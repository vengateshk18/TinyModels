package com.ai.tinymodels.feature.home

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.R
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.repository.ChatRepository
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.repository.SettingsRepository
import com.ai.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.ai.tinymodels.domain.usecase.model.DownloadPaths
import com.ai.tinymodels.domain.usecase.model.DownloadState
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import com.ai.tinymodels.feature.home.model.DownloadedModelInfo
import com.ai.tinymodels.feature.home.model.HomeUiState
import com.ai.tinymodels.feature.home.model.LastChatSummary
import com.ai.tinymodels.feature.home.model.RecommendedModel
import com.ai.tinymodels.feature.home.model.StorageBreakdown
import com.ai.tinymodels.feature.home.model.UsageStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Home tab. Branches the UI into two states:
 *  - [HomeUiState.FirstTime] — no models downloaded yet (onboarding).
 *  - [HomeUiState.Dashboard] — at least one model downloaded (launchpad).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelRepository: ModelRepository,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val downloadUseCase: DownloadModelUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Progress of the recommended-model download (FirstTime state only). */
    private val _recommendedDownload = MutableStateFlow<DownloadState?>(null)
    val recommendedDownload: StateFlow<DownloadState?> = _recommendedDownload.asStateFlow()

    /**
     * True when the recommended file was stopped with its partial bytes kept.
     * The card shows Resume / Discard instead of a fresh Download.
     */
    private val _recommendedPaused = MutableStateFlow(false)
    val recommendedPaused: StateFlow<Boolean> = _recommendedPaused.asStateFlow()

    /** Bytes of the recommended file already on disk while paused. */
    private val _recommendedPausedBytes = MutableStateFlow(0L)
    val recommendedPausedBytes: StateFlow<Long> = _recommendedPausedBytes.asStateFlow()

    /** One-shot error surfaced as a snackbar, with friendly copy. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            // Two refresh triggers, because the dashboard shows BOTH kinds of data:
            //  - model downloads (the FirstTime ↔ Dashboard branch),
            //  - chat activity (last chat card, usage stats, compactions…).
            // Without the chat trigger the dashboard kept stale numbers after
            // the user created chats in the Chat tab and came back to Home.
            val chatActivitySignature = chatRepository.observeChatSummaries()
                .map { summaries -> summaries.size.toLong() * 1_000_000_000L + summaries.sumOf { it.updatedAt } }
                .distinctUntilChanged()

            combine(
                modelRepository.observeDownloadedModels(),
                chatActivitySignature
            ) { models, _ ->
                models.isNotEmpty()
            }.collect { hasModels ->
                refreshState(hasModels)
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    /** Starts (or observes) the download of the recommended model. */
    fun downloadRecommended(model: RecommendedModel) {
        launchRecommendedDownload(model)
    }

    /** Pauses the in-flight download, keeping the partial bytes for resume. */
    fun pauseRecommendedDownload(model: RecommendedModel) {
        downloadUseCase.cancelFile(model.modelId, model.fileName)
        viewModelScope.launch {
            val current = modelRepository.getModelFile(model.modelId, model.fileName)?.status
            if (current !in setOf(FileDownloadStatus.DOWNLOADED, FileDownloadStatus.PAUSED)) {
                modelRepository.updateFileStatus(
                    model.modelId,
                    model.fileName,
                    FileDownloadStatus.PAUSED
                )
            }
            _recommendedPausedBytes.value = partialBytes(model)
            _recommendedPaused.value = true
            _recommendedDownload.value = DownloadState()
        }
    }

    /** Re-enqueues a paused download, appending to the partial bytes on disk. */
    fun resumeRecommendedDownload(model: RecommendedModel) {
        _recommendedPaused.value = false
        _recommendedPausedBytes.value = 0L
        viewModelScope.launch {
            modelRepository.updateFileStatus(
                model.modelId,
                model.fileName,
                FileDownloadStatus.DOWNLOADING
            )
            launchRecommendedDownload(model, forceEnqueue = true)
        }
    }

    /**
     * Throws away the download after confirmation: waits for the worker to
     * settle, deletes the partial bytes, and resets the tracking row.
     */
    fun discardRecommendedDownload(model: RecommendedModel) {
        viewModelScope.launch(Dispatchers.Default) {
            downloadUseCase.cancelFileAndWait(model.modelId, model.fileName)
            downloadUseCase.discardPartial(model.modelId, model.fileName)
            modelRepository.updateFileStatus(
                model.modelId,
                model.fileName,
                FileDownloadStatus.NOT_DOWNLOADED
            )
            _recommendedPaused.value = false
            _recommendedPausedBytes.value = 0L
            _recommendedDownload.value = DownloadState()
        }
    }

    private fun launchRecommendedDownload(model: RecommendedModel, forceEnqueue: Boolean = false) {
        viewModelScope.launch(Dispatchers.Default) {
            // Observe an already-running download for this file, if any.
            if (!forceEnqueue) {
                val existing = downloadUseCase.observeExistingFile(model.modelId, model.fileName)
                if (existing != null) {
                    existing.collect { _recommendedDownload.value = it }
                    return@launch
                }
            }
            val details = minimalModelDetails(model)
            try {
                downloadUseCase.executeFile(details, model.fileName, model.sizeBytes, replace = forceEnqueue)
                    .collect { state ->
                        _recommendedDownload.value = state
                        if (state.status == DownloadStatus.FAILED) {
                            _error.value = friendlyDownloadError(state.error)
                        }
                    }
            } catch (t: Throwable) {
                _error.value = friendlyDownloadError(t.message)
            }
        }
    }

    private fun minimalModelDetails(model: RecommendedModel) = ModelDetails(
        id = model.modelId,
        author = model.modelId.substringBefore('/'),
        pipelineTag = "text-generation",
        libraryName = "litertlm",
        tags = emptyList(),
        downloads = null,
        likes = null,
        lastModified = null,
        createdAt = null,
        siblings = listOf(model.fileName),
        usedStorage = null,
        sha = null,
        gated = "false",
        disabled = false,
        widgetPrompts = emptyList(),
        baseModel = null
    )

    /** Bytes of the in-progress file left on disk for this recommendation. */
    private fun partialBytes(model: RecommendedModel): Long =
        runCatching {
            DownloadPaths.partFile(context.filesDir, model.modelId, model.fileName).length()
        }.getOrDefault(0L)

    /**
     * Friendly copy for a failed recommended-model download. Worker errors keep
     * their specific reason (auth, storage) when present; otherwise fall back to
     * a connection-oriented message since these models are ungated.
     */
    private fun friendlyDownloadError(raw: String?): String {
        if (raw.isNullOrBlank()) {
            return context.getString(R.string.home_download_failed)
        }
        // Keep actionable auth/storage messages as-is.
        if (raw.contains("401") || raw.contains("403") || raw.contains("storage", ignoreCase = true)) {
            return raw
        }
        return context.getString(R.string.home_download_failed)
    }

    // ---- State assembly ----

    private suspend fun refreshState(hasModels: Boolean) {
        val device = collectDeviceInfo()
        val freeStorageBytes = freeStorageBytes()

        if (!hasModels) {
            val recommended = RecommendedModels.pickForDevice(
                availableRamForAiMb = device.availableRamForAiMb,
                freeStorageBytes = freeStorageBytes
            )
            // A previous session may have left the file paused with partial bytes;
            // surface it as resumable rather than a fresh download.
            val file = modelRepository.observeModelFiles(recommended.modelId).first()
                .firstOrNull { it.fileName == recommended.fileName }
            val paused = file?.status == FileDownloadStatus.PAUSED
            _recommendedPaused.value = paused
            _recommendedPausedBytes.value = if (paused) partialBytes(recommended) else 0L
            _uiState.value = HomeUiState.FirstTime(
                device = device,
                recommended = recommended,
                freeStorageBytes = freeStorageBytes
            )
            return
        }

        // ---- Dashboard ----
        // "Your models", newest first — needed both for the launch card and
        // the model list, so it is assembled before the launch card.
        val lastUsedModelId = settingsRepository.getLastUsedModelId()
        val filesByModel = modelRepository.observeDownloadedFiles().first()
            .groupBy { it.modelId }
        val models = modelRepository.observeDownloadedModels().first()
            .sortedByDescending { it.downloadedAt }
            .map { m ->
                val files = filesByModel[m.modelId].orEmpty()
                // For imported models (author == "imported"), use the first file's
                // filename without extension as the display name. For HF models,
                // use the model name (part after the last '/').
                val displayName = if (m.author == "imported") {
                    files.firstOrNull()?.fileName?.removeSuffix(".litertlm")
                        ?: m.modelId.substringAfterLast('/')
                } else {
                    m.modelId.substringAfterLast('/')
                }
                DownloadedModelInfo(
                    modelId = m.modelId,
                    displayName = displayName,
                    sizeBytes = files.sumOf { it.sizeBytes },
                    fileCount = files.size,
                    downloadedAt = m.downloadedAt,
                    isLastUsed = m.modelId == lastUsedModelId
                )
            }

        // Fresh-install flow: no chat has been opened yet, so nothing is
        // "last used". Point the launchpad at the newest downloaded model —
        // that is what the first card must offer to start a chat with.
        val lastUsedModel =
            lastUsedModelId?.let { modelRepository.getDownloadedModel(it) }
                ?: models.firstOrNull()?.let { modelRepository.getDownloadedModel(it.modelId) }
        val lastUsedFileName = lastUsedModel?.let {
            modelRepository.getDownloadedFileForModel(it.modelId)?.fileName
        }

        // Most recently active chat (summaries are ordered by updatedAt DESC).
        val lastChat = chatRepository.observeChatSummaries().first()
            .firstOrNull()
            ?.let {
                LastChatSummary(
                    id = it.id,
                    title = it.title,
                    updatedAt = it.updatedAt,
                    contextTokens = 2048
                )
            }

        val usage = UsageStats(
            totalChats = chatRepository.totalChats(),
            totalTokens = chatRepository.totalTokensGenerated(),
            modelsTried = chatRepository.modelsTried(),
            totalMessages = chatRepository.totalMessages(),
            chatsThisWeek = chatRepository.chatsSince(
                System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            ),
            compactionsRun = chatRepository.totalCompactions(),
            webSearchChats = chatRepository.webSearchChats(),
            favoriteModel = chatRepository.favoriteModelId()?.substringAfterLast('/')
        )

        val storage = StorageBreakdown(
            modelBytes = modelRepository.observeDownloadedFiles().first()
                .sumOf { it.sizeBytes },
            freeBytes = freeStorageBytes()
        )

        _uiState.value = HomeUiState.Dashboard(
            device = device,
            lastUsedModel = lastUsedModel,
            lastUsedFileName = lastUsedFileName,
            lastChat = lastChat,
            usage = usage,
            storage = storage,
            tokensPerSecond = chatRepository.getLastSessionTokensPerSecond(),
            models = models
        )
    }

    private fun collectDeviceInfo(): DeviceInfoState {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalRamMb = mi.totalMem / (1024 * 1024)
        val availableRamMb = mi.availMem / (1024 * 1024)

        val stat = StatFs(context.filesDir.absolutePath)
        val freeStorageMb = stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024)

        val cpuCores = Runtime.getRuntime().availableProcessors()
        val arch = Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val availableRamForAiMb = (totalRamMb * 0.45).toLong()

        val (capability, maxParams) = AiCapabilityClassifier.classify(totalRamMb)

        return DeviceInfoState(
            deviceName = deviceName,
            totalRamMb = totalRamMb,
            availableRamMb = availableRamMb,
            freeStorageMb = freeStorageMb,
            cpuCores = cpuCores,
            arch = arch,
            aiCapabilityLevel = capability,
            recommendedMaxParams = maxParams,
            availableRamForAiMb = availableRamForAiMb
        )
    }

    private fun freeStorageBytes(): Long {
        val stat = StatFs(context.filesDir.absolutePath)
        return stat.availableBlocksLong * stat.blockSizeLong
    }
}
