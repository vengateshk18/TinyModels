package com.example.tinymodels.data.repository

import com.example.tinymodels.core.common.DispatcherProvider
import com.example.tinymodels.core.database.ChatDao
import com.example.tinymodels.core.database.entities.ChatEntity
import com.example.tinymodels.core.database.entities.MessageEntity
import com.example.tinymodels.domain.model.Chat
import com.example.tinymodels.domain.model.ChatMessage
import com.example.tinymodels.domain.model.ChatSummary
import com.example.tinymodels.domain.model.InferenceSettings
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
                    updatedAt = row.updatedAt,
                    messageCount = row.messageCount
                )
            }
        }

    override fun observeMessages(chatId: String): Flow<List<ChatMessage>> =
        chatDao.observeMessages(chatId).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getMessages(chatId: String): List<ChatMessage> =
        withContext(dispatchers.io) { chatDao.getMessages(chatId).map { it.toDomain() } }

    override suspend fun getChat(chatId: String): Chat? =
        withContext(dispatchers.io) { chatDao.getChat(chatId)?.toDomain() }

    override suspend fun createChat(modelId: String, title: String, defaultBackend: BackendPreference): Chat =
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            val entity = ChatEntity(
                chatId = UUID.randomUUID().toString(),
                title = title,
                modelId = modelId,
                createdAt = now,
                updatedAt = now,
                backend = defaultBackend.name
            )
            chatDao.upsertChat(entity)
            entity.toDomain()
        }

    override suspend fun renameChat(chatId: String, title: String) =
        withContext(dispatchers.io) {
            chatDao.updateChatTitle(chatId, title, System.currentTimeMillis())
        }

    override suspend fun saveCompaction(chatId: String, summary: String?, cutoffCreatedAt: Long?) =
        withContext(dispatchers.io) {
            chatDao.saveCompaction(chatId, summary, cutoffCreatedAt)
        }

    override suspend fun updateInferenceSettings(chatId: String, settings: InferenceSettings) =
        withContext(dispatchers.io) {
            val existing = chatDao.getChat(chatId) ?: return@withContext
            chatDao.upsertChat(existing.copy(
                backend = settings.backend.name,
                temperature = settings.temperature,
                topK = settings.topK,
                topP = settings.topP,
                systemInstruction = settings.systemInstruction
            ))
        }

    override suspend fun saveMessage(message: ChatMessage) =
        withContext(dispatchers.io) {
            chatDao.upsertMessage(message.toEntity())
            chatDao.touchChat(message.chatId, System.currentTimeMillis())
        }

    override suspend fun deleteMessage(messageId: String) =
        withContext(dispatchers.io) {
            chatDao.deleteMessage(messageId)
        }

    override suspend fun deleteChat(chatId: String) =
        withContext(dispatchers.io) {
            chatDao.deleteMessagesForChat(chatId)
            chatDao.deleteChat(chatId)
        }

    override suspend fun clearAllChats() =
        withContext(dispatchers.io) {
            chatDao.deleteAllMessages()
            chatDao.deleteAllChats()
        }

    override suspend fun messageCount(chatId: String): Int =
        withContext(dispatchers.io) { chatDao.messageCount(chatId) }

    // ---- Usage stats (Home dashboard) ----

    override suspend fun totalChats(): Int =
        withContext(dispatchers.io) { chatDao.totalChats() }

    override suspend fun modelsTried(): Int =
        withContext(dispatchers.io) { chatDao.modelsTried() }

    override suspend fun totalTokensGenerated(): Long =
        withContext(dispatchers.io) { chatDao.totalTokensGenerated() }

    override suspend fun getLastSessionTokensPerSecond(): Float? =
        withContext(dispatchers.io) {
            // Most recently updated chat.
            val latest = chatDao.observeChatSummaries().first().firstOrNull()
                ?: return@withContext null
            val stats = chatDao.sessionTokenStats(latest.id) ?: return@withContext null
            val durationMs = (stats.finishedAt ?: return@withContext null) -
                (stats.startedAt ?: return@withContext null)
            if (stats.totalTokens <= 0 || durationMs <= 0) return@withContext null
            stats.totalTokens.toFloat() / (durationMs / 1000f)
        }

    // ---- Mapping ----

    private fun ChatEntity.toDomain() = Chat(
        id = chatId,
        title = title,
        modelId = modelId,
        createdAt = createdAt,
        updatedAt = updatedAt,
                isArchived = isArchived,
                compactedSummary = compactedSummary,
                compactedUpToCreatedAt = compactedUpToCreatedAt,
                compactionCount = compactionCount,
                inferenceSettings = InferenceSettings(
            backend = runCatching { BackendPreference.valueOf(backend) }.getOrDefault(BackendPreference.AUTO),
            temperature = temperature,
            topK = topK,
            topP = topP,
            systemInstruction = systemInstruction
        )
    )

        private fun MessageEntity.toDomain() = ChatMessage(
        id = messageId,
        chatId = chatId,
        role = runCatching { ChatMessage.Role.valueOf(role) }
            .getOrDefault(ChatMessage.Role.USER),
        content = content,
        tokenCount = tokenCount,
        createdAt = createdAt,
        isComplete = isComplete,
        completedAt = completedAt
    )

    private fun ChatMessage.toEntity() = MessageEntity(
        messageId = id,
        chatId = chatId,
        role = role.name,
        content = content,
        tokenCount = tokenCount,
        createdAt = createdAt,
        isComplete = isComplete,
        completedAt = completedAt
    )
}
