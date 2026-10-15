package com.ai.tinymodels.core.inference.runtime

import android.util.Log
import com.ai.tinymodels.domain.model.SamplerSettings
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The LiteRT-LM (.litertlm) implementation of [ChatSession]: wraps a single
 * LiteRT [Conversation] and manages its context window.
 *
 * Responsibilities:
 *  - Build the conversation with the user's sampler settings + prior history
 *    (context restore across app restarts via [ConversationConfig.initialMessages]).
 *  - Track the estimated token budget via [BaseChatSession].
 *  - Stream assistant replies as CUMULATIVE text. LiteRT-LM emits delta
 *    chunks per emission, so this class accumulates them internally.
 *  - Release KV-cache memory via [close] without reloading the model.
 */
class ConversationSession(
    private val conversation: Conversation,
    maxContextTokens: Int,
    initialTokens: Int = 0
) : BaseChatSession(maxContextTokens, initialTokens) {

    /**
     * Send [text] (with optional attached photo paths) and stream the
     * assistant's reply as CUMULATIVE text.
     *
     * Photos ride as [Content.ImageFile] parts in the same user message
     * (multimodal models like Gemma 3). Text-only turns keep the exact
     * previous String path — zero behavior change when no photo is attached.
     */
    override fun send(text: String, imagePaths: List<String>): Flow<String> {
        Log.i(JOURNEY_TAG, "JOURNEY: prompt sent to model (${text.length} chars, ~${SessionBudget.estimateTokens(text)} tokens, ${imagePaths.size} image(s)): \"$text\"")
        countPrompt(text)
        repeat(imagePaths.size) { countImage() }
        val accumulated = StringBuilder()
        val outgoing = if (imagePaths.isEmpty()) {
            conversation.sendMessageAsync(text)
        } else {
            val parts = imagePaths.map { Content.ImageFile(it) } + Content.Text(text)
            conversation.sendMessageAsync(Message.user(Contents.of(parts)))
        }
        return outgoing.map { message ->
            val delta = message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
            accumulated.append(delta)
            countResponseDelta(delta.length)
            accumulated.toString()
        }
    }

    /** Release this conversation's KV-cache memory. The engine stays loaded. */
    override fun close() {
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
    override suspend fun summarizeOlderTurns(older: List<ChatTurn>): String? {
        if (older.isEmpty()) return null
        val transcript = summarizeTranscript(older)
        Log.i(
            JOURNEY_TAG,
            "JOURNEY: compaction prompt sent to model — ${older.size} older turns, " +
                "transcript ${transcript.length} chars:\n$transcript"
        )
        return try {
            val sb = StringBuilder()
            conversation.sendMessageAsync(SessionBudget.SUMMARIZE_PROMPT + transcript)
                .collect { message ->
                    sb.append(
                        message.contents.contents
                            .filterIsInstance<Content.Text>()
                            .joinToString(separator = "") { it.text }
                    )
                }
            val summary = sb.toString().trim().takeIf { it.isNotBlank() }
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

    companion object {
        private const val TAG = "ConversationSession"

        // Same tag as ChatViewModel/WebSearchToolSet so one logcat filter
        // (adb logcat -s TinyModelsJourney) shows the entire turn.
        private const val JOURNEY_TAG = "TinyModelsJourney"

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
            priorSummary: String? = null,
            /** Model-callable tools (e.g. web search). Empty = no tool overhead. */
            tools: List<ToolProvider> = emptyList()
        ): ConversationSession {
            // Reserve headroom for the model's reply (and tokenizer estimation
            // error) so the prompt + history never overflow the engine's real
            // context window — overflow surfaces as "Status code 13: Task
            // failed with large input" from the native task.
            val trimmed = SessionBudget.trimToBudget(
                history,
                (maxContextTokens * (1 - SessionBudget.GENERATION_HEADROOM_RATIO)).toInt()
            )
            val initialMessages = trimmed.map { turn ->
                when (turn.role) {
                    ChatTurn.Role.USER -> turn.toUserMessage()
                    ChatTurn.Role.ASSISTANT -> Message.model(turn.text)
                }
            }
            // Fold any prior compaction digest into the system instruction so
            // the model retains the gist of summarized-away older turns.
            val effectiveSystemInstruction =
                SessionBudget.foldSystemInstruction(systemInstruction, priorSummary)
            val config = ConversationConfig(
                systemInstruction = effectiveSystemInstruction?.let { Contents.of(Content.Text(it)) },
                initialMessages = initialMessages,
                tools = tools,
                // The runtime executes tool calls (e.g. web search) itself and
                // feeds results back — the app just streams the final text.
                automaticToolCalling = tools.isNotEmpty(),
                samplerConfig = SamplerConfig(
                    topK = sampler.topK,
                    topP = sampler.topP,
                    temperature = sampler.temperature
                )
            )
            // JOURNEY logging: the exact payload handed to the engine — system
            // prompt, tools, history, sampler — so one logcat filter shows
            // everything the model is given (incl. whether tools are sent).
            val toolNames = tools.joinToString { it.javaClass.simpleName }
            val priorSummaryLabel = priorSummary?.let { "${it.length} chars" } ?: "none"
            Log.i(
                JOURNEY_TAG,
                "JOURNEY: conversation handed to engine — " +
                    "systemPrompt=\"${effectiveSystemInstruction ?: "<none>"}\", " +
                    "tools=${tools.size}[$toolNames], " +
                    "automaticToolCalling=${tools.isNotEmpty()}, " +
                    "history=${trimmed.size}/${history.size} turns, " +
                    "priorSummary=$priorSummaryLabel, " +
                    "sampler(temp=${sampler.temperature}, topK=${sampler.topK}, topP=${sampler.topP}), " +
                    "maxContextTokens=$maxContextTokens"
            )
            trimmed.forEachIndexed { index, turn ->
                val preview = if (turn.text.length > 200) turn.text.take(200) + "…" else turn.text
                Log.i(
                    JOURNEY_TAG,
                    "JOURNEY: history[$index] " +
                        "${if (turn.role == ChatTurn.Role.USER) "USER" else "ASSISTANT"} " +
                        "(${turn.text.length} chars): \"$preview\""
                )
            }
            val conversation = engine.createConversation(config)
            // Count everything the engine actually holds: live history PLUS
            // the system instruction and compaction digest folded into it.
            // Omitting those made the gauge drop further than reality after
            // every compaction (and the next overflow arrived as Status 13).
            // Tool schemas cost roughly ~200 tokens each — counted flat.
            // Photos cost IMAGE_TOKEN_COST each (vision encoder output).
            val initialTokens = trimmed.sumOf {
                SessionBudget.estimateTokens(it.text) +
                    it.imagePaths.size * SessionBudget.IMAGE_TOKEN_COST
            } +
                (effectiveSystemInstruction?.let { SessionBudget.estimateTokens(it) } ?: 0) +
                tools.size * SessionBudget.TOOL_TOKEN_OVERHEAD
            return ConversationSession(conversation, maxContextTokens, initialTokens)
        }

        /** A user turn as a runtime message, with photos as image parts. */
        internal fun ChatTurn.toUserMessage(): Message =
            if (imagePaths.isEmpty()) {
                Message.user(text)
            } else {
                val parts = imagePaths.map { Content.ImageFile(it) } + Content.Text(text)
                Message.user(Contents.of(parts))
            }

        /** Formats older turns for the compaction summarization prompt. */
        internal fun summarizeTranscript(older: List<ChatTurn>): String =
            older.joinToString("\n") {
                val photo = if (it.imagePaths.isNotEmpty()) " [photo attached]" else ""
                (if (it.role == ChatTurn.Role.USER) "User: " else "Assistant: ") + it.text + photo
            }
    }
}
