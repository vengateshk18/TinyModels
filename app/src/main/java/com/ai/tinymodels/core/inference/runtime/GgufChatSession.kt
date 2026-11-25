package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.core.common.TinyLog
import com.ai.tinymodels.core.inference.gguf.GgufCompaction
import com.ai.tinymodels.core.inference.gguf.GgufDigestAttempt
import com.ai.tinymodels.domain.model.SamplerSettings
import com.dark.gguf_lib.GGMLEngine
import com.dark.gguf_lib.models.GenerationEvent
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GGUF (llama.cpp) [ChatSession] backed by [GGMLEngine].
 *
 * GGMLEngine has no per-session history API, so this class renders the full
 * transcript into a JSON array on every [send] (same strategy as MediaPipe).
 * Tokens are accumulated internally and emitted as CUMULATIVE text.
 *
 * The stream lifecycle mirrors ToolNeuron's InferenceService.collectFlow
 * discipline: collection happens inside a child job so [awaitClose] can stop
 * the native decode loop the moment the collector goes away (stop button,
 * session swap, ViewModel cleared). An abandoned decode is exactly what makes
 * a later runtime close()/initialize() free llama.cpp weights from under it —
 * a use-after-free the JVM reports as SIGSEGV, not a catchable exception.
 */
class GgufChatSession(
    private val engine: GGMLEngine,
    private val runtime: GgufRuntime,
    request: SessionRequest,
) : BaseChatSession(request.maxContextTokens, initialTokensFor(request)) {

    private val systemInstruction: String? = request.systemInstruction
    private val priorSummary: String? = request.priorSummary
    private val sampler: SamplerSettings = request.sampler
    private val modelName: String? = request.modelName

    /** Reply budget computed once from the session's context window. */
    private val generationTokens: Int = computeGenerationTokens(request.maxContextTokens)

    /** Serializes transcript mutations (collector thread vs stream job). */
    private val transcriptLock = Any()

    private val turns = ArrayDeque<ChatTurn>()
    private val closed = AtomicBoolean(false)

    init {
        val trimmed = trimmedHistoryFor(request)
        synchronized(transcriptLock) { turns.addAll(trimmed) }
        TinyLog.i(
            JOURNEY_TAG,
            "JOURNEY: GGUF session created — history=${trimmed.size}/${request.history.size} turns, " +
                "maxContextTokens=${request.maxContextTokens}, maxTokens=$generationTokens"
        )
    }

    override fun send(text: String, imagePaths: List<String>): Flow<String> = callbackFlow {
        if (closed.get()) {
            close(IllegalStateException("Session is closed"))
            return@callbackFlow
        }
        Log.i("GGUF_LOAD", "send:start chars=${text.length} images=${imagePaths.size} turns=${turns.size} loaded=${engine.isLoaded}")
        synchronized(transcriptLock) { turns.addLast(ChatTurn(ChatTurn.Role.USER, text, imagePaths)) }
        countPrompt(text)
        repeat(imagePaths.size) { countImage() }

        // mtmd needs the projector's media marker in the prompt to know where
        // to splice the image embeddings. Read it from the loaded projector
        // (it is model-specific) and only for the turn carrying the bitmaps.
        val vlmMarker = if (imagePaths.isNotEmpty() && engine.isVlmLoaded) {
            engine.getVlmDefaultMarker()
        } else {
            null
        }

        val messagesJson = synchronized(transcriptLock) {
            buildMessagesJson(systemInstruction, priorSummary, turns.toList(), vlmMarker)
        }
        val accumulated = StringBuilder()
        val recorded = AtomicBoolean(false)

        val streamJob = launch {
            try {
                // Hold the runtime's engine lock for the whole stream so an
                // unload/reload (model swap, memory trim, benchmark) can never
                // touch the weights mid-decode.
                runtime.withEngineLock {
                    // Full transcript is sent each time; reset stale KV state to avoid
                    // native crashes/state corruption across generations.
                    runCatching { engine.resetKvCache() }
                        .onFailure { Log.w("GGUF_LOAD", "resetKvCache failed: ${it.message}") }
                    val flow = engineFlow(messagesJson, imagePaths)
                    Log.i("GGUF_LOAD", "send:collecting maxTokens=$generationTokens vlm=${imagePaths.isNotEmpty() && engine.isVlmLoaded}")
                    flow.collect { event ->
                        when (event) {
                            is GenerationEvent.Token -> {
                                if (event.text.isNotEmpty()) {
                                    accumulated.append(event.text)
                                    countResponseDelta(event.text.length)
                                    trySend(accumulated.toString())
                                }
                            }
                            // Terminal — the same contract as ToolNeuron's
                            // IGenerationCallback.onError. Logging and
                            // continuing past a broken turn leaves the next
                            // send talking to a native side that abandoned
                            // mid-prefill.
                            is GenerationEvent.Error ->
                                throw RuntimeException(event.message)
                            else -> {}
                        }
                    }
                }
                Log.i("GGUF_LOAD", "send:complete chars=${accumulated.length}")
                close()
            } catch (e: CancellationException) {
                Log.i("GGUF_LOAD", "send:cancelled chars=${accumulated.length}")
                throw e
            } catch (t: Throwable) {
                Log.e("GGUF_LOAD", "send:failed chars=${accumulated.length} err=${t.message}", t)
                TinyLog.e(TAG, "GGUF generate failed: ${t.message}", t)
                close(t)
            } finally {
                // A partial reply still belongs in the transcript (and in the
                // persisted assistant message, which the caller writes from
                // the last emission it saw).
                if (recorded.compareAndSet(false, true)) {
                    val reply = accumulated.toString()
                    if (reply.isNotEmpty()) {
                        synchronized(transcriptLock) {
                            turns.addLast(ChatTurn(ChatTurn.Role.ASSISTANT, reply))
                        }
                    }
                }
            }
        }

        awaitClose {
            // Collector cancelled (stop button / conversation replaced / VM
            // cleared): tell the native decode loop to stop. Without this the
            // decode keeps running on its detached thread, and the next
            // unload/reload frees the weights from under it.
            try {
                engine.stopGeneration()
            } catch (t: Throwable) {
                TinyLog.w(TAG, "stopGeneration after cancel failed: ${t.message}")
            }
        }
    }.buffer(Channel.UNLIMITED)

    /** Nothing native persists per [GgufChatSession] itself. */
    override fun close() {
        closed.set(true)
        TinyLog.i(TAG, "GGUF session closed (model stays loaded)")
    }

    /**
     * One-shot (non-streaming) summarization of older turns, used by the
     * context-compaction flow. Returns null on failure — the caller then
     * falls back to the plain sliding-window trim.
     *
     * The vendored engine owns the summarize call (`compact`), which runs the
     * model outside the live chat turn. Everything the app decides —
     * transcript, token ceiling, acceptance — lives in [GgufCompaction] so it
     * stays unit-tested.
     */
    override suspend fun summarizeOlderTurns(older: List<ChatTurn>): String? =
        runtime.withEngineLock {
            GgufCompaction.digest(older, maxContextTokens) { transcript, maxSummaryTokens ->
                try {
                    val result = engine.compact(
                        transcript,
                        maxSummaryTokens,
                        SessionBudget.SUMMARIZE_PROMPT,
                    ) { }
                    GgufDigestAttempt(result.summary, result.success)
                } catch (throwable: Throwable) {
                    TinyLog.w(TAG, "GGUF compaction failed: ${throwable.message}")
                    GgufDigestAttempt(null, success = false)
                }
            }
        }

    /**
     * Route text vs vision traffic the way ToolNeuron's InferenceCoordinator
     * does: images attached + a loaded projector → the VLM flow; plain text
     * otherwise. maxTokens follows the shared generation headroom (25% of the
     * window) while history was already trimmed to the remaining 75% by
     * [trimmedHistoryFor], so the transcript handed to llama.cpp can never
     * overrun the loaded context window.
     */
    private fun engineFlow(messagesJson: String, imagePaths: List<String>): Flow<GenerationEvent> {
        val useVlm = imagePaths.isNotEmpty() && engine.isVlmLoaded
        if (useVlm) {
            val imageBytes = imagePaths.mapNotNull { path ->
                runCatching { File(path).readBytes() }.getOrNull()
            }
            if (imageBytes.isNotEmpty()) {
                return engine.generateVlmFlow(
                    messagesJson = messagesJson,
                    imageData = imageBytes,
                    maxTokens = generationTokens,
                    vtKeys = emptyList(),
                    vlmKvKey = null,
                    imageQuality = com.dark.gguf_lib.ImageQuality.HIGH,
                )
            }
        }
        return engine.generateMultiTurnFlow(messagesJson, generationTokens)
    }

    private fun buildMessagesJson(
        systemInstruction: String?,
        priorSummary: String?,
        turns: List<ChatTurn>,
        vlmMarker: String? = null,
    ): String {
        val arr = JSONArray()
        val system = SessionBudget.foldSystemInstruction(systemInstruction, priorSummary)
        if (!system.isNullOrBlank()) {
            arr.put(JSONObject().apply {
                put("role", "system")
                put("content", system)
            })
        }
        for ((index, turn) in turns.withIndex()) {
            val content = if (
                vlmMarker != null &&
                index == turns.lastIndex &&
                turn.role == ChatTurn.Role.USER
            ) {
                VlmPromptMarker.apply(turn.text, vlmMarker, turn.imagePaths.size)
            } else {
                turn.text
            }
            arr.put(JSONObject().apply {
                put("role", if (turn.role == ChatTurn.Role.USER) "user" else "assistant")
                put("content", content)
            })
        }
        return arr.toString()
    }

    companion object {
        private const val TAG = "GgufChatSession"

        // Same tag as ChatViewModel so one logcat filter shows the entire turn.
        private const val JOURNEY_TAG = "TinyModelsJourney"

        /** Never hand the engine a zero-token budget when the window is tiny. */
        private const val MIN_GENERATION_TOKENS = 64

        /**
         * Reply budget: the generation-headroom share of the context window,
         * floor-clamped so a stubbornly small window still produces something.
         */
        private fun computeGenerationTokens(maxContextTokens: Int): Int =
            (maxContextTokens * SessionBudget.GENERATION_HEADROOM_RATIO)
                .toInt().coerceAtLeast(MIN_GENERATION_TOKENS)

        /** Same headroom policy as the MediaPipe/LiteRT paths. */
        private fun budgetWindow(request: SessionRequest): Int =
            (request.maxContextTokens * (1 - SessionBudget.GENERATION_HEADROOM_RATIO)).toInt()

        private fun trimmedHistoryFor(request: SessionRequest): List<ChatTurn> =
            SessionBudget.trimToBudget(request.history, budgetWindow(request))

        private fun initialTokensFor(request: SessionRequest): Int {
            val folded = SessionBudget.foldSystemInstruction(
                request.systemInstruction,
                request.priorSummary,
            )
            return trimmedHistoryFor(request).sumOf {
                SessionBudget.estimateTokens(it.text) +
                    it.imagePaths.size * SessionBudget.IMAGE_TOKEN_COST
            } + (folded?.let { SessionBudget.estimateTokens(it) } ?: 0)
        }
    }
}
