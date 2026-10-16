package com.ai.tinymodels.core.inference.runtime

import android.util.Log
import com.ai.tinymodels.domain.model.SamplerSettings
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.google.mediapipe.tasks.genai.llminference.PromptTemplates
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * The MediaPipe (.task) implementation of [ChatSession].
 *
 * MediaPipe sessions have NO history or system-prompt API, so this class is
 * stateless per turn the same way the library's own `LlmInference`
 * convenience path is: every [send] renders the FULL transcript through
 * [MediaPipePromptBuilder], creates a throwaway [LlmInferenceSession] with the
 * user's sampler (and all-empty prompt templates, so the library adds no
 * wrapping of its own), streams the reply, and closes the session. Chat
 * reopen / regenerate / edit therefore behave exactly like the LiteRT-LM
 * path — a rebuild simply re-renders the prompt from stored history.
 *
 * Native cancellation: the library's task lock is process-wide per model and
 * is only released by the final `done=true` callback. Stopping mid-reply
 * cancels the native task and waits briefly for that callback before closing
 * the session; a subsequent turn retries if the previous lock has not settled.
 *
 * Upstream note: MediaPipe LLM Inference is in maintenance mode (Google
 * points to LiteRT-LM), but it remains the only library that executes `.task`
 * bundles.
 */
