package com.example.tinymodels.data.repository

import com.example.tinymodels.core.common.DispatcherProvider
import com.example.tinymodels.core.database.ChatDao
import com.example.tinymodels.core.database.entities.ChatEntity
import com.example.tinymodels.core.database.entities.MessageEntity
import com.example.tinymodels.domain.model.Chat
import com.example.tinymodels.domain.model.ChatMessage
import com.example.tinymodels.domain.model.ChatSummary
import com.example.tinymodels.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatDao: ChatDao,
    private val dispatchers: DispatcherProvider
) : ChatRepository {

    override fun observeChatSummaries(): Flow<List<ChatSummary>> =
        chatDao.observeChatSummaries().map { rows ->
            rows.map { row ->
                ChatSummary(
                    id = row.id,
                    title = row.title,
                    modelId = row.modelId,
                    lastMessagePreview = row.lastMessagePreview,
                    updatedAt = row.updatedAt
                )
            }
        }

    override fun observeMessages(chatId: String): Flow<List<ChatMessage>> =
        chatDao.observeMessages(chatId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getMessages(chatId: String): List<ChatMessage> =
        withContext(dispatchers.io) { chatDao.getMessages(chatId).map { it.toDomain() } }

    override suspend fun getChat(chatId: String): Chat? =
        withContext(dispatchers.io) { chatDao.getChat(chatId)?.toDomain() }

    override suspend fun createChat(modelId: String, title: String): Chat =
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            val entity = ChatEntity(
                chatId = UUID.randomUUID().toString(),
                title = title,
                modelId = modelId,
                createdAt = now,
                updatedAt = now
            )
            chatDao.upsertChat(entity)
            entity.toDomain()
        }

    override suspend fun renameChat(chatId: String, title: String) =
        withContext(dispatchers.io) {
            chatDao.updateChatTitle(chatId, title, System.currentTimeMillis())
        }

    override suspend fun saveMessage(message: ChatMessage) =
        withContext(dispatchers.io) {
            chatDao.upsertMessage(message.toEntity())
            chatDao.touchChat(message.chatId, System.currentTimeMillis())
        }

    override suspend fun deleteChat(chatId: String) =
        withContext(dispatchers.io) {
            chatDao.deleteMessagesForChat(chatId)
            chatDao.deleteChat(chatId)
        }

    override suspend fun messageCount(chatId: String): Int =
        withContext(dispatchers.io) { chatDao.messageCount(chatId) }

    // ---- Mapping ----

    private fun ChatEntity.toDomain() = Chat(
        id = chatId,
        title = title,
        modelId = modelId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isArchived = isArchived
    )

    private fun MessageEntity.toDomain() = ChatMessage(
        id = messageId,
        chatId = chatId,
        role = runCatching { ChatMessage.Role.valueOf(role) }
            .getOrDefault(ChatMessage.Role.USER),
        content = content,
        tokenCount = tokenCount,
        createdAt = createdAt,
        isComplete = isComplete
    )

    private fun ChatMessage.toEntity() = MessageEntity(
        messageId = id,
        chatId = chatId,
        role = role.name,
        content = content,
        tokenCount = tokenCount,
        createdAt = createdAt,
        isComplete = isComplete
    )
}
