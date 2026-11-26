package com.ai.tinymodels.core.network.cloud

import kotlinx.coroutines.flow.Flow

/** One model exposed by the cloud router. */
data class CloudModel(
    val id: String,
    val ownedBy: String? = null
)

/** One chat turn in OpenAI wire shape. */
data class CloudChatMessage(
    val role: String,
    val content: String
)

/**
 * The small surface the chat layer needs from a cloud provider. An interface
 * so [com.ai.tinymodels.core.inference.runtime.CloudChatSession] is testable
 * with a fake stream.
 */
interface CloudChatClient {

    /** Streams text deltas for one completion. */
    fun streamChat(
        model: String,
        messages: List<CloudChatMessage>,
        temperature: Double,
        topP: Double,
        maxTokens: Int
    ): Flow<String>

    /** Lists the chat models the router can serve right now. */
    suspend fun listModels(): List<CloudModel>
}

/** A provider-side failure with a user-readable message. */
class CloudApiException(
    val statusCode: Int,
    override val message: String
) : Exception(message)
