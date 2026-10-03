package com.example.tinymodels.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.core.common.InferenceError
import com.example.tinymodels.core.inference.ChatTurn
import com.example.tinymodels.core.inference.ConversationSession
import com.example.tinymodels.core.inference.InferenceException
import com.example.tinymodels.core.inference.ModelManager
import com.example.tinymodels.domain.model.ChatMessage
import com.example.tinymodels.domain.model.InferenceSettings
import com.example.tinymodels.domain.model.DownloadedModel
import com.example.tinymodels.domain.repository.ChatRepository
import com.example.tinymodels.domain.repository.ModelRepository
import com.example.tinymodels.domain.repository.SettingsRepository
import com.example.tinymodels.feature.chat.model.ChatError
import com.example.tinymodels.feature.chat.model.ChatEvent
import com.example.tinymodels.feature.chat.model.ChatListItem
import com.example.tinymodels.feature.chat.model.ChatUiState
import com.example.tinymodels.feature.chat.model.GenerationState
import com.example.tinymodels.feature.chat.model.ModelChipState
import com.example.tinymodels.feature.chat.model.ModelLoadProgress
import com.example.tinymodels.feature.chat.model.UiChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * MVI ViewModel for the chat feature. Exposes a single immutable [ChatUiState].
 *
 * Memory behavior is delegated to the process-wide [ModelManager]; this ViewModel
 * only orchestrates loading + per-chat [ConversationSession]s. The engine survives
 * configuration changes because it is NOT owned here.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val modelManager: ModelManager,
    private val chatRepository: ChatRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Downloaded models for the picker sheet. */
    val downloadedModels: StateFlow<List<DownloadedModel>> =
        modelRepository.observeDownloadedModels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var session: ConversationSession? = null
    private var generationJob: Job? = null
    private var activeModel: DownloadedModel? = null

    /** The chatId for this screen, passed via nav arguments (SavedStateHandle). */
    val chatId: String? get() = savedStateHandle["chatId"]

    init {
        observeDownloadedModels()
        observeEngineState()
        // Auto-open the chat identified by the nav arg.
        chatId?.let { viewModelScope.launch { openChatInternal(it) } }
    }

    fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.SelectModel -> selectModel(event.modelId)
            is ChatEvent.SendMessage -> sendMessage(event.text)
            ChatEvent.CancelGeneration -> cancelGeneration()
            ChatEvent.Regenerate -> regenerate()
            is ChatEvent.EditMessage -> editMessage(event.messageId, event.newText)
            ChatEvent.NewChat -> newChat()
            is ChatEvent.OpenChat -> openChat(event.chatId)
            is ChatEvent.DeleteChat -> deleteChat(event.chatId)
            ChatEvent.DismissError -> _uiState.update { it.copy(error = null) }
            is ChatEvent.UpdateInferenceSettings -> updateInferenceSettings(event.settings)
            ChatEvent.OpenInferenceSettings -> _uiState.update { it.copy(showInferenceSettings = true) }
            ChatEvent.CloseInferenceSettings -> _uiState.update { it.copy(showInferenceSettings = false) }
        }
    }

    // ---- Observation ----

    private fun observeChats() {
        viewModelScope.launch {
            chatRepository.observeChatSummaries().collect { summaries ->
                _uiState.update { state ->
                    state.copy(chats = summaries.map {
                        ChatListItem(it.id, it.title, it.lastMessagePreview, it.updatedAt)
                    })
                }
            }
        }
    }

    private fun observeDownloadedModels() {
        viewModelScope.launch {
            modelRepository.observeDownloadedModels().collect { models ->
                val hadNone = _uiState.value.hasDownloadedModels.not()
                _uiState.update { it.copy(hasDownloadedModels = models.isNotEmpty()) }
                // Auto-select the first model when none is active yet.
                if (activeModel == null && models.isNotEmpty() && hadNone.not()) {
                    // Only auto-select if user hasn't picked and no engine loaded.
                    if (modelManager.loadedModelId == null) {
                        selectModel(models.first().modelId)
                    }
                }
            }
        }
    }

    private fun observeEngineState() {
        viewModelScope.launch {
            modelManager.engineState.collect { engineState ->
                val chip = when (engineState) {
                    is ModelManager.EngineState.Idle -> ModelChipState.NotSelected
                    is ModelManager.EngineState.Loading -> ModelChipState.Loading
                    is ModelManager.EngineState.Ready -> ModelChipState.Ready(
                        modelId = engineState.model.modelId,
                        backendLabel = engineState.model.backendUsed.name
                    )
                    is ModelManager.EngineState.Error ->
                        ModelChipState.Error(friendlyError(engineState.error))
                }
                _uiState.update { it.copy(model = chip) }
            }
        }
    }

    // ---- Model selection / loading ----

    private fun selectModel(modelId: String) {
        if (modelManager.loadedModelId == modelId && session != null) return
        viewModelScope.launch {
            val downloaded = modelRepository.getDownloadedModel(modelId)
            if (downloaded == null) {
                _uiState.update { it.copy(error = ChatError("Model $modelId is not downloaded")) }
                return@launch
            }
            val modelFile = resolveModelFile(downloaded)
            if (modelFile == null) {
                _uiState.update {
                    it.copy(error = ChatError("Model file missing on device", "Re-download"))
                }
                return@launch
            }
            val chatId = _uiState.value.activeChatId
            val chat = chatId?.let { chatRepository.getChat(it) }
            val inference = chat?.inferenceSettings ?: InferenceSettings()
            
            // Track loading progress
            _uiState.update { 
                it.copy(
                    modelLoadProgress = ModelLoadProgress(
                        modelId = modelId,
                        progress = 0f,
                        stage = ModelLoadProgress.LoadStage.INITIALIZING
                    )
                ) 
            }
            
            val result = modelManager.loadModel(
                modelId = modelId,
                modelFile = modelFile,
                backend = inference.backend,
                maxNumTokens = inference.maxContextTokens,
                onProgress = { progress, stage ->
                    _uiState.update { currentState ->
                        val uiStage = when (stage) {
                            ModelManager.ModelLoadStage.INITIALIZING -> ModelLoadProgress.LoadStage.INITIALIZING
                            ModelManager.ModelLoadStage.LOADING_WEIGHTS -> ModelLoadProgress.LoadStage.LOADING_WEIGHTS
                            ModelManager.ModelLoadStage.READY -> ModelLoadProgress.LoadStage.READY
                        }
                        currentState.copy(
                            modelLoadProgress = currentState.modelLoadProgress?.copy(
                                progress = progress,
                                stage = uiStage
                            )
                        )
                    }
                }
            )
            when (result) {
                is com.example.tinymodels.core.common.AppResult.Success -> {
                    activeModel = downloaded
                    // Clear progress on success
                    _uiState.update { it.copy(modelLoadProgress = null) }
                    // (Re)build a conversation bound to the active chat, if any.
                    _uiState.value.activeChatId?.let { rebuildSession(it) }
                }
                is com.example.tinymodels.core.common.AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            error = ChatError(result.error.message ?: "Failed to load model"),
                            modelLoadProgress = null
                        )
                    }
                }
            }
        }
    }

    private suspend fun resolveModelFile(model: DownloadedModel): File? {
        val directory = File(model.localPath)
        val file = modelRepository.getDownloadedFileForModel(model.modelId) ?: return null
        val resolved = File(directory, file.fileName.substringAfterLast("/"))
        return if (resolved.exists()) resolved else null
    }

    // ---- Chat lifecycle ----

    private fun newChat() {
        // Do NOT write to the DB here. Just reset the active chat state so the
        // user sees a blank canvas. The actual DB row is created in sendMessage()
        // when the user actually sends their first message.
        session?.close()
        session = null
        _uiState.update {
            it.copy(
                activeChatId = null,
                messages = emptyList(),
                streamingMessageId = null,
                generation = GenerationState.IDLE,
                error = null
            )
        }
    }

    private fun openChat(chatId: String) {
        viewModelScope.launch { openChatInternal(chatId) }
    }

    private suspend fun openChatInternal(chatId: String) {
        val chat = chatRepository.getChat(chatId) ?: return
        // Ensure the chat's model is the one loaded; if not, load it.
        if (modelManager.loadedModelId != chat.modelId) {
            selectModel(chat.modelId)
            // selectModel triggers rebuildSession via activeChatId once ready; set id now.
            _uiState.update { it.copy(activeChatId = chatId) }
        }
        _uiState.update { it.copy(activeChatId = chatId, inferenceSettings = chat.inferenceSettings) }
        rebuildSession(chatId)
        observeMessages(chatId)
    }

    private fun deleteChat(chatId: String) {
        viewModelScope.launch {
            chatRepository.deleteChat(chatId)
            if (_uiState.value.activeChatId == chatId) {
                session?.close()
                session = null
                _uiState.update { it.copy(activeChatId = null, messages = emptyList()) }
            }
        }
    }

    /** Build a fresh [ConversationSession] restoring prior turns for [chatId]. */
    private suspend fun rebuildSession(chatId: String) {
        session?.close()
        session = null
        val history = chatRepository.getMessages(chatId)
            .filter { it.isComplete }
            .map {
                ChatTurn(
                    role = if (it.role == ChatMessage.Role.USER) ChatTurn.Role.USER else ChatTurn.Role.ASSISTANT,
                    text = it.content
                )
            }
        val chat = chatRepository.getChat(chatId)
        val inference = chat?.inferenceSettings ?: InferenceSettings()
        try {
            modelManager.withEngine { engine, config ->
                session = ConversationSession.create(
                    engine = engine,
                    systemInstruction = inference.systemInstruction,
                    history = history,
                    sampler = com.example.tinymodels.domain.model.SamplerSettings(
                        temperature = inference.temperature,
                        topK = inference.topK,
                        topP = inference.topP
                    ),
                    maxContextTokens = inference.maxContextTokens
                )
            }
            updateContextUsage()
        } catch (e: InferenceException) {
            // Engine not loaded yet; session will be built when the model finishes loading.
        }
    }

    private fun observeMessages(chatId: String) {
        viewModelScope.launch {
            chatRepository.observeMessages(chatId).collect { messages ->
                _uiState.update { state ->
                    if (state.activeChatId != chatId) return@update state
                    val roomUi = messages.map { it.toUi() }
                    // Merge: use Room data, but preserve the in-flight streaming
                    // assistant message (with its live partial text) when active.
                    val streamingId = state.streamingMessageId
                    val streamingMsg = state.messages.firstOrNull { it.id == streamingId }
                    if (streamingId != null && streamingMsg != null) {
                        // Drop any stale Room version of the streaming placeholder
                        // (it won't exist until persisted) and append the live one.
                        val without = roomUi.filterNot { it.id == streamingId }
                        state.copy(messages = without + streamingMsg)
                    } else {
                        state.copy(messages = roomUi)
                    }
                }
            }
        }
    }

    // ---- Sending / generation ----

    private fun sendMessage(rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return
        val state = _uiState.value
        if (state.generation == GenerationState.GENERATING) return
        if (state.model !is ModelChipState.Ready) {
            _uiState.update { it.copy(error = ChatError("Model is still loading")) }
            return
        }
        var chatId = state.activeChatId
        viewModelScope.launch {
            if (chatId == null) {
                val modelId = activeModel?.modelId ?: modelManager.loadedModelId ?: return@launch
                chatId = chatRepository.createChat(modelId, title = text.take(40)).id
                _uiState.update { it.copy(activeChatId = chatId) }
                rebuildSession(chatId!!)
            }
            val activeChatId = chatId!!

            // Persist the user message.
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                chatId = activeChatId,
                role = ChatMessage.Role.USER,
                content = text,
                tokenCount = ConversationSession.estimateTokens(text),
                createdAt = System.currentTimeMillis(),
                isComplete = true
            )
            chatRepository.saveMessage(userMessage)

            // Optimistic UI: append user bubble + a streaming assistant placeholder.
            val assistantId = UUID.randomUUID().toString()
            _uiState.update { s ->
                s.copy(
                    generation = GenerationState.GENERATING,
                    streamingMessageId = assistantId,
                    messages = s.messages + userMessage.toUi() + UiChatMessage(
                        id = assistantId, isUser = false, text = "", isStreaming = true,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            // Title the chat from the first message.
            if (chatRepository.messageCount(activeChatId) <= 1) {
                chatRepository.renameChat(activeChatId, text.take(40))
            }

            runGeneration(activeChatId, assistantId, text)
        }
    }

    private fun runGeneration(chatId: String, assistantId: String, prompt: String) {
        val activeSession = session
        if (activeSession == null) {
            _uiState.update {
                it.copy(generation = GenerationState.IDLE, error = ChatError("No active conversation"))
            }
            return
        }
        generationJob = viewModelScope.launch {
            val buffer = StringBuilder()
            activeSession.send(prompt)
                .catch { throwable ->
                    // Re-throw cancellation so the cancel handler (cancelGeneration)
                    // owns the cleanup path instead of surfacing a spurious error.
                    if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                    // Persist partial, then surface the error.
                    persistAssistant(chatId, assistantId, buffer.toString(), isComplete = false)
                    _uiState.update {
                        it.copy(
                            generation = GenerationState.IDLE,
                            streamingMessageId = null,
                            error = ChatError(throwable.message ?: "Generation failed")
                        )
                    }
                }
                .collect { cumulative ->
                    // ConversationSession.send() accumulates delta chunks internally
                    // and emits cumulative text. Replace the buffer + UI with each emission.
                    buffer.setLength(0)
                    buffer.append(cumulative)
                    _uiState.update { s ->
                        s.copy(messages = s.messages.map { m ->
                            if (m.id == assistantId) m.copy(text = cumulative, isStreaming = true) else m
                        })
                    }
                }
            // Completed normally (catch already handled the failure path).
            if (_uiState.value.generation == GenerationState.GENERATING) {
                val finishedAt = System.currentTimeMillis()
                persistAssistant(chatId, assistantId, buffer.toString(), isComplete = true, completedAt = finishedAt)
                _uiState.update { s ->
                    s.copy(
                        generation = GenerationState.IDLE,
                        streamingMessageId = null,
                        messages = s.messages.map { m ->
                            if (m.id == assistantId) m.copy(isStreaming = false, completedAt = finishedAt) else m
                        }
                    )
                }
            }
            updateContextUsage()
        }
    }

    private fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        // Mark the in-flight assistant message as stopped (keep partial text).
        _uiState.update { s ->
            val updated = s.messages.map { m -> if (m.isStreaming) m.copy(isStreaming = false) else m }
            s.copy(generation = GenerationState.IDLE, streamingMessageId = null, messages = updated)
        }
        // Persist whatever partial assistant text remains for this chat.
        viewModelScope.launch {
            val chatId = _uiState.value.activeChatId ?: return@launch
            val streaming = _uiState.value.messages.lastOrNull { !it.isUser } ?: return@launch
            if (streaming.text.isNotBlank()) {
                persistAssistant(chatId, streaming.id, streaming.text, isComplete = false)
            }
        }
    }

    /**
     * Regenerate the last assistant reply: delete the last assistant message,
     * find the preceding user prompt, rebuild the session without that turn,
     * and re-run generation. If the session can't trim history (LiteRT-LM has
     * no "undo" API), we simply re-send the last user prompt as a fresh turn.
     */
    private fun regenerate() {
        val state = _uiState.value
        if (state.generation == GenerationState.GENERATING) return
        val chatId = state.activeChatId ?: return
        val messages = state.messages
        if (messages.isEmpty()) return

        // Find the last assistant message and its preceding user prompt.
        val lastAssistant = messages.lastOrNull { !it.isUser } ?: return
        val lastUser = messages.lastOrNull { it.isUser } ?: return
        val activeSession = session
        if (state.model !is ModelChipState.Ready || activeSession == null) {
            _uiState.update { it.copy(error = ChatError("Model is not ready")) }
            return
        }

        viewModelScope.launch {
            // Delete the old assistant message from Room.
            chatRepository.deleteMessage(lastAssistant.id)
            // Remove it from the UI immediately.
            _uiState.update { s ->
                s.copy(messages = s.messages.filterNot { it.id == lastAssistant.id })
            }

            // Create a new streaming placeholder.
            val newAssistantId = java.util.UUID.randomUUID().toString()
            _uiState.update { s ->
                s.copy(
                    generation = GenerationState.GENERATING,
                    streamingMessageId = newAssistantId,
                    messages = s.messages + UiChatMessage(
                        id = newAssistantId, isUser = false, text = "", isStreaming = true,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            runGeneration(chatId, newAssistantId, lastUser.text)
        }
    }

    /**
     * Edit a previously-sent user message: update its content in Room,
     * delete all messages after it, rebuild the session, and re-generate.
     */
    private fun editMessage(messageId: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        val state = _uiState.value
        if (state.generation == GenerationState.GENERATING) return
        val chatId = state.activeChatId ?: return
        val activeSession = session
        if (state.model !is ModelChipState.Ready || activeSession == null) {
            _uiState.update { it.copy(error = ChatError("Model is not ready")) }
            return
        }

        viewModelScope.launch {
            val allMessages = chatRepository.getMessages(chatId)
            val targetIndex = allMessages.indexOfFirst { it.id == messageId }
            if (targetIndex < 0) return@launch

            // Update the user message content.
            val updated = allMessages[targetIndex].copy(content = text)
            chatRepository.saveMessage(updated)

            // Delete all messages after the edited one (assistant replies etc).
            allMessages.drop(targetIndex + 1).forEach { msg ->
                chatRepository.deleteMessage(msg.id)
            }

            // Update UI: keep messages up to and including the edited one.
            _uiState.update { s ->
                val kept = s.messages.takeWhile { m ->
                    val idx = allMessages.indexOfFirst { it.id == m.id }
                    idx <= targetIndex
                }.map { m -> if (m.id == messageId) m.copy(text = text) else m }
                s.copy(messages = kept)
            }

            // Rebuild the session so the edited prompt is the latest turn.
            rebuildSession(chatId)

            // Start a fresh generation.
            val newAssistantId = java.util.UUID.randomUUID().toString()
            _uiState.update { s ->
                s.copy(
                    generation = GenerationState.GENERATING,
                    streamingMessageId = newAssistantId,
                    messages = s.messages + UiChatMessage(
                        id = newAssistantId, isUser = false, text = "", isStreaming = true,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            runGeneration(chatId, newAssistantId, text)
        }
    }

    private suspend fun persistAssistant(
        chatId: String,
        id: String,
        text: String,
        isComplete: Boolean,
        completedAt: Long? = if (isComplete) System.currentTimeMillis() else null
    ) {
        if (text.isBlank()) return
        chatRepository.saveMessage(
            ChatMessage(
                id = id,
                chatId = chatId,
                role = ChatMessage.Role.ASSISTANT,
                content = text,
                tokenCount = ConversationSession.estimateTokens(text),
                createdAt = System.currentTimeMillis(),
                isComplete = isComplete,
                completedAt = completedAt
            )
        )
    }

    private fun updateContextUsage() {
        val activeSession = session ?: return
        _uiState.update {
            it.copy(
                contextUsage = com.example.tinymodels.feature.chat.model.ContextUsage(
                    usedTokens = activeSession.estimatedTokensInContext,
                    maxTokens = it.contextUsage.maxTokens,
                    nearFull = activeSession.isContextNearFull
                )
            )
        }
    }

    // ---- Mapping / helpers ----

    private fun ChatMessage.toUi() = UiChatMessage(
        id = id,
        isUser = role == ChatMessage.Role.USER,
        text = content,
        isStreaming = !isComplete,
        timestamp = createdAt,
        completedAt = completedAt
    )

    private fun friendlyError(error: InferenceError): String = when (error) {
        is InferenceError.OutOfMemory -> "Not enough free memory. Close other apps and retry."
        is InferenceError.ModelFileMissing -> "Model file missing. Re-download the model."
        is InferenceError.BackendUnavailable -> "No supported backend on this device."
        is InferenceError.LoadFailed -> error.message ?: "Failed to load model"
        is InferenceError.GenerationFailed -> error.message ?: "Generation failed"
        InferenceError.NoModelLoaded -> "No model loaded"
    }

    private fun updateInferenceSettings(settings: InferenceSettings) {
        val chatId = _uiState.value.activeChatId ?: return
        viewModelScope.launch {
            chatRepository.updateInferenceSettings(chatId, settings)
            // Rebuild the session with the new sampler + context window.
            rebuildSession(chatId)
        }
    }

    override fun onCleared() {
        super.onCleared()
        generationJob?.cancel()
        session?.close()
        session = null
        // viewModelScope is cancelled before this block runs, so launch on the
        // ModelManager's own application-level scope which outlives the ViewModel.
        modelManager.unloadInBackground()
    }
}
