package com.example.tinymodels.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val title: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isArchived: Boolean = false
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["chatId", "createdAt"])]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val role: String, // USER / ASSISTANT / SYSTEM
    val content: String,
    val tokenCount: Int,
    val createdAt: Long,
    val isComplete: Boolean = true,
    /** Wall-clock time when generation finished (assistant messages only). */
    val completedAt: Long? = null
)
