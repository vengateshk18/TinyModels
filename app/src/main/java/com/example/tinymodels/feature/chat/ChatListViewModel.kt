package com.example.tinymodels.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.domain.model.ChatSummary
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.repository.ChatRepository
import com.example.tinymodels.domain.repository.ModelRepository
import com.example.tinymodels.domain.repository.SettingsRepository
import com.example.tinymodels.feature.chat.model.ChatListItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
     * Navigate to a new chat room without creating a DB row.
     *
     * The actual [chatRepository.createChat] call is deferred until the user
     * sends their first message inside [ChatViewModel]. This prevents empty
     * "ghost" sessions from appearing in the history list every time the user
     * taps the FAB without typing anything.
     *
     * We still validate that at least one model is downloaded before navigating,
     * so the room screen can immediately prompt the user to select a model.
     */
    fun createNewChat(onCreated: (String) -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val models = modelRepository.observeDownloadedModels().first()
            if (models.isEmpty()) {
                onError("Download a model first to start chatting.")
                return@launch
            }
            // Navigate with a sentinel id that tells ChatViewModel to start fresh
            // without an existing DB row.
            onCreated(NEW_CHAT_SENTINEL)
        }
    }

    companion object {
        /** Sentinel used by navigation to indicate "open a blank new chat room". */
        const val NEW_CHAT_SENTINEL = "new"
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch { chatRepository.deleteChat(chatId) }
    }

    private fun ChatSummary.toListItem() = ChatListItem(
        id = id,
        title = title,
        preview = lastMessagePreview,
        updatedAt = updatedAt,
        modelId = modelId,
        messageCount = messageCount
    )
}
