package com.example.tinymodels.domain.model

/** A persisted chat conversation (metadata for the history list). */
data class Chat(
    val id: String,
    val title: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isArchived: Boolean = false
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
    val updatedAt: Long
)
