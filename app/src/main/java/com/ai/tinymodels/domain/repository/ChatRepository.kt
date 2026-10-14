package com.ai.tinymodels.domain.repository

import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.Chat
import com.ai.tinymodels.domain.model.ChatMessage
import com.ai.tinymodels.domain.model.ChatSummary
import com.ai.tinymodels.domain.model.InferenceSettings
import kotlinx.coroutines.flow.Flow

interface ChatRepository {

    fun observeChatSummaries(): Flow<List<ChatSummary>>

    fun observeMessages(chatId: String): Flow<List<ChatMessage>>

    suspend fun getMessages(chatId: String): List<ChatMessage>

    suspend fun getChat(chatId: String): Chat?

    /**
     * Creates a chat and returns its id. [webSearchEnabled] carries the
     * pre-first-message UI toggle into the row — the per-chat flag the
     * web_search tool registration is gated on.
     */
    suspend fun createChat(
        modelId: String,
        title: String,
        defaultBackend: BackendPreference = BackendPreference.AUTO,
        webSearchEnabled: Boolean = false
    ): Chat

    suspend fun renameChat(chatId: String, title: String)

    /** Update the per-session inference settings for a chat. */
    suspend fun updateInferenceSettings(chatId: String, settings: InferenceSettings)

    /**
     * Persist a context-compaction digest for a chat: [summary] covers all
     * messages created at or before [cutoffCreatedAt]. Pass a null summary
     * to clear the compaction (e.g. when the user edits a summarized turn).
     */
    suspend fun saveCompaction(chatId: String, summary: String?, cutoffCreatedAt: Long?)

    /** Insert or update a message; bumps the chat's updatedAt. */
    suspend fun saveMessage(message: ChatMessage)

    /** Per-chat opt-in for the model's web_search tool. */
    suspend fun updateWebSearchEnabled(chatId: String, enabled: Boolean)

    /** Delete a single message by id (used by regenerate / edit). */
    suspend fun deleteMessage(messageId: String)

    suspend fun deleteChat(chatId: String)

    /** Delete ALL chats and their messages. Used by Settings → Clear chat history. */
    suspend fun clearAllChats()

    suspend fun messageCount(chatId: String): Int

    /** Total messages across all chats. */
    suspend fun totalMessages(): Int

    /** Chats created since [sinceMs] (epoch millis). */
    suspend fun chatsSince(sinceMs: Long): Int

    /** Most-chatted model id, or null when no chats exist. */
    suspend fun favoriteModelId(): String?

    /** Total compactions run across all chats. */
    suspend fun totalCompactions(): Int

    /** Chats with web search enabled. */
    suspend fun webSearchChats(): Int

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
