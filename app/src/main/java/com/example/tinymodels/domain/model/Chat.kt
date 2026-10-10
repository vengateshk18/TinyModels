package com.example.tinymodels.domain.model

/** A persisted chat conversation (metadata for the history list). */
data class Chat(
    val id: String,
    val title: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isArchived: Boolean = false,
    val inferenceSettings: InferenceSettings = InferenceSettings(),
    /** Digest of older turns summarized away to fit the model's context window. */
    val compactedSummary: String? = null,
    /** Messages strictly BEFORE this createdAt are covered by [compactedSummary]. */
    val compactedUpToCreatedAt: Long? = null,
    /** How many times compaction has run for this chat (observability metric). */
    val compactionCount: Int = 0
)

/** A single message within a chat. */
data class ChatMessage(
    val id: String,
    val chatId: String,
    val role: Role,
    val content: String,
    val tokenCount: Int,
    val createdAt: Long,
    val isComplete: Boolean = true,
    /** Wall-clock time when generation finished (assistant messages only). */
    val completedAt: Long? = null
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/** A chat with a preview of its last message, for the history list. */
data class ChatSummary(
    val id: String,
    val title: String,
    val modelId: String,
    val lastMessagePreview: String?,
    val updatedAt: Long,
    /** Total number of messages in this chat. */
    val messageCount: Int = 0
)
