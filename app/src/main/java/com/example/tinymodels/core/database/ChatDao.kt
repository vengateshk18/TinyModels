package com.example.tinymodels.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tinymodels.core.database.entities.ChatEntity
import com.example.tinymodels.core.database.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // ---- Chats ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChat(chat: ChatEntity)

    @Query("SELECT * FROM chats WHERE chatId = :chatId")
    suspend fun getChat(chatId: String): ChatEntity?

    @Query(
        "UPDATE chats SET title = :title, updatedAt = :updatedAt WHERE chatId = :chatId"
    )
    suspend fun updateChatTitle(chatId: String, title: String, updatedAt: Long)

    @Query("UPDATE chats SET updatedAt = :updatedAt WHERE chatId = :chatId")
    suspend fun touchChat(chatId: String, updatedAt: Long)

    /**
     * Persist a context-compaction digest: [summary] covers all messages
     * created strictly before [cutoffCreatedAt]. Null summary clears the
     * compaction (used when the user edits/regenerates inside the region).
     */
    @Query(
        """
        UPDATE chats SET compactedSummary = :summary,
                         compactedUpToCreatedAt = CASE WHEN :summary IS NULL THEN NULL
                                                       ELSE :cutoffCreatedAt END,
                         compactionCount = CASE WHEN :summary IS NULL THEN 0
                                                ELSE compactionCount + 1 END
        WHERE chatId = :chatId
        """
    )
    suspend fun saveCompaction(chatId: String, summary: String?, cutoffCreatedAt: Long?)

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM chats")
    suspend fun deleteAllChats()

    /**
     * Chats for the history list, each with a preview of its latest message
     * and its message count. Ordered by most recently active.
     */
    @Query(
        """
        SELECT c.chatId AS id, c.title AS title, c.modelId AS modelId,
               c.updatedAt AS updatedAt,
               (SELECT m.content FROM messages m
                  WHERE m.chatId = c.chatId
                  ORDER BY m.createdAt DESC LIMIT 1) AS lastMessagePreview,
               (SELECT COUNT(*) FROM messages m
                  WHERE m.chatId = c.chatId) AS messageCount
        FROM chats c
        WHERE c.isArchived = 0
        ORDER BY c.updatedAt DESC
        """
    )
    fun observeChatSummaries(): Flow<List<ChatSummaryRow>>

    // ---- Messages ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun observeMessages(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    suspend fun getMessages(chatId: String): List<MessageEntity>

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: String)

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun messageCount(chatId: String): Int

    // ---- Usage stats (Home dashboard) ----

    @Query("SELECT COUNT(*) FROM chats")
    suspend fun totalChats(): Int

    @Query("SELECT COUNT(DISTINCT modelId) FROM chats")
    suspend fun modelsTried(): Int

    @Query("SELECT COALESCE(SUM(tokenCount), 0) FROM messages")
    suspend fun totalTokensGenerated(): Long

    /**
     * Token + timing totals for one chat's completed assistant messages —
     * used to compute average generation speed (tokens/sec).
     */
    @Query(
        """
        SELECT COALESCE(SUM(tokenCount), 0) AS totalTokens,
               MIN(createdAt) AS startedAt,
               MAX(completedAt) AS finishedAt
        FROM messages
        WHERE chatId = :chatId
          AND role = 'ASSISTANT'
          AND completedAt IS NOT NULL
        """
    )
    suspend fun sessionTokenStats(chatId: String): SessionTokenStatsRow?
}

/** Projection for [ChatDao.sessionTokenStats]. */
data class SessionTokenStatsRow(
    val totalTokens: Long,
    val startedAt: Long?,
    val finishedAt: Long?
)

/** Projection for [ChatDao.observeChatSummaries]. */
data class ChatSummaryRow(
    val id: String,
    val title: String,
    val modelId: String,
    val lastMessagePreview: String?,
    val updatedAt: Long,
    val messageCount: Int
)
