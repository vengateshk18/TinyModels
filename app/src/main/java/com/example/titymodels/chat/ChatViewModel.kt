package com.example.titymodels.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.titymodels.models.domain.ObserveDownloadedModels
import com.example.titymodels.models.local.DownloadedModelEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class ChatViewModel(
    observeDownloadedModels: ObserveDownloadedModels,
    private val cacheDir: File
) : ViewModel() {

    private val engineRepository = ChatEngineRepository()
    private val messageIdGenerator = AtomicLong(0)

    val downloadedModels: StateFlow<List<DownloadedModelEntity>> =
        observeDownloadedModels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedModel = MutableStateFlow<DownloadedModelEntity?>(null)
    val selectedModel: StateFlow<DownloadedModelEntity?> = _selectedModel.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading.asStateFlow()

    private val _isModelReady = MutableStateFlow(false)
    val isModelReady: StateFlow<Boolean> = _isModelReady.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error.asStateFlow()

    fun selectModel(model: DownloadedModelEntity) {
        if (_selectedModel.value?.modelId == model.modelId) return
        _selectedModel.value = model
        _messages.value = emptyList()
        _isModelReady.value = false
        _error.value = ""

        viewModelScope.launch {
            _isModelLoading.value = true
            try {
                val modelFile = resolveModelFile(model)
                engineRepository.loadModel(modelFile, cacheDir)
                _isModelReady.value = true
            } catch (e: Exception) {
                _error.value = "Couldn't load ${model.modelId}: ${e.message}"
            } finally {
                _isModelLoading.value = false
            }
        }
    }

    fun sendMessage(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || !_isModelReady.value || _isGenerating.value) return

        val userMessage = ChatMessage(nextId(), ChatRole.USER, prompt)
        val assistantId = nextId()
        val assistantMessage = ChatMessage(assistantId, ChatRole.ASSISTANT, "", isStreaming = true)
        _messages.value = _messages.value + userMessage + assistantMessage
        _error.value = ""

        viewModelScope.launch {
            _isGenerating.value = true
            try {
                engineRepository.sendMessage(prompt).collect { chunk ->
                    appendToAssistantMessage(assistantId, chunk)
                }
            } catch (e: Exception) {
                _error.value = "Generation failed: ${e.message}"
            } finally {
                markAssistantMessageComplete(assistantId)
                _isGenerating.value = false
            }
        }
    }

    private fun appendToAssistantMessage(id: Long, chunk: String) {
        _messages.value = _messages.value.map { message ->
            if (message.id == id) message.copy(text = message.text + chunk) else message
        }
    }

    private fun markAssistantMessageComplete(id: Long) {
        _messages.value = _messages.value.map { message ->
            if (message.id == id) message.copy(isStreaming = false) else message
        }
    }

    private fun nextId(): Long = messageIdGenerator.incrementAndGet()

    private fun resolveModelFile(model: DownloadedModelEntity): File {
        val directory = File(model.localPath)
        val fileName = model.files.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.substringAfterLast("/")
            ?: throw IllegalStateException("No local file recorded for this model")
        val file = File(directory, fileName)
        if (!file.exists()) {
            throw IllegalStateException("Model file is missing on disk: ${file.absolutePath}")
        }
        return file
    }

    override fun onCleared() {
        super.onCleared()
        engineRepository.close()
    }
}

class ChatViewModelFactory(
    private val observeDownloadedModels: ObserveDownloadedModels,
    private val cacheDir: File
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ChatViewModel(observeDownloadedModels, cacheDir) as T
    }
}
