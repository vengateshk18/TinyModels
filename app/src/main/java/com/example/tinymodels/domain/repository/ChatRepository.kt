package com.example.tinymodels.domain.repository

import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.Chat
import com.example.tinymodels.domain.model.ChatMessage
import com.example.tinymodels.domain.model.ChatSummary
import com.example.tinymodels.domain.model.InferenceSettings
import kotlinx.coroutines.flow.Flow

interface ChatRepository {

    fun observeChatSummaries(): Flow<List<ChatSummary>>

    fun observeMessages(chatId: String): Flow<List<ChatMessage>>

    suspend fun getMessages(chatId: String): List<ChatMessage>

    suspend fun getChat(chatId: String): Chat?

    /** Creates a chat and returns its id. */
    suspend fun createChat(modelId: String, title: String, defaultBackend: BackendPreference = BackendPreference.AUTO): Chat

    suspend fun renameChat(chatId: String, title: String)

    /** Update the per-session inference settings for a chat. */
    suspend fun updateInferenceSettings(chatId: String, settings: InferenceSettings)

    /**
     * Persist a context-compaction digest for a chat: [summary] covers all
     * messages created strictly before [cutoffCreatedAt]. Pass a null summary
     * to clear the compaction (e.g. when the user edits a summarized turn).
     */
    suspend fun saveCompaction(chatId: String, summary: String?, cutoffCreatedAt: Long?)

    /** Insert or update a message; bumps the chat's updatedAt. */
    suspend fun saveMessage(message: ChatMessage)

    /** Delete a single message by id (used by regenerate / edit). */
    suspend fun deleteMessage(messageId: String)

    suspend fun deleteChat(chatId: String)

    /** Delete ALL chats and their messages. Used by Settings → Clear chat history. */
    suspend fun clearAllChats()

    suspend fun messageCount(chatId: String): Int

    // ---- Usage stats (Home dashboard) ----

    /** Total number of chats ever created (excluding archived). */
    suspend fun totalChats(): Int

    /** Distinct models the user has actually chatted with. */
    suspend fun modelsTried(): Int

    /** Sum of tokenCount across all messages. */
    suspend fun totalTokensGenerated(): Long

    /**
     * Average generation speed (tokens/sec) of the most recent chat, computed
     * from its completed assistant messages. Null when not computable
     * (no messages or zero duration).
     */
    suspend fun getLastSessionTokensPerSecond(): Float?
}
