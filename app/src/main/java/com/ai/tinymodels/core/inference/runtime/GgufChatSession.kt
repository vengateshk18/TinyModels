package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.core.common.TinyLog
import com.ai.tinymodels.domain.model.SamplerSettings
import com.dark.gguf_lib.GGMLEngine
import com.dark.gguf_lib.models.GenerationEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GGUF (llama.cpp) [ChatSession] backed by [GGMLEngine].
 *
 * GGMLEngine has no per-session history API, so this class renders the full
 * transcript into a JSON array on every [send] (same strategy as MediaPipe).
 * Tokens are accumulated internally and emitted as CUMULATIVE text.
 */
class GgufChatSession(
    private val engine: GGMLEngine,
    request: SessionRequest,
) : BaseChatSession(request.maxContextTokens, initialTokensFor(request)) {

    private val systemInstruction: String? = request.systemInstruction
    private val priorSummary: String? = request.priorSummary
    private val sampler: SamplerSettings = request.sampler
    private val modelName: String? = request.modelName

    private val turns = ArrayDeque<ChatTurn>()
    private val closed = AtomicBoolean(false)

    init {
        val trimmed = trimmedHistoryFor(request)
        turns.addAll(trimmed)
    }

    override fun send(text: String, imagePaths: List<String>): Flow<String> = callbackFlow {
        if (closed.get()) {
            close(IllegalStateException("Session is closed"))
            return@callbackFlow
        }
        val newTurn = ChatTurn(ChatTurn.Role.USER, text, emptyList())
        turns.addLast(newTurn)
        countPrompt(text)

        val messagesJson = buildMessagesJson(systemInstruction, priorSummary, turns.toList())

        val accumulated = StringBuilder()
        try {
            engine.generateMultiTurnFlow(messagesJson, (maxContextTokens * 0.25).toInt().coerceAtLeast(64))
                .collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            val piece = event.text
                            if (piece.isNotEmpty()) {
                                accumulated.append(piece)
                                countResponseDelta(piece.length)
                                trySend(accumulated.toString())
                            }
                        }
                        is GenerationEvent.Error -> {
                            TinyLog.e(TAG, "GGUF generation error: ${event.message}")
                        }
                        else -> {}
                    }
                }
        } catch (t: Throwable) {
            TinyLog.e(TAG, "GGUF generate failed: ${t.message}", t)
            close(t)
            return@callbackFlow
        }

        // Record assistant turn
        if (accumulated.isNotEmpty()) {
            turns.addLast(ChatTurn(ChatTurn.Role.ASSISTANT, accumulated.toString()))
        }
    }

    override fun close() {
        closed.set(true)
    }

    override suspend fun summarizeOlderTurns(older: List<ChatTurn>): String? = null

    private fun buildMessagesJson(
        systemInstruction: String?,
        priorSummary: String?,
        turns: List<ChatTurn>,
    ): String {
        val arr = JSONArray()
        val system = SessionBudget.foldSystemInstruction(systemInstruction, priorSummary)
        if (!system.isNullOrBlank()) {
            arr.put(JSONObject().apply {
                put("role", "system")
                put("content", system)
            })
        }
        for (turn in turns) {
            arr.put(JSONObject().apply {
                put("role", if (turn.role == ChatTurn.Role.USER) "user" else "assistant")
                put("content", turn.text)
            })
        }
        return arr.toString()
    }

    companion object {
        private const val TAG = "GgufChatSession"

        private fun trimmedHistoryFor(request: SessionRequest): List<ChatTurn> =
            SessionBudget.trimToBudget(request.history, request.maxContextTokens)

        private fun initialTokensFor(request: SessionRequest): Int =
            request.history.sumOf { SessionBudget.estimateTokens(it.text) } +
                request.history.sumOf { it.imagePaths.size * SessionBudget.IMAGE_TOKEN_COST }
    }
}