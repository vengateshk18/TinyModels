package com.example.tinymodels.domain.repository

import com.example.tinymodels.domain.model.Chat
import com.example.tinymodels.domain.model.ChatMessage
import com.example.tinymodels.domain.model.ChatSummary
import kotlinx.coroutines.flow.Flow

interface ChatRepository {

    fun observeChatSummaries(): Flow<List<ChatSummary>>

    fun observeMessages(chatId: String): Flow<List<ChatMessage>>

    suspend fun getMessages(chatId: String): List<ChatMessage>

    suspend fun getChat(chatId: String): Chat?

    /** Creates a chat and returns its id. */
    suspend fun createChat(modelId: String, title: String): Chat

    suspend fun renameChat(chatId: String, title: String)

    /** Insert or update a message; bumps the chat's updatedAt. */
    suspend fun saveMessage(message: ChatMessage)

    /** Delete a single message by id (used by regenerate / edit). */
    suspend fun deleteMessage(messageId: String)

    suspend fun deleteChat(chatId: String)

    suspend fun messageCount(chatId: String): Int
}
