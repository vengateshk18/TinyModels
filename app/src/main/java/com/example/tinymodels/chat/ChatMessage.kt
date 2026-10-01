package com.example.tinymodels.chat

/** Role of a single message in a chat conversation. */
enum class ChatRole {
    USER,
    ASSISTANT
}

/**
 * A single chat message shown in the UI.
 *
 * @param text is mutable-in-place via copy() as streamed tokens arrive for assistant replies.
 * @param isStreaming true while the assistant is still generating this message.
 */
data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val isStreaming: Boolean = false
)