@Suppress("DEPRECATION") // MediaPipe LLM Inference is deprecated upstream but still the only .task executor.
class MediaPipeSession(
    private val llmInference: LlmInference,
    request: SessionRequest
) : BaseChatSession(request.maxContextTokens, initialTokensFor(request)) {

    private val systemInstruction: String? = request.systemInstruction
    private val priorSummary: String? = request.priorSummary
    private val sampler: SamplerSettings = request.sampler
    private val modelName: String? = request.modelName
    private val baseTemplate: ChatTemplate = ChatTemplate.fromName(modelName)
    @Volatile private var winner: ChatTemplate? = null

    /** Live transcript: trimmed history + every turn sent/answered since. */
    private val turns = ArrayDeque<ChatTurn>()

    /** Serializes transcript mutations (collector thread vs native callback thread). */
    private val transcriptLock = Any()

    @Volatile
    private var closed = false

    init {
        val trimmed = trimmedHistoryFor(request)
        turns.addAll(trimmed)
        Log.i(
            JOURNEY_TAG,
            "JOURNEY: MediaPipe session created — history=${trimmed.size}/${request.history.size} turns, " +
                "systemPrompt=${if (systemInstruction.isNullOrBlank()) "<none>" else "${systemInstruction.length} chars"}, " +
                "priorSummary=${priorSummary?.let { "${it.length} chars" } ?: "none"}, " +
                "sampler(temp=${sampler.temperature}, topK=${sampler.topK}, topP=${sampler.topP}), " +
                "maxContextTokens=${request.maxContextTokens}"
        )
    }

    override fun send(text: String, imagePaths: List<String>): Flow<String> = callbackFlow {
        if (closed) {
            close(IllegalStateException("Conversation session is closed"))
            return@callbackFlow
        }
        // MediaPipe text models cannot read photos; the app's vision guards
        // already strip them — but never let one reach the prompt builder.
        val newTurn = ChatTurn(ChatTurn.Role.USER, text, emptyList())
        synchronized(transcriptLock) { turns.addLast(newTurn) }
        countPrompt(text)
        repeat(imagePaths.size) { countImage() }

        val turnsSnapshot = snapshotTurns()
        val tmpl = ensureWinner(turnsSnapshot)
        Log.i(TAG, "MP using chat template: $tmpl (base=$baseTemplate, modelName=$modelName)")
        val prompt = MediaPipePromptBuilder.build(systemInstruction, priorSummary, turnsSnapshot, tmpl)
        val preview = if (prompt.length > 400) prompt.take(400) + "…" else prompt
        Log.i(
            JOURNEY_TAG,
            "JOURNEY: MediaPipe prompt handed to model (${prompt.length} chars, " +
                "~${SessionBudget.estimateTokens(prompt)} tokens, ${turnsSnapshotSize()} turns): \"$preview\""
        )

        val session = try {
            LlmInferenceSession.createFromOptions(llmInference, sessionOptions()).also {
                it.addQueryChunk(prompt)
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "Failed to start MediaPipe generation: ${throwable.message}", throwable)
            close(throwable)
            return@callbackFlow
        }

        val settled = CountDownLatch(1)
        val accumulated = StringBuilder()

        val listener = object : ProgressListener<String> {
            override fun run(partialResult: String?, done: Boolean) {
                val piece = partialResult.orEmpty()
                android.util.Log.i(TAG, "MP listener: piece=${piece.length} done=$done acc=${accumulated.length}")
                if (piece.isNotEmpty()) {
                    accumulated.append(piece)
                    countResponseDelta(piece.length)
                    trySend(accumulated.toString())
                }
                if (done) {
                    val reply = accumulated.toString()
                    if (reply.isNotEmpty()) {
                        synchronized(transcriptLock) {
                            turns.addLast(ChatTurn(ChatTurn.Role.ASSISTANT, reply))
                        }
                    }
                    if (reply.isEmpty()) {
                        android.util.Log.w(TAG, "MP generation completed but produced ZERO chars (model likely hit EOS immediately; check chat template / model)")
                    }
                    Log.i(
                        JOURNEY_TAG,
                        "JOURNEY: MediaPipe reply complete (${reply.length} chars, ~${SessionBudget.estimateTokens(reply)} tokens)"
                    )
                    settled.countDown()
                    close()
                }
            }
        }

        val started: ListenableFuture<String> = try {
            startGenerationWithLockRetry(session, listener)
        } catch (throwable: Throwable) {
            settled.countDown()
            session.closeQuietly()
            close(throwable)
            return@callbackFlow
        }

        // Async native failures complete the future with an exception and may
        // never invoke the listener — surface them instead of hanging.
        started.addListener(
            {
                try {
                    val r = started.get()
                    android.util.Log.i(TAG, "MP future complete, chars=${r?.length ?: -1}")
                } catch (throwable: Throwable) {
                    android.util.Log.e(TAG, "MP future error: ${throwable.message}", throwable)
                    settled.countDown()
                    close(throwable.cause ?: throwable)
                }
            },
            { runnable -> runnable.run() }
        )

        awaitClose {
            // Collector cancelled (stop button / benchmark cap / screen gone):
            // stop the native task, then wait briefly for the final done
            // callback that releases the process-wide task lock.
            try {
                session.cancelGenerateResponseAsync()
            } catch (throwable: Throwable) {
                Log.w(TAG, "cancelGenerateResponseAsync failed: ${throwable.message}")
            }
            if (!settled.await(CANCEL_SETTLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Generation did not settle in ${CANCEL_SETTLE_TIMEOUT_MS}ms — closing session anyway")
            }
            session.closeQuietly()
        }
    }.buffer(Channel.UNLIMITED)

    /** Nothing native persists per session — each turn owns its own session. */
    override fun close() {
        closed = true
        Log.i(TAG, "Session closed (model stays loaded)")
    }

    /**
     * One-shot (non-streaming) summarization of older turns, used by the
     * context-compaction flow. Returns null on failure — the caller then
     * falls back to the plain sliding-window trim.
     */
    override suspend fun summarizeOlderTurns(older: List<ChatTurn>): String? {
        if (older.isEmpty()) return null
        val transcript = ConversationSession.summarizeTranscript(older)
        Log.i(
            JOURNEY_TAG,
            "JOURNEY: compaction prompt sent to model — ${older.size} older turns, " +
                "transcript ${transcript.length} chars:\n$transcript"
        )
        return try {
            val prompt = MediaPipePromptBuilder.build(
                systemInstruction = null,
                priorSummary = null,
                turns = listOf(ChatTurn(ChatTurn.Role.USER, SessionBudget.SUMMARIZE_PROMPT + transcript))
            )
            val session = LlmInferenceSession.createFromOptions(llmInference, sessionOptions())
            val summary = try {
                session.addQueryChunk(prompt)
                session.generateResponse().trim().takeIf { it.isNotBlank() }
            } finally {
                session.closeQuietly()
            }
            Log.i(
                JOURNEY_TAG,
                "JOURNEY: compaction summary received (${summary?.length ?: 0} chars): \"${summary ?: ""}\""
            )
            summary
        } catch (throwable: Throwable) {
            Log.w(TAG, "Compaction summarization failed: ${throwable.message}")
            null
        }
    }

    private fun sessionOptions(): LlmInferenceSession.LlmInferenceSessionOptions {
        val values = MediaPipeSamplerMapper.map(sampler)
        return LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(values.topK)
            .setTopP(values.topP)
            .setTemperature(values.temperature)
            .setRandomSeed(0)
            .setPromptTemplates(
                // ALL-EMPTY on purpose: the session must not wrap chunks — the
                // full prompt (its own template markers included) is built by
                // MediaPipePromptBuilder.
                PromptTemplates.builder().build()
            )
            .build()
    }

    /**
     * The native task lock is process-wide per loaded model and is released
     * by the previous turn's final callback; a turn started right after a
     * cancellation can therefore race it. Retry briefly instead of failing
     * the user's turn.
     */
    private suspend fun startGenerationWithLockRetry(
        session: LlmInferenceSession,
        listener: ProgressListener<String>
    ): ListenableFuture<String> {
        var attempt = 0
        while (true) {
            try {
                return session.generateResponseAsync(listener)
            } catch (throwable: Throwable) {
                val lockBusy = throwable is IllegalStateException &&
                    throwable.message?.contains("lock", ignoreCase = true) == true
                if (!lockBusy || attempt++ >= LOCK_RETRIES) throw throwable
                delay(LOCK_RETRY_DELAY_MS)
            }
        }
    }

    private fun ensureWinner(turnsSnapshot: List<ChatTurn>): ChatTemplate {
        // NOTE: Previously we probed each candidate template via the sync
        // `generateResponse()`. That path calls nativePredictSync() which
        // aborts the whole process if the prefill runner fails (JNI
        // NewByteArray-with-pending-exception), unrelated to correctness.
        // Deterministically use the template chosen from the model name. Async
        // generation below will surface any remaining failure cleanly.
        return baseTemplate
    }

    private fun snapshotTurns(): List<ChatTurn> = synchronized(transcriptLock) { turns.toList() }

    private fun turnsSnapshotSize(): Int = synchronized(transcriptLock) { turns.size }

    private fun LlmInferenceSession.closeQuietly() {
        try {
            close()
        } catch (throwable: Throwable) {
            Log.w(TAG, "Error closing MediaPipe session: ${throwable.message}")
        }
    }

    companion object {
        private const val TAG = "MediaPipeSession"

        // Same tag as ChatViewModel so one logcat filter shows the entire turn.
        private const val JOURNEY_TAG = "TinyModelsJourney"

        /** How long a cancelled generation may take to settle before force-closing. */
        private const val CANCEL_SETTLE_TIMEOUT_MS = 3_000L

        /** Retry window for the process-wide native task lock (~2s total). */
        private const val LOCK_RETRIES = 40
        private const val LOCK_RETRY_DELAY_MS = 50L

        /** Same reply headroom policy as the LiteRT path. */
        private fun budgetWindow(request: SessionRequest): Int =
            (request.maxContextTokens * (1 - SessionBudget.GENERATION_HEADROOM_RATIO)).toInt()

        private fun trimmedHistoryFor(request: SessionRequest): List<ChatTurn> =
            SessionBudget.trimToBudget(request.history, budgetWindow(request))

        private fun initialTokensFor(request: SessionRequest): Int {
            val trimmed = trimmedHistoryFor(request)
            val folded = SessionBudget.foldSystemInstruction(
                request.systemInstruction,
                request.priorSummary
            )
            return trimmed.sumOf {
                SessionBudget.estimateTokens(it.text) +
                    it.imagePaths.size * SessionBudget.IMAGE_TOKEN_COST
            } +
                (folded?.let { SessionBudget.estimateTokens(it) } ?: 0) +
                request.tools.size * SessionBudget.TOOL_TOKEN_OVERHEAD
        }
    }
}
