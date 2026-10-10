package com.example.tinymodels.core.inference

import android.util.Log
import com.example.tinymodels.domain.model.SamplerSettings
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A prior turn, used to restore a conversation's context after reopen. */
data class ChatTurn(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

/**
 * Wraps a single LiteRT-LM [Conversation] and manages its context window.
 *
 * Responsibilities:
 *  - Build the conversation with the user's sampler settings + prior history
 *    (context restore across app restarts via [ConversationConfig.initialMessages]).
 *  - Track an estimated token budget and report when the context is nearly full.
 *  - Stream assistant replies. Note: LiteRT-LM emits CUMULATIVE text per
 *    emission, so consumers must REPLACE, never append.
 *  - Release KV-cache memory via [close] without reloading the model.
 */
class ConversationSession(
    private val conversation: Conversation,
    private val maxContextTokens: Int
) {
    /** Estimated tokens currently occupying the context window. */
    var estimatedTokensInContext: Int = 0
        private set

    /** Tracks the last cumulative reply length so we only count new tokens. */
    private var lastStreamedLength: Int = 0

    /** True when estimated usage exceeds the high-water mark (80% of budget). */
    val isContextNearFull: Boolean
        get() = estimatedTokensInContext >= (maxContextTokens * HIGH_WATER_RATIO)

    /**
     * Send [text] and stream the assistant's reply as CUMULATIVE text.
     *
     * LiteRT-LM's `sendMessageAsync` emits **delta chunks** (only the new tokens),
     * NOT cumulative text. This method accumulates them internally and emits the
     * full cumulative text on each emission so the caller can simply replace its
     * display buffer.
     */
    fun send(text: String): Flow<String> {
        estimatedTokensInContext += estimateTokens(text)
        lastStreamedLength = 0
        val accumulated = StringBuilder()
        return conversation.sendMessageAsync(text).map { message ->
            val delta = message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
            accumulated.append(delta)
            val cumulative = accumulated.toString()
            val newChars = delta.length
            lastStreamedLength = cumulative.length
            estimatedTokensInContext += newChars / CHARS_PER_TOKEN
            cumulative
        }
    }

    /** Release this conversation's KV-cache memory. The engine stays loaded. */
    fun close() {
        try {
            conversation.close()
        } catch (throwable: Throwable) {
            Log.w(TAG, "Error closing conversation: ${throwable.message}")
        }
    }

    /**
     * One-shot (non-streaming) summarization of older turns, used by the
     * context-compaction flow. Returns null on failure — the caller then
     * falls back to the plain sliding-window trim.
     */
    suspend fun summarizeOlderTurns(older: List<ChatTurn>): String? {
        if (older.isEmpty()) return null
        val transcript = older.joinToString("\n") {
            (if (it.role == ChatTurn.Role.USER) "User: " else "Assistant: ") + it.text
        }
        return try {
            val sb = StringBuilder()
            conversation.sendMessageAsync(SUMMARIZE_PROMPT + transcript)
                .collect { message ->
                    sb.append(
                        message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString(separator = "") { it.text }
                    )
                }
            sb.toString().trim().takeIf { it.isNotBlank() }
        } catch (throwable: Throwable) {
            Log.w(TAG, "Compaction summarization failed: ${throwable.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ConversationSession"
        private const val HIGH_WATER_RATIO = 0.8
        private const val CHARS_PER_TOKEN = 4

        /** Fraction of the context budget reserved for the assistant's reply. */
        private const val GENERATION_HEADROOM_RATIO = 0.25

        /** Prompt used when asking the model to digest older turns. */
        private const val SUMMARIZE_PROMPT =
            "Summarize the following conversation so far in under 200 tokens. " +
                "Keep: user goals, decisions, constraints, key facts, and any " +
                "unresolved questions. Drop pleasantries and repetition. " +
                "Reply with the summary only.\n\n"

        fun estimateTokens(text: String): Int =
            (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)

        /**
         * Build a session from an [Engine], restoring prior [history] and applying
         * [sampler]. History is trimmed to the most recent turns that fit the budget
         * (sliding window) so reopening a long chat cannot overflow the KV cache.
         * [priorSummary] (a compaction digest of older, summarized-away turns)
         * is folded into the system instruction so the model keeps the gist.
         */
        fun create(
            engine: Engine,
            systemInstruction: String?,
            history: List<ChatTurn>,
            sampler: SamplerSettings,
            maxContextTokens: Int,
            priorSummary: String? = null
        ): ConversationSession {
            // Reserve headroom for the model's reply (and tokenizer estimation
            // error) so the prompt + history never overflow the engine's real
            // context window — overflow surfaces as "Status code 13: Task
            // failed with large input" from the native task.
            val trimmed = trimToBudget(history, (maxContextTokens * (1 - GENERATION_HEADROOM_RATIO)).toInt())
            val initialMessages = trimmed.map { turn ->
                when (turn.role) {
                    ChatTurn.Role.USER -> Message.user(turn.text)
                    ChatTurn.Role.ASSISTANT -> Message.model(turn.text)
                }
            }
            // Fold any prior compaction digest into the system instruction so
            // the model retains the gist of summarized-away older turns.
            val effectiveSystemInstruction = buildString {
                systemInstruction?.let { append(it) }
                priorSummary?.let {
                    if (isNotEmpty()) append("\n\n")
                    append("Conversation so far (summary of earlier messages):\n")
                    append(it)
                }
            }.takeIf { it.isNotBlank() }
            val config = ConversationConfig(
                systemInstruction = effectiveSystemInstruction?.let { Contents.of(Content.Text(it)) },
                initialMessages = initialMessages,
                samplerConfig = SamplerConfig(
                    topK = sampler.topK,
                    topP = sampler.topP,
                    temperature = sampler.temperature
                )
            )
            val conversation = engine.createConversation(config)
            val session = ConversationSession(conversation, maxContextTokens)
            session.estimatedTokensInContext = trimmed.sumOf { estimateTokens(it.text) }
            return session
        }

        /**
         * Sliding-window trim: keep the most recent turns whose combined estimated
         * tokens fit within [budget]. Always keeps at least the last turn if present.
         */
        fun trimToBudget(history: List<ChatTurn>, budget: Int): List<ChatTurn> {
            if (history.isEmpty()) return emptyList()
            var tokens = 0
            val kept = mutableListOf<ChatTurn>()
            for (turn in history.asReversed()) {
                val turnTokens = estimateTokens(turn.text)
                if (tokens + turnTokens > budget && kept.isNotEmpty()) break
                tokens += turnTokens
                kept.add(0, turn)
            }
            return kept
        }
    }
}