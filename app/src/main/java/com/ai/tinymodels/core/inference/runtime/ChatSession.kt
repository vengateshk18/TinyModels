package com.ai.tinymodels.core.inference.runtime

import kotlinx.coroutines.flow.Flow

/** A prior turn, used to restore a conversation's context after reopen. */
data class ChatTurn(val role: Role, val text: String, val imagePaths: List<String> = emptyList()) {
    enum class Role { USER, ASSISTANT }
}

/**
 * One live conversation with a loaded model — the contract every runtime's
 * session implements, so the chat layer never touches runtime specifics.
 *
 * Core contract: [send] emits CUMULATIVE text (the full reply so far), never
 * deltas; consumers replace their display buffer on every emission. Both
 * LiteRT-LM and MediaPipe emit deltas natively, so each session implementation
 * accumulates internally.
 */
interface ChatSession {
    /** Estimated tokens currently occupying the context window. */
    val estimatedTokensInContext: Int

    /** True when estimated usage exceeds the high-water mark (80% of budget). */
    val isContextNearFull: Boolean

    /** Send [text] (with optional attached photo paths) and stream CUMULATIVE reply text. */
    fun send(text: String, imagePaths: List<String> = emptyList()): Flow<String>

    /** Release this conversation's KV-cache memory. The model stays loaded. */
    fun close()

    /**
     * One-shot (non-streaming) summarization of older turns, used by the
     * context-compaction flow. Returns null on failure — the caller then
     * falls back to the plain sliding-window trim.
     */
    suspend fun summarizeOlderTurns(older: List<ChatTurn>): String?
}

/**
 * Shared session bookkeeping: token accounting and the context high-water
 * mark, identical for every runtime so the usage gauge behaves the same
 * regardless of which engine serves the chat.
 */
abstract class BaseChatSession(
    protected val maxContextTokens: Int,
    initialTokens: Int = 0
) : ChatSession {

    final override var estimatedTokensInContext: Int = initialTokens
        protected set

    final override val isContextNearFull: Boolean
        get() = estimatedTokensInContext >= maxContextTokens * HIGH_WATER_RATIO

    /** Counts a sent prompt. */
    protected fun countPrompt(text: String) {
        estimatedTokensInContext += SessionBudget.estimateTokens(text)
    }

    /** Counts one attached photo (vision encoder output). */
    protected fun countImage() {
        estimatedTokensInContext += SessionBudget.IMAGE_TOKEN_COST
    }

    /** Counts streamed reply characters as they arrive. */
    protected fun countResponseDelta(chars: Int) {
        estimatedTokensInContext += chars / SessionBudget.CHARS_PER_TOKEN
    }

    companion object {
        private const val HIGH_WATER_RATIO = 0.8
    }
}
