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
    val isArchived: Boolean = false,
    // Per-session inference settings:
    val backend: String = "AUTO",
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.95,
    val maxContextTokens: Int = 2048,
    val systemInstruction: String = "You are a helpful assistant."
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
