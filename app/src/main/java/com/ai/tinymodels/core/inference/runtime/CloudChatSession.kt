package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.core.network.cloud.CloudChatClient
import com.ai.tinymodels.core.network.cloud.CloudChatMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * A [ChatSession] backed by a hosted model instead of a local engine.
 *
 * The chat layer already consumes cumulative text, so this session keeps the
 * running reply locally and emits it after every provider delta. History is
 * replayed as OpenAI-style messages on each turn — the provider is stateless.
 */
class CloudChatSession(
    private val client: CloudChatClient,
    private val model: String,
    private val request: SessionRequest,
    contextTokens: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : BaseChatSession(contextTokens) {

    companion object {
        /** Conservative output cap accepted by every partner provider. */
        const val MAX_OUTPUT_TOKENS = 2048
    }

    override fun send(text: String, imagePaths: List<String>): Flow<String> = flow {
        countPrompt(text)
        val messages = buildList {
            request.systemInstruction?.takeIf { it.isNotBlank() }?.let {
                add(CloudChatMessage("system", it))
            }
            request.priorSummary?.takeIf { it.isNotBlank() }?.let {
                add(CloudChatMessage("system", "Summary of the earlier conversation:\n$it"))
            }
            request.history.forEach { turn ->
                add(
                    CloudChatMessage(
                        role = if (turn.role == ChatTurn.Role.USER) "user" else "assistant",
                        content = turn.text
                    )
                )
            }
            add(CloudChatMessage("user", text))
        }

        var accumulated = ""
        client.streamChat(
            model = model,
            messages = messages,
            temperature = request.sampler.temperature,
            topP = request.sampler.topP,
            // Providers cap output tokens well below the context window; a
            // 32k request is rejected by most of them, so stay conservative.
            maxTokens = minOf(MAX_OUTPUT_TOKENS, maxContextTokens)
        ).collect { delta ->
            accumulated += delta
            countResponseDelta(delta.length)
            emit(accumulated)
        }
    }.flowOn(dispatcher)

    /** Nothing native to release. */
    override fun close() = Unit

    /**
     * Compaction falls back to the plain sliding window for cloud chats —
     * a second paid call just to summarize is not worth it in this MVP.
     */
    override suspend fun summarizeOlderTurns(older: List<ChatTurn>): String? = null
}
