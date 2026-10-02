package com.example.tinymodels.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.domain.model.ChatSummary
import com.example.tinymodels.domain.repository.ChatRepository
import com.example.tinymodels.domain.repository.ModelRepository
import com.example.tinymodels.domain.repository.SettingsRepository
import com.example.tinymodels.feature.chat.model.ChatListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lightweight ViewModel for the Chat tab. Observes the chat-session list
 * and can create new chats. The full per-session chat logic lives in
 * [ChatViewModel] (scoped to a chatId via SavedStateHandle in N3).
 */
@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /** Chat sessions for the list, most recently active first. */
    val chats: StateFlow<List<ChatListItem>> =
        chatRepository.observeChatSummaries()
            .map { summaries -> summaries.map { it.toListItem() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Downloaded models — needed to pick a default model for new chats. */
    val downloadedModels = modelRepository.observeDownloadedModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Create a new chat session. Returns the chat id via [onCreated].
     * Uses the first downloaded model as the default, or fails if none.
     */
    fun createNewChat(onCreated: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val models = downloadedModels.value
            if (models.isEmpty()) {
                onError("Download a model first")
                return@launch
            }
            val modelId = models.first().modelId
            val chat = chatRepository.createChat(modelId, title = "New chat")
            onCreated(chat.id)
        }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch { chatRepository.deleteChat(chatId) }
    }

    private fun ChatSummary.toListItem() = ChatListItem(
        id = id,
        title = title,
        preview = lastMessagePreview,
        updatedAt = updatedAt
    )
}
