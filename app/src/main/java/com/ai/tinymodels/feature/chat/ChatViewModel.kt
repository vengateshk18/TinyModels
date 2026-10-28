package com.ai.tinymodels.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.common.TinyLog
import com.ai.tinymodels.core.common.InferenceError
import com.ai.tinymodels.core.inference.InferenceException
import com.ai.tinymodels.core.inference.ModelContextInspector
import com.ai.tinymodels.core.inference.ModelManager
import com.ai.tinymodels.core.inference.ModelVisionSupport
import com.ai.tinymodels.core.inference.runtime.ChatSession
import com.ai.tinymodels.core.inference.runtime.ChatTurn
import com.ai.tinymodels.core.inference.runtime.SessionBudget
import com.ai.tinymodels.core.inference.runtime.SessionRequest
import com.ai.tinymodels.core.inference.tools.WebSearchToolSet
import com.ai.tinymodels.core.network.WebSearchPrompt
import com.ai.tinymodels.core.network.WebSearchQuery
import com.ai.tinymodels.domain.model.ChatMessage
import com.ai.tinymodels.domain.model.WebSearchResult
import com.ai.tinymodels.domain.model.InferenceSettings
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.repository.ChatRepository
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.repository.SettingsRepository
import com.ai.tinymodels.domain.repository.WebSearchException
import com.ai.tinymodels.domain.repository.WebSearchRepository
import com.ai.tinymodels.feature.chat.model.ChatError
import com.ai.tinymodels.feature.chat.model.ChatEvent
import com.ai.tinymodels.feature.chat.model.ChatListItem
import com.ai.tinymodels.feature.chat.model.ChatUiState
import com.ai.tinymodels.feature.chat.model.GenerationState
import com.ai.tinymodels.feature.chat.model.ModelChipState
import com.ai.tinymodels.feature.chat.model.ModelLoadProgress
import com.ai.tinymodels.feature.chat.model.UiChatMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * MVI ViewModel for the chat feature. Exposes a single immutable [ChatUiState].
 *
 * Memory behavior is delegated to the process-wide [ModelManager]; this ViewModel
 * only orchestrates loading + per-chat [ChatSession]s (LiteRT-LM or MediaPipe,
 * chosen by the loaded file's format). The model survives configuration changes
 * because it is NOT owned here.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val modelManager: ModelManager,
    private val chatRepository: ChatRepository,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val webSearchToolSet: WebSearchToolSet,
    private val webSearchRepository: WebSearchRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** Downloaded models (parents) for the picker sheet. */
    val downloadedModels: StateFlow<List<DownloadedModel>> =
        modelRepository.observeDownloadedModels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Downloaded model files (children) for the file-level picker sheet. */
    val downloadedFiles: StateFlow<List<DownloadedModelFile>> =
        modelRepository.observeDownloadedFiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var session: ChatSession? = null
    private var generationJob: Job? = null
    private var activeModel: DownloadedModel? = null

    /**
     * Serializes ALL access to the native [ChatSession] / loaded model.
     * The native runtimes are NOT thread-safe: concurrent use of the same
     * conversation (e.g. a generation while compaction summarizes, or two
     * rebuilds racing) segfaults in liblitertlm_jni.so / the MediaPipe task.
     */
    private val nativeMutex = Mutex()

    /** The modelId the current [session] was built against. Used to detect a
     *  stale session after the shared engine was swapped (e.g. by Benchmark). */
    private var sessionModelId: String? = null

    /** The specific file of the active model that was loaded, if known. */
    private var activeFileName: String? = null

    /**
     * Last search sources per chat (in-memory recall for follow-ups).
     * Without this, "what is the model name" after a searched answer is
     * asked of a model that can no longer see the search.
     */
    private val lastSearchSources = mutableMapOf<String, List<WebSearchResult>>()

    /** Simulated progress ticker running while a model loads (see [selectModel]). */
    private var progressTickerJob: Job? = null

    /** The chatId for this screen, passed via nav arguments (SavedStateHandle). */
    val chatId: String? get() = savedStateHandle["chatId"]

    /** Preferred model to load, passed as a nav argument from model detail. */
    private val preferredModelId: String?
        get() = savedStateHandle["preferredModelId"]

    init {
        observeChats()           // populate the history drawer
        observeDownloadedModels()
        observeEngineState()
        observeWebSearchAvailability()

        val existingChatId = chatId?.takeIf { it != "new" }
        if (existingChatId != null) {
            // Opening a specific existing chat — load its model inside openChatInternal.
            viewModelScope.launch(Dispatchers.Default) { openChatInternal(existingChatId) }
        } else {
            // New chat or direct entry — auto-load the best available model,
            // unless the user disabled it in Settings (manual pick instead).
            viewModelScope.launch(Dispatchers.Default) {
                if (settingsRepository.getSettings().autoLoadLastModel) {
                    autoLoadModel()
                }
            }
        }
    }

    /**
     * Auto-selects a model on chat entry using this priority cascade:
     * 1. preferredModelId (from model detail nav arg)
     * 2. lastUsedModelId  (persisted in DataStore)
     * 3. Most recently downloaded model (fallback)
     *
     * Skips if a model is already in RAM.
     */
    private suspend fun autoLoadModel() {
        // Don't auto-load if a model is already in RAM.
        if (modelManager.loadedModelId != null) return

        val candidates = listOfNotNull(
            preferredModelId,
            settingsRepository.getLastUsedModelId()
        )

        for (modelId in candidates) {
            val downloaded = modelRepository.getDownloadedModel(modelId)
            if (downloaded != null) {
                selectModel(modelId)
                return
            }
        }

        // Fallback: most recently downloaded model.
        val fallback = modelRepository.getLastDownloadedModel()
        if (fallback != null) {
            selectModel(fallback.modelId)
        }
    }

    fun onEvent(event: ChatEvent) {
        when (event) {
            is ChatEvent.SelectModel -> selectModel(event.modelId, event.fileName)
            is ChatEvent.SendMessage -> sendMessage(event.text, event.imageUri)
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
            ChatEvent.CompactConversation -> compactNow()
            is ChatEvent.ToggleWebSearch -> toggleWebSearch(event.enabled)
        }
    }

    /** Per-chat web_search opt-in (the + menu in the input bar). */
    private fun toggleWebSearch(enabled: Boolean) {
        TinyLog.i(TAG, "JOURNEY: web search toggled enabled=$enabled — rebuilding session to (un)register the tool")
        _uiState.update { it.copy(webSearchEnabled = enabled) }
        val chatId = _uiState.value.activeChatId ?: return
        viewModelScope.launch(Dispatchers.Default) {
            chatRepository.updateWebSearchEnabled(chatId, enabled)
            // Re-register (or unregister) the tool with the live session.
            rebuildSession(chatId)
        }
    }

    // ---- Observation ----

    private fun observeChats() {
        viewModelScope.launch(Dispatchers.Default) {
            chatRepository.observeChatSummaries().collect { summaries ->
                _uiState.update { state ->
                    state.copy(chats = summaries.map {
                        ChatListItem(
                            id = it.id,
                            title = it.title,
                            preview = it.lastMessagePreview,
                            updatedAt = it.updatedAt,
                            modelId = it.modelId,
                            messageCount = it.messageCount
                        )
                    })
                }
            }
        }
    }

    private fun observeDownloadedModels() {
        viewModelScope.launch(Dispatchers.Default) {
            modelRepository.observeDownloadedModels().collect { models ->
                _uiState.update { it.copy(hasDownloadedModels = models.isNotEmpty()) }
            }
        }
    }

    /** Global availability gate for the web_search tool (Settings switch). */
    private fun observeWebSearchAvailability() {
        viewModelScope.launch(Dispatchers.Default) {
            settingsRepository.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        webSearchAvailable = settings.webSearchEnabled,
                        keepScreenOn = settings.keepScreenOn,
                        showTokenCounts = settings.showTokenCounts
                    )
                }
            }
        }
    }

    private fun observeEngineState() {
        viewModelScope.launch(Dispatchers.Default) {
            modelManager.engineState.collect { engineState ->
                val chip = when (engineState) {
                    is ModelManager.EngineState.Idle -> ModelChipState.NotSelected
                    is ModelManager.EngineState.Loading -> ModelChipState.Loading
                    is ModelManager.EngineState.Ready -> ModelChipState.Ready(
                        modelId = engineState.model.modelId,
                        backendLabel = engineState.model.backendUsed.name,
                        fileName = activeFileName
                    )
                    is ModelManager.EngineState.Error ->
                        ModelChipState.Error(friendlyError(engineState.error))
                }
                _uiState.update {
                    it.copy(
                        model = chip,
                        isVisionModel = (engineState as? ModelManager.EngineState.Ready)
                            ?.model?.visionEnabled == true
                    )
                }
            }
        }
    }

    // ---- Model selection / loading ----

    private fun selectModel(modelId: String, fileName: String? = null) {
        // Same model AND same file already loaded with a live session — nothing to do.
        if (modelManager.loadedModelId == modelId && session != null && activeFileName == fileName) return
        viewModelScope.launch(Dispatchers.Default) {
            val downloaded = modelRepository.getDownloadedModel(modelId)
            if (downloaded == null) {
                _uiState.update { it.copy(error = ChatError("Model $modelId is not downloaded")) }
                return@launch
            }
            // Resolve the exact file record to load — the user's pick, or the
            // first downloaded file as fallback.
            val fileRecord = if (fileName != null) {
                modelRepository.getModelFile(downloaded.modelId, fileName)
            } else {
                modelRepository.getDownloadedFileForModel(downloaded.modelId)
            }
            if (fileRecord == null) {
                _uiState.update {
                    it.copy(error = ChatError("Model file missing on device", "Re-download"))
                }
                return@launch
            }
            val modelFile = File(File(downloaded.localPath), fileRecord.fileName.substringAfterLast("/"))
            if (!modelFile.exists()) {
                _uiState.update {
                    it.copy(error = ChatError("Model file missing on device", "Re-download"))
                }
                return@launch
            }
            val chatId = _uiState.value.activeChatId
            val chat = chatId?.let { chatRepository.getChat(it) }
            val inference = chat?.inferenceSettings ?: InferenceSettings()

            // Detect the model's true compiled context window — the automatic
            // compaction budget. Context is NO LONGER user-tunable: detected
            // value when available, safe default otherwise.
            // File I/O — must stay off the main thread (ANR otherwise).
            val detectedContext = withContext(dispatchers.io) {
                ModelContextInspector.detectContext(modelFile)
            }
            val effectiveMaxTokens = detectedContext
                ?: com.ai.tinymodels.domain.model.InferenceSettings.DEFAULT_CONTEXT_TOKENS

            // Surface the detected context window to the UI.
            _uiState.update { it.copy(detectedContextTokens = detectedContext) }
            
            // Record the file being loaded BEFORE the engine flips to Ready so the
            // chip collector (observeEngineState) already sees the correct value.
            // Use the resolved record's name so auto-loads (no explicit pick)
            // also highlight the right file in the picker sheet.
            activeFileName = fileRecord.fileName

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

            // The native engine reports only a few discrete milestones (10% → 30% → 50%)
            // around one long-blocking initialize() call, so the bar would otherwise
            // stall and then jump to 100%. Run a simulated ticker alongside the real
            // load: it creeps asymptotically toward 90% and never reaches it, while
            // real milestones are merged in as a floor. Completion snaps to 100%.
            startProgressTicker(modelId)

            val result = modelManager.loadModel(
                modelId = modelId,
                modelFile = modelFile,
                backend = inference.backend,
                maxNumTokens = effectiveMaxTokens,
                onProgress = { progress, stage ->
                    _uiState.update { currentState ->
                        val uiStage = when (stage) {
                            ModelManager.ModelLoadStage.INITIALIZING -> ModelLoadProgress.LoadStage.INITIALIZING
                            ModelManager.ModelLoadStage.LOADING_WEIGHTS -> ModelLoadProgress.LoadStage.LOADING_WEIGHTS
                            ModelManager.ModelLoadStage.READY -> ModelLoadProgress.LoadStage.READY
                        }
                        currentState.copy(
                            modelLoadProgress = currentState.modelLoadProgress?.copy(
                                // Never regress below the simulated value.
                                progress = maxOf(simulatedProgress, progress),
                                stage = uiStage
                            )
                        )
                    }
                }
            )
            progressTickerJob?.cancel()
            progressTickerJob = null
            when (result) {
                is com.ai.tinymodels.core.common.AppResult.Success -> {
                    activeModel = downloaded
                    // Animate to 100% with "Ready!" label, hold briefly, then dismiss.
                    _uiState.update {
                        it.copy(
                            modelLoadProgress = it.modelLoadProgress?.copy(
                                progress = 1.0f,
                                stage = ModelLoadProgress.LoadStage.READY
                            )
                        )
                    }
                    delay(400)
                    _uiState.update { it.copy(modelLoadProgress = null) }
                    // Persist last-used model preference.
                    settingsRepository.setLastUsedModelId(modelId)
                    // (Re)build a conversation bound to the active chat, if any.
                    _uiState.value.activeChatId?.let { rebuildSession(it) }
                }
                is com.ai.tinymodels.core.common.AppResult.Error -> {
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

    /**
     * Simulated progress for the loading dialog. [simulatedProgress] is the current
     * fake value; the ticker nudges it toward [SIMULATED_PROGRESS_CEILING] (~90%)
     * with decreasing steps so it never actually arrives while the real load runs.
     */
    private var simulatedProgress = 0f

    private fun startProgressTicker(modelId: String) {
        progressTickerJob?.cancel()
        simulatedProgress = 0.05f
        progressTickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(150)
                simulatedProgress += (SIMULATED_PROGRESS_CEILING - simulatedProgress) * 0.06f
                val stage = if (simulatedProgress < 0.35f) {
                    ModelLoadProgress.LoadStage.INITIALIZING
                } else {
                    ModelLoadProgress.LoadStage.LOADING_WEIGHTS
                }
                _uiState.update { state ->
                    val current = state.modelLoadProgress ?: return@update state
                    if (current.stage == ModelLoadProgress.LoadStage.READY) return@update state
                    // Stage is monotonic: never regress from LOADING_WEIGHTS to INITIALIZING.
                    val mergedStage = if (stage == ModelLoadProgress.LoadStage.LOADING_WEIGHTS ||
                        current.stage == ModelLoadProgress.LoadStage.LOADING_WEIGHTS
                    ) {
                        ModelLoadProgress.LoadStage.LOADING_WEIGHTS
                    } else {
                        ModelLoadProgress.LoadStage.INITIALIZING
                    }
                    state.copy(
                        modelLoadProgress = current.copy(
                            progress = maxOf(current.progress, simulatedProgress),
                            stage = mergedStage
                        )
                    )
                }
            }
        }
    }

    private companion object {
        /** The simulated ticker approaches but never reaches this value. */
        const val SIMULATED_PROGRESS_CEILING = 0.90f

        /** Compaction triggers when usage crosses this fraction of the budget. */
        const val COMPACTION_HEADROOM = 0.90

        // Same tag as WebSearchToolSet's tool logs so one logcat filter
        // (adb logcat -s TinyModelsJourney) shows the entire turn.
        private const val TAG = "TinyModelsJourney"
    }

    // ---- Chat lifecycle ----

    private fun newChat() {
        // Do NOT write to the DB here. Just reset the active chat state so the
        // user sees a blank canvas. The actual DB row is created in sendMessage()
        // when the user actually sends their first message.
        TinyLog.i(TAG, "JOURNEY: newChat — closing session and resetting active chat state")
        session?.close()
        session = null
        _uiState.update {
            it.copy(
                activeChatId = null,
                messages = emptyList(),
                streamingMessageId = null,
                generation = GenerationState.IDLE,
                error = null,
                compactedSummary = null,
                compactionCount = 0,
                webSearchEnabled = false
            )
        }
    }

    private fun openChat(chatId: String) {
        viewModelScope.launch(Dispatchers.Default) { openChatInternal(chatId) }
    }

    private suspend fun openChatInternal(chatId: String) {
        val chat = chatRepository.getChat(chatId) ?: return
        TinyLog.i(
            TAG,
            "JOURNEY: openChat(chatId=$chatId, model=${chat.modelId}, " +
                "webSearchEnabled=${chat.webSearchEnabled}, compactionCount=${chat.compactionCount})"
        )
        // Ensure the chat's model is the one loaded; if not, load it.
        if (modelManager.loadedModelId != chat.modelId) {
            selectModel(chat.modelId)
            // selectModel triggers rebuildSession via activeChatId once ready; set id now.
            _uiState.update { it.copy(activeChatId = chatId) }
        }
        _uiState.update {
            it.copy(
                activeChatId = chatId,
                inferenceSettings = chat.inferenceSettings,
                compactedSummary = chat.compactedSummary,
                compactionCount = chat.compactionCount,
                webSearchEnabled = chat.webSearchEnabled
            )
        }
        rebuildSession(chatId)
        observeMessages(chatId)
    }

    private fun deleteChat(chatId: String) {
        viewModelScope.launch(Dispatchers.Default) {
            TinyLog.i(TAG, "JOURNEY: deleteChat(chatId=$chatId)")
            chatRepository.deleteChat(chatId)
            if (_uiState.value.activeChatId == chatId) {
                session?.close()
                session = null
                _uiState.update { it.copy(activeChatId = null, messages = emptyList()) }
            }
        }
    }

    /** Build a fresh [ChatSession] restoring prior turns for [chatId]. */
    private suspend fun rebuildSession(chatId: String) {
        session?.close()
        session = null
        val chat = chatRepository.getChat(chatId)
        val inference = chat?.inferenceSettings ?: InferenceSettings()
        // Messages covered by a compaction digest are excluded — their gist
        // is carried by the digest instead (folded into the system prompt).
        val cutoff = chat?.compactedUpToCreatedAt
        val history = chatRepository.getMessages(chatId)
            .filter { it.isComplete && !SessionBudget.isCoveredByDigest(it.createdAt, cutoff) }
            .map {
                ChatTurn(
                    role = if (it.role == ChatMessage.Role.USER) ChatTurn.Role.USER else ChatTurn.Role.ASSISTANT,
                    text = it.content,
                    imagePaths = listOfNotNull(it.imagePath)
                )
            }
        // Tools: register the web_search tool only when the loaded runtime can
        // execute tools (LiteRT-LM), the global setting (availability gate) AND
        // the per-chat flag are all enabled — otherwise zero tool overhead in
        // the prompt. MediaPipe chats rely on the prompt-level search
        // augmentation (maybeAugmentWithSearch) instead.
        val toolsSupported = modelManager.activeRuntimeSupportsTools
        val tools = if (toolsSupported && _uiState.value.webSearchAvailable && chat?.webSearchEnabled == true) {
            TinyLog.i(TAG, "Registering web_search tool for chat $chatId")
            listOf(com.google.ai.edge.litertlm.tool(webSearchToolSet))
        } else {
            TinyLog.i(
                TAG,
                "web_search tool NOT registered for chat $chatId " +
                    "(runtime=$toolsSupported, global=${_uiState.value.webSearchAvailable}, " +
                    "perChat=${chat?.webSearchEnabled})"
            )
            emptyList()
        }
        val priorSummary = chat?.compactedSummary
        // When search is on, tell the model it can read live results so it
        // never says "I cannot access the internet" over fresh headlines.
        val effectiveSystem = if (tools.isNotEmpty()) {
            listOfNotNull(
                inference.systemInstruction?.takeIf { it.isNotBlank() },
                WebSearchPrompt.SYSTEM_LINE
            ).joinToString("\n\n")
        } else {
            inference.systemInstruction
        }
        TinyLog.i(
            TAG,
            "JOURNEY: rebuildSession(chat=$chatId) — " +
                "systemInstruction=\"${inference.systemInstruction}\", " +
                "history=${history.size} turns (cutoff=${cutoff ?: "none"}), " +
                "priorSummary=${priorSummary?.let { "${it.length} chars" } ?: "none"}, " +
                "tools=${tools.size}"
        )

        // Session construction is native blocking work — run it off the main
        // thread (viewModelScope defaults to Dispatchers.Main). Hold the native
        // mutex: closing the old session + creating the new one must never race
        // another native call.
        //
        // Vision guard: a runtime without a vision path (text-only .litertlm or
        // any MediaPipe model) cannot hold ImageFile parts — restoring a
        // photo-carrying chat onto it fails native session creation. Strip
        // attachments to their text so the chat stays usable; photo sends are
        // blocked up-front in sendMessage().
        val visionEnabled = modelManager.isVisionModelLoaded
        val hadImages = ModelVisionSupport.historyHasImages(history)
        if (hadImages && !visionEnabled) {
            TinyLog.i(
                TAG,
                "JOURNEY: rebuildSession(chat=$chatId) — text-only model, " +
                    "stripping photo attachments from ${history.count { it.imagePaths.isNotEmpty() }} turn(s)"
            )
        }
        val effectiveHistory =
            if (visionEnabled) history else ModelVisionSupport.stripImages(history)
        try {
            withContext(dispatchers.io) {
                nativeMutex.withLock {
                    session = modelManager.createSession(
                        SessionRequest(
                            maxContextTokens = modelManager.activeMaxNumTokens
                                ?: ModelManager.DEFAULT_MAX_TOKENS,
                            systemInstruction = effectiveSystem,
                            history = effectiveHistory,
                            sampler = com.ai.tinymodels.domain.model.SamplerSettings(
                                temperature = inference.temperature,
                                topK = inference.topK,
                                topP = inference.topP
                            ),
                            priorSummary = chat?.compactedSummary,
                            modelName = chat?.modelId,
                            tools = tools
                        )
                    )
                }
            }
            sessionModelId = modelManager.loadedModelId
            updateContextUsage()
            if (hadImages && !visionEnabled) {
                // Photo skipped silently on text-only models (no snackbar).
            }
        } catch (e: InferenceException) {
            // Engine not loaded yet; session will be built when the model finishes loading.
        } catch (e: CancellationException) {
            throw e
        } catch (throwable: Throwable) {
            // Native conversation creation failed — this MUST NOT crash the
            // app (previously a FATAL EXCEPTION for
            // "TF_LITE_VISION_ENCODER not found in the model" when a
            // photo-carrying chat was restored onto a text-only model).
            TinyLog.e(TAG, "JOURNEY: rebuildSession failed: ${throwable.message}", throwable)
            if (ModelVisionSupport.isVisionEncoderError(throwable)) {
                handleVisionEncoderFailure(chatId, history, effectiveSystem, inference, tools, chat)
            } else {
                session = null
                _uiState.update { it.copy(error = ChatError(friendlyConversationError(throwable))) }
            }
        }
    }

    /**
     * Self-healing path for `TF_LITE_VISION_ENCODER not found`: the live
     * engine was built with vision against a text-only model. Reload the
     * engine without vision, then rebuild the session with photo attachments
     * stripped. Falls back to a user-facing error when the reload fails.
     */
    private suspend fun handleVisionEncoderFailure(
        chatId: String,
        history: List<ChatTurn>,
        effectiveSystem: String?,
        inference: InferenceSettings,
        tools: List<com.google.ai.edge.litertlm.ToolProvider>,
        chat: com.ai.tinymodels.domain.model.Chat?
    ) {
        val stripped = ModelVisionSupport.stripImages(history)
        val reloaded = try {
            withContext(dispatchers.io) { modelManager.reloadWithoutVision() }
        } catch (throwable: Throwable) {
            TinyLog.w(TAG, "Vision self-heal reload failed: ${throwable.message}")
            false
        }
        if (reloaded) {
            try {
                withContext(dispatchers.io) {
                    nativeMutex.withLock {
                        session = modelManager.createSession(
                            SessionRequest(
                                maxContextTokens = modelManager.activeMaxNumTokens
                                    ?: ModelManager.DEFAULT_MAX_TOKENS,
                                systemInstruction = effectiveSystem,
                                history = stripped,
                                sampler = com.ai.tinymodels.domain.model.SamplerSettings(
                                    temperature = inference.temperature,
                                    topK = inference.topK,
                                    topP = inference.topP
                                ),
                                priorSummary = chat?.compactedSummary,
                                modelName = chat?.modelId,
                                tools = tools
                            )
                        )
                    }
                }
                sessionModelId = modelManager.loadedModelId
                updateContextUsage()
                // Photo skipped silently on text-only models (no snackbar).
                return
            } catch (retryFailure: Throwable) {
                TinyLog.e(TAG, "Rebuild after vision reload failed: ${retryFailure.message}", retryFailure)
            }
        } else if (ModelVisionSupport.historyHasImages(history)) {
            // Engine already vision-free but the history still carried photos
            // (e.g. loaded before this fix): one retry with stripped history.
            try {
                withContext(dispatchers.io) {
                    nativeMutex.withLock {
                        session = modelManager.createSession(
                            SessionRequest(
                                maxContextTokens = modelManager.activeMaxNumTokens
                                    ?: ModelManager.DEFAULT_MAX_TOKENS,
                                systemInstruction = effectiveSystem,
                                history = stripped,
                                sampler = com.ai.tinymodels.domain.model.SamplerSettings(
                                    temperature = inference.temperature,
                                    topK = inference.topK,
                                    topP = inference.topP
                                ),
                                priorSummary = chat?.compactedSummary,
                                modelName = chat?.modelId,
                                tools = tools
                            )
                        )
                    }
                }
                sessionModelId = modelManager.loadedModelId
                updateContextUsage()
                // Photo skipped silently on text-only models (no snackbar).
                return
            } catch (retryFailure: Throwable) {
                TinyLog.e(TAG, "Stripped-history rebuild failed: ${retryFailure.message}", retryFailure)
            }
        }
        session = null
        // Photo skipped silently on text-only models (no snackbar).
    }

    /**
     * Compact the conversation when it outgrows the model's context budget:
     * summarize older turns into a digest (via the same local model), persist
     * it with a cutoff timestamp, and rebuild the session so the KV cache
     * only holds the digest + recent turns. Falls back to the plain
     * sliding-window trim if summarization fails.
     */
    private suspend fun maybeCompact(chatId: String) {
        val activeSession = session ?: return
        val chat = chatRepository.getChat(chatId) ?: return
        // The summarization generation is native blocking work — run the
        // whole compaction flow off the main thread. The native mutex (held
        // here for the summarization, and re-acquired inside rebuildSession)
        // serializes native access against any concurrent generation.
        withContext(dispatchers.io) {
            nativeMutex.withLock {
                maybeCompactInternal(chatId, activeSession, chat)
            }
        }
    }

    /**
     * Manual compaction (context metrics sheet's Compact button). Same flow
     * as auto-compaction but bypasses the 90% threshold check.
     */
    private fun compactNow() {
        val chatId = _uiState.value.activeChatId ?: return
        val activeSession = session ?: return
        TinyLog.i(TAG, "JOURNEY: manual compaction requested for chat $chatId")
        _uiState.update { it.copy(isCompacting = true, compactionMessage = null) }
        viewModelScope.launch(Dispatchers.Default) {
            val chat = chatRepository.getChat(chatId)
            val message = if (chat == null) {
                "No active chat to compact"
            } else {
                withContext(dispatchers.io) {
                    nativeMutex.withLock {
                        compactInternal(chatId, activeSession, chat)
                    }
                }
            }
            // rebuildSession (inside compactInternal) already refreshed
            // contextUsage; refresh again so the sheet/icon always reflect
            // the post-compaction token count, then surface the result.
            updateContextUsage()
            _uiState.update { it.copy(isCompacting = false, compactionMessage = message) }
        }
    }

    /** The automatic compaction budget: detected context window, or safe default. */
    private fun autoContextBudget(): Int =
        _uiState.value.detectedContextTokens
            ?: com.ai.tinymodels.domain.model.InferenceSettings.DEFAULT_CONTEXT_TOKENS

    private suspend fun maybeCompactInternal(
        chatId: String,
        activeSession: ChatSession,
        chat: com.ai.tinymodels.domain.model.Chat
    ) {
        // Trigger at 90% of the budget — NOT 10%: (1 - HEADROOM) was an
        // inversion that compacted after ~1 short turn.
        val budget = (autoContextBudget() * COMPACTION_HEADROOM).toInt()
        if (activeSession.estimatedTokensInContext < budget) {
            TinyLog.i(
                TAG,
                "JOURNEY: auto-compaction skipped — " +
                    "${activeSession.estimatedTokensInContext} tokens < trigger $budget"
            )
            return
        }
        TinyLog.i(
            TAG,
            "JOURNEY: auto-compaction triggered — " +
                "${activeSession.estimatedTokensInContext} tokens >= trigger $budget"
        )
        _uiState.update { it.copy(isCompacting = true) }
        try {
            compactInternal(chatId, activeSession, chat)
        } finally {
            _uiState.update { it.copy(isCompacting = false) }
        }
    }

    /**
     * The actual compaction work. Caller must hold [nativeMutex].
     * Returns a user-facing result message for the metrics sheet.
     */
    private suspend fun compactInternal(
        chatId: String,
        activeSession: ChatSession,
        chat: com.ai.tinymodels.domain.model.Chat
    ): String {
        val budget = autoContextBudget()

        // Only live (not yet summarized) turns are eligible. Re-summarizing
        // the already-compacted prefix would bloat the digest and overstate
        // the reported savings.
        val live = chatRepository.getMessages(chatId)
            .filter {
                it.isComplete &&
                    !SessionBudget.isCoveredByDigest(it.createdAt, chat.compactedUpToCreatedAt)
            }
        val allTurns = live.map {
            ChatTurn(
                role = if (it.role == ChatMessage.Role.USER) ChatTurn.Role.USER else ChatTurn.Role.ASSISTANT,
                text = it.content,
                imagePaths = listOfNotNull(it.imagePath)
            )
        }
        // Keep the most recent turns that fit in half the budget; summarize the rest.
        val keepBudget = budget / 2
        val kept = SessionBudget.trimToBudget(allTurns, keepBudget)
        val olderCount = allTurns.size - kept.size
        if (olderCount <= 0) {
            return "Nothing to compact — the conversation already fits the context window"
        }
        val olderEntities = live.take(olderCount)
        val older = olderEntities.map {
            ChatTurn(
                role = if (it.role == ChatMessage.Role.USER) ChatTurn.Role.USER else ChatTurn.Role.ASSISTANT,
                text = it.content,
                imagePaths = listOfNotNull(it.imagePath)
            )
        }

        // Include the previous digest in the summarization input (iterative
        // compaction: summarize the summary).
        val priorDigest = chat.compactedSummary
        val digestInput = if (priorDigest != null) {
            listOf(ChatTurn(ChatTurn.Role.ASSISTANT, priorDigest)) + older
        } else {
            older
        }
        val digest = activeSession.summarizeOlderTurns(digestInput)
        if (digest == null) {
            // Summarization failed — the rebuild below still trims to budget.
            // Release the mutex so rebuildSession can re-acquire it.
            nativeMutex.unlock()
            try {
                rebuildSession(chatId)
            } finally {
                nativeMutex.lock()
            }
            return "Summarization failed — history was trimmed to fit the context window"
        }
        // Cutoff by entity identity, never by content match: duplicate texts
        // ("thanks", "hi") previously resolved the cutoff to the wrong message
        // and silently dropped live turns from context. Contract: messages at
        // or before the cutoff are covered by the digest (see isCoveredByDigest).
        val newCutoff = olderEntities.last().createdAt
        chatRepository.saveCompaction(chatId, digest, newCutoff)
        _uiState.update {
            it.copy(
                compactedSummary = digest,
                compactionCount = chat.compactionCount + 1
            )
        }
        // Release the mutex so rebuildSession can re-acquire it.
        nativeMutex.unlock()
        try {
            rebuildSession(chatId)
        } finally {
            nativeMutex.lock()
        }
        return "Compacted ${older.size} older message${if (older.size == 1) "" else "s"} into a summary"
    }

    /** Invalidate the compaction digest when the user edits a summarized turn. */
    private suspend fun invalidateCompactionIfNeeded(chatId: String, messageId: String) {
        val chat = chatRepository.getChat(chatId) ?: return
        val cutoff = chat.compactedUpToCreatedAt ?: return
        val edited = chatRepository.getMessages(chatId).firstOrNull { it.id == messageId } ?: return
        // Covered = at or before the cutoff (boundary message IS summarized).
        if (edited.createdAt <= cutoff) {
            chatRepository.saveCompaction(chatId, null, null)
            _uiState.update { it.copy(compactedSummary = null, compactionCount = 0) }
        }
    }

    private fun observeMessages(chatId: String) {
        viewModelScope.launch(Dispatchers.Default) {
            chatRepository.observeMessages(chatId).collect { messages ->
                _uiState.update { state ->
                    if (state.activeChatId != chatId) return@update state
                    val roomUi = messages.map { it.toUi() }
                    // Merge: use Room data, but preserve the in-flight streaming
                    // assistant message (with its live partial text) when active.
                    val streamingId = state.streamingMessageId
                    val streamingMsg = state.messages.firstOrNull { it.id == streamingId }
                    val merged = if (streamingId != null && streamingMsg != null) {
                        // Drop any stale Room version of the streaming placeholder
                        // (it won't exist until persisted) and append the live one.
                        roomUi.filterNot { it.id == streamingId } + streamingMsg
                    } else {
                        roomUi
                    }
                    // Safety net: the message list is keyed by id in LazyColumn,
                    // so a duplicate id here would crash the list at measure time.
                    state.copy(messages = merged.distinctBy { it.id })
                }
            }
        }
    }

    // ---- Sending / generation ----

    private fun sendMessage(rawText: String, imageUri: String?) {
        val text = rawText.trim()
        if (text.isEmpty() && imageUri == null) return
        val state = _uiState.value
        if (state.generation == GenerationState.GENERATING) return
        if (state.model !is ModelChipState.Ready) {
            _uiState.update { it.copy(error = ChatError("Model is still loading")) }
            return
        }
        // Block photo sends on text-only engines up-front: the native side has
        // no vision encoder, so an image part would fail the turn anyway.
        // Text-only prompts fall through normally.
        if (imageUri != null && !modelManager.isVisionModelLoaded) {
            // Photo skipped silently on text-only models (no snackbar).
            return
        }
        var chatId = state.activeChatId
        TinyLog.i(TAG, "JOURNEY: user sent prompt (${text.length} chars, image=${imageUri != null}): \"$text\"")
        viewModelScope.launch(Dispatchers.Default) {
            if (chatId == null) {
                val modelId = activeModel?.modelId ?: modelManager.loadedModelId ?: return@launch
                TinyLog.i(TAG, "JOURNEY: no active chat — creating one for model $modelId")
                // Carry the pre-first-message UI toggle into the chat row —
                // otherwise the entity default (false) silently drops the
                // tool the user already enabled before sending.
                val webSearchEnabled = _uiState.value.webSearchEnabled
                chatId = chatRepository.createChat(
                    modelId = modelId,
                    title = text.take(40),
                    webSearchEnabled = webSearchEnabled
                ).id
                TinyLog.i(
                    TAG,
                    "JOURNEY: chat created (id=$chatId) with webSearchEnabled=$webSearchEnabled"
                )
                _uiState.update { it.copy(activeChatId = chatId) }
                rebuildSession(chatId!!)
            }
            val activeChatId = chatId!!
            TinyLog.i(
                TAG,
                "JOURNEY: chat=$activeChatId session=${if (session != null) "live" else "null"} " +
                    "webSearch(global=${state.webSearchAvailable}, chat=${state.webSearchEnabled}) " +
                    "contextUsage=${state.contextUsage.usedTokens}/${state.contextUsage.maxTokens}"
            )

            // Persist the user message (photo is copied into app storage first
            // so the content-URI permission doesn't expire out from under us).
            val savedImagePath = imageUri?.let { copyPickedImage(activeChatId, it) }
            if (imageUri != null && savedImagePath == null) {
                _uiState.update {
                    it.copy(
                        generation = GenerationState.IDLE,
                        streamingMessageId = null,
                        error = ChatError("Couldn't read that photo — try another image")
                    )
                }
                return@launch
            }
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                chatId = activeChatId,
                role = ChatMessage.Role.USER,
                content = text,
                tokenCount = SessionBudget.estimateTokens(text),
                createdAt = System.currentTimeMillis(),
                isComplete = true,
                imagePath = savedImagePath
            )
            chatRepository.saveMessage(userMessage)

            // Optimistic UI: append user bubble + a streaming assistant placeholder.
            val assistantId = UUID.randomUUID().toString()
            _uiState.update { s ->
                // The Room observer can deliver the persisted user message while
                // saveMessage() is still suspended (invalidation + re-query emit
                // before this update runs). Appending it again would create two
                // items with the same id and crash LazyColumn, which keys
                // messages by id — so only append when it is not already present.
                val withUserMessage =
                    if (s.messages.any { it.id == userMessage.id }) s.messages
                    else s.messages + userMessage.toUi()
                s.copy(
                    generation = GenerationState.GENERATING,
                    streamingMessageId = assistantId,
                    messages = withUserMessage + UiChatMessage(
                        id = assistantId, isUser = false, text = "", isStreaming = true,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            // Title the chat from the first message.
            if (chatRepository.messageCount(activeChatId) <= 1) {
                chatRepository.renameChat(activeChatId, text.take(40))
            }

            // The shared engine may have been swapped since this session was
            // built (e.g. a benchmark loaded another model) — rebuild before
            // generating so we never stream through a dead engine.
            if (sessionModelId != modelManager.loadedModelId) {
                TinyLog.i(TAG, "JOURNEY: engine swapped since session build — rebuilding session")
                rebuildSession(activeChatId)
            }

            TinyLog.i(TAG, "JOURNEY: starting generation (assistantId=$assistantId)")
            val prompt = maybeAugmentWithSearch(activeChatId, text)
                .ifBlank { "What do you see in this image?" }
            runGeneration(activeChatId, assistantId, prompt, listOfNotNull(savedImagePath))
        }
    }

    /**
     * Copies a picked photo (content URI) into app-private storage, downscaled
     * so a 12 MP camera shot doesn't blow the vision encoder's budget.
     * Returns the absolute file path, or null when the image is unreadable.
     */
    private suspend fun copyPickedImage(chatId: String, uriString: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val uri = android.net.Uri.parse(uriString)
                val dir = java.io.File(appContext.filesDir, "chat_images").apply { mkdirs() }
                val target = java.io.File(dir, "${chatId}_${java.util.UUID.randomUUID()}.jpg")
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    // Downscale to ≤1568px on the long edge before saving.
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    val bytes = input.readBytes()
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    val longest = maxOf(bounds.outWidth, bounds.outHeight)
                    while (longest / sample > 1568) sample *= 2
                    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        ?: return@withContext null
                    target.outputStream().use { out ->
                        if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)) {
                            return@withContext null
                        }
                    }
                    if (!bitmap.isRecycled) bitmap.recycle()
                } ?: return@withContext null
                TinyLog.i(TAG, "JOURNEY: photo saved for vision (${target.length()} bytes): ${target.absolutePath}")
                target.absolutePath
            } catch (t: Throwable) {
                TinyLog.w(TAG, "Photo copy failed: ${t.message}")
                null
            }
        }

    /**
     * App-side auto-search (primary trigger). Tiny on-device models often skip
     * tool calls entirely — this does not need their cooperation.
     *
     * Returns the prompt to send: either [WebSearchPrompt.webSearchPrompt] /
     * [WebSearchPrompt.webRecallPrompt] output, or the raw [text] when search
     * is off, unneeded, empty, or failed (model then answers from knowledge).
     * The raw user message is still what gets persisted; only this turn's
     * prompt is augmented. Sources are kept in [lastSearchSources] for
     * follow-up recall.
     */
    private suspend fun maybeAugmentWithSearch(chatId: String, text: String): String {
        if (!_uiState.value.webSearchAvailable || !_uiState.value.webSearchEnabled) return text
        // Fresh search when the message wants live info.
        if (WebSearchQuery.needsWebSearch(text)) {
            return try {
                val previous = chatRepository.getMessages(chatId)
                    .filter { it.role == ChatMessage.Role.USER }
                    .takeLast(3)
                    .map { it.content }
                val sent = WebSearchQuery.searchQueryFrom(text, previous)
                if (sent.isBlank()) return text
                val started = System.currentTimeMillis()
                val results = webSearchRepository.search(sent)
                val ms = System.currentTimeMillis() - started
                if (results.isNotEmpty) {
                    lastSearchSources[chatId] = results.results
                    TinyLog.i(
                        TAG,
                        "JOURNEY: web_search auto (${results.results.size} results, lead=${results.leadText.length} chars) in ${ms}ms for \"$sent\""
                    )
                    WebSearchPrompt.webSearchPrompt(results, text)
                } else {
                    TinyLog.w(
                        TAG,
                        "JOURNEY: web_search auto empty (blocked=${results.blocked}) in ${ms}ms for \"$sent\""
                    )
                    if (results.blocked) {
                        "$text\n\n(Note: live web search was blocked just now; answer from your own knowledge and say so.)"
                    } else {
                        // Fall back to prior sources rather than nothing.
                        val prior = lastSearchSources[chatId].orEmpty()
                        if (prior.isNotEmpty()) WebSearchPrompt.webRecallPrompt(prior, text) else text
                    }
                }
            } catch (e: WebSearchException) {
                TinyLog.w(TAG, "JOURNEY: web_search auto failed: ${e.message}")
                text
            } catch (e: Exception) {
                TinyLog.w(TAG, "JOURNEY: web_search auto failed unexpectedly: ${e.message}")
                text
            }
        }
        // Follow-up with no fresh need: re-show prior sources so the model
        // does not invent or refuse ("what is the model name" after an answer).
        val prior = lastSearchSources[chatId].orEmpty()
        if (prior.isNotEmpty() && WebSearchQuery.readsAsFollowUp(text)) {
            TinyLog.i(TAG, "JOURNEY: web_search recall (${prior.size} sources) for follow-up \"$text\"")
            return WebSearchPrompt.webRecallPrompt(prior, text)
        }
        return text
    }

    private fun runGeneration(
        chatId: String,
        assistantId: String,
        prompt: String,
        imagePaths: List<String> = emptyList()
    ) {
        val activeSession = session
        if (activeSession == null) {
            _uiState.update {
                it.copy(generation = GenerationState.IDLE, error = ChatError("No active conversation"))
            }
            return
        }
        generationJob = viewModelScope.launch(Dispatchers.Default) {
            val buffer = StringBuilder()
            val generationStart = System.currentTimeMillis()
            var emissionCount = 0
            var lastLogAt = generationStart
            // Hold the native mutex for the whole generation so a concurrent
            // compaction summarization can never touch the same conversation.
            nativeMutex.withLock {
                activeSession.send(prompt, imagePaths)
                .catch { throwable ->
                    // Re-throw cancellation so the cancel handler (cancelGeneration)
                    // owns the cleanup path instead of surfacing a spurious error.
                    if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                    TinyLog.e(
                        TAG,
                        "JOURNEY: generation FAILED after $emissionCount emissions " +
                            "(${buffer.length} chars): ${throwable.message}",
                        throwable
                    )
                    // Persist partial, then surface the error.
                    persistAssistant(chatId, assistantId, buffer.toString(), isComplete = false)
                    _uiState.update {
                        it.copy(
                            generation = GenerationState.IDLE,
                            streamingMessageId = null,
                            error = ChatError(friendlyGenerationError(throwable))
                        )
                    }
                }
                .collect { cumulative ->
                    // ChatSession.send() accumulates delta chunks internally
                    // and emits cumulative text. Replace the buffer + UI with each emission.
                    emissionCount++
                    buffer.setLength(0)
                    buffer.append(cumulative)
                    // Throttled stream logging: at most every 2s, so long
                    // generations are traceable without flooding logcat.
                    val now = System.currentTimeMillis()
                    if (now - lastLogAt >= 2_000) {
                        TinyLog.i(
                            TAG,
                            "JOURNEY: streaming… emission #$emissionCount, ${buffer.length} chars so far"
                        )
                        lastLogAt = now
                    }
                    _uiState.update { s ->
                        s.copy(messages = s.messages.map { m ->
                            if (m.id == assistantId) m.copy(text = cumulative, isStreaming = true) else m
                        })
                    }
                }
            }
            // Completed normally (catch already handled the failure path).
            if (_uiState.value.generation == GenerationState.GENERATING) {
                val finishedAt = System.currentTimeMillis()
                val totalMs = finishedAt - generationStart
                TinyLog.i(
                    TAG,
                    "JOURNEY: generation complete in ${totalMs}ms — $emissionCount emissions, " +
                        "${buffer.length} chars, ~${buffer.length / 4} tokens " +
                        "(~${if (totalMs > 0) (buffer.length / 4 * 1000 / totalMs) else 0} tokens/s)"
                )
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
                // Compact the conversation if it has outgrown the context budget.
                maybeCompact(chatId)
            }
            updateContextUsage()
            TinyLog.i(
                TAG,
                "JOURNEY: turn done — context usage now " +
                    "${_uiState.value.contextUsage.usedTokens}/${_uiState.value.contextUsage.maxTokens} tokens"
            )
        }
    }

    private fun cancelGeneration() {
        TinyLog.i(TAG, "JOURNEY: generation cancelled by user")
        generationJob?.cancel()
        generationJob = null
        // Mark the in-flight assistant message as stopped (keep partial text).
        _uiState.update { s ->
            val updated = s.messages.map { m -> if (m.isStreaming) m.copy(isStreaming = false) else m }
            s.copy(generation = GenerationState.IDLE, streamingMessageId = null, messages = updated)
        }
        // Persist whatever partial assistant text remains for this chat.
        viewModelScope.launch(Dispatchers.Default) {
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

        viewModelScope.launch(Dispatchers.Default) {
            TinyLog.i(TAG, "JOURNEY: regenerate — deleting old reply, re-sending prompt: \"${lastUser.text}\"")
            // Delete the old assistant message from Room.
            chatRepository.deleteMessage(lastAssistant.id)
            // Remove it from the UI immediately.
            _uiState.update { s ->
                s.copy(messages = s.messages.filterNot { it.id == lastAssistant.id })
            }

            // Rebuild so the deleted reply leaves the native KV cache AND the
            // token counter — otherwise the stale reply keeps occupying context
            // forever and the usage number only ever grows.
            TinyLog.i(TAG, "JOURNEY: regenerate — rebuilding session to drop stale reply")
            rebuildSession(chatId)

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

        viewModelScope.launch(Dispatchers.Default) {
            TinyLog.i(TAG, "JOURNEY: editMessage — edited prompt now: \"$text\"; rebuilding session and re-generating")
            val allMessages = chatRepository.getMessages(chatId)
            val targetIndex = allMessages.indexOfFirst { it.id == messageId }
            if (targetIndex < 0) return@launch

            // Update the user message content.
            val updated = allMessages[targetIndex].copy(content = text)
            chatRepository.saveMessage(updated)
            // Editing a turn inside the compacted region invalidates the digest.
            invalidateCompactionIfNeeded(chatId, messageId)

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
                tokenCount = SessionBudget.estimateTokens(text),
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
                contextUsage = com.ai.tinymodels.feature.chat.model.ContextUsage(
                    usedTokens = activeSession.estimatedTokensInContext,
                    // Prefer the engine's actual loaded budget (clamped to the
                    // detected context window) over the raw user setting.
                    maxTokens = modelManager.activeMaxNumTokens
                        ?: it.detectedContextTokens
                        ?: it.contextUsage.maxTokens,
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
        completedAt = completedAt,
        tokenCount = tokenCount.takeIf { isComplete },
        imagePath = imagePath
    )

    /**
     * Maps raw engine failures to user-friendly messages. Notably, MediaPipe/
     * LiteRT-LM surfaces context-overflow as "Status code 13: Task failed with
     * large input Id" — translate that into actionable guidance.
     */
    private fun friendlyGenerationError(throwable: Throwable): String {
        val raw = throwable.message ?: return "Generation failed"
        // Photo sent to a text-only model (or vision init skipped): the
        // native side reports a null vision executor / unavailable image input.
        if (raw.contains("vision executor", ignoreCase = true) ||
            raw.contains("TryLoadingVisionExecutor") ||
            raw.contains("image input", ignoreCase = true) ||
            raw.contains("TF_LITE_VISION_ENCODER") ||
            raw.contains("vision encoder", ignoreCase = true)
        ) {
            return "This model can't read photos. Load a vision-capable model " +
                "(e.g. Gemma 3) to analyze images."
        }
        return if (raw.contains("Status code 13") || raw.contains("large input", ignoreCase = true)) {
            "Prompt or chat history is too long for this model's context window. " +
                "Start a new chat or reduce max context tokens in session settings."
        } else raw
    }

    /**
     * Maps native conversation-creation failures (rebuildSession path) to
     * user-facing text. Vision-encoder failures get the photo guidance; the
     * generic branch never leaks a native stack trace verbatim.
     */
    private fun friendlyConversationError(throwable: Throwable): String {
        if (ModelVisionSupport.isVisionEncoderError(throwable)) {
            return ModelVisionSupport.visionUnsupportedMessage()
        }
        val raw = throwable.message?.takeIf { it.isNotBlank() } ?: return "Couldn't start the conversation"
        return if (raw.contains("Status code 13") || raw.contains("large input", ignoreCase = true)) {
            "Chat history is too long for this model's context window. " +
                "Start a new chat or compact the conversation."
        } else if (raw.length > 200) {
            "Couldn't start the conversation. Try reopening the chat or reloading the model."
        } else {
            raw
        }
    }

    private fun friendlyError(error: InferenceError): String = when (error) {
        is InferenceError.OutOfMemory -> "Not enough free memory. Close other apps and retry."
        is InferenceError.ModelFileMissing -> "Model file missing. Re-download the model."
        is InferenceError.BackendUnavailable -> "No supported backend on this device."
        is InferenceError.LoadFailed -> error.message ?: "Failed to load model"
        is InferenceError.GenerationFailed -> error.message ?: "Generation failed"
        is InferenceError.UnsupportedFormat ->
            error.message ?: "${error.format} files aren't supported by the on-device runtime yet."
        InferenceError.NoModelLoaded -> "No model loaded"
    }

    private fun updateInferenceSettings(settings: InferenceSettings) {
        TinyLog.i(
            TAG,
            "JOURNEY: inference settings updated — temp=${settings.temperature}, topK=${settings.topK}, " +
                "topP=${settings.topP}, systemInstruction=\"${settings.systemInstruction}\" — rebuilding session"
        )
        // Reflect the saved settings in the UI state immediately so the sheet
        // shows them (not defaults) the next time it is opened.
        _uiState.update { it.copy(inferenceSettings = settings) }
        val chatId = _uiState.value.activeChatId ?: return
        viewModelScope.launch(Dispatchers.Default) {
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
