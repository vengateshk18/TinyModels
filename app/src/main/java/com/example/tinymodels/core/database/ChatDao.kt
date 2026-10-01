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

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    /**
     * Chats for the history list, each with a preview of its latest message.
     * Ordered by most recently active.
     */
    @Query(
        """
        SELECT c.chatId AS id, c.title AS title, c.modelId AS modelId,
               c.updatedAt AS updatedAt,
               (SELECT m.content FROM messages m
                 WHERE m.chatId = c.chatId
                 ORDER BY m.createdAt DESC LIMIT 1) AS lastMessagePreview
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

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun messageCount(chatId: String): Int
}

/** Projection for [ChatDao.observeChatSummaries]. */
data class ChatSummaryRow(
    val id: String,
    val title: String,
    val modelId: String,
    val lastMessagePreview: String?,
    val updatedAt: Long
)
