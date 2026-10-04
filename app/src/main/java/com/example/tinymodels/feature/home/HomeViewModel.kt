package com.example.tinymodels.feature.home

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.domain.model.ModelDetails
import com.example.tinymodels.domain.repository.ChatRepository
import com.example.tinymodels.domain.repository.ModelRepository
import com.example.tinymodels.domain.repository.SettingsRepository
import com.example.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.example.tinymodels.domain.usecase.model.DownloadState
import com.example.tinymodels.domain.usecase.model.DownloadStatus
import com.example.tinymodels.feature.home.model.DownloadedModelInfo
import com.example.tinymodels.feature.home.model.HomeUiState
import com.example.tinymodels.feature.home.model.LastChatSummary
import com.example.tinymodels.feature.home.model.RecommendedModel
import com.example.tinymodels.feature.home.model.StorageBreakdown
import com.example.tinymodels.feature.home.model.UsageStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    /** One-shot error surfaced as a snackbar, with friendly copy. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            modelRepository.observeDownloadedModels().collect { models ->
                refreshState(models.isNotEmpty())
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    /** Starts (or observes) the download of the recommended model. */
    fun downloadRecommended(model: RecommendedModel) {
        viewModelScope.launch {
            // Observe an already-running download for this file, if any.
            val existing = downloadUseCase.observeExistingFile(model.modelId, model.fileName)
            if (existing != null) {
                existing.collect { _recommendedDownload.value = it }
                return@launch
            }
            // Build a minimal ModelDetails payload for the download pipeline.
            val details = ModelDetails(
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
            try {
                downloadUseCase.executeFile(details, model.fileName, model.sizeBytes)
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

    /** Cancels the recommended-model download. */
    fun cancelRecommendedDownload(model: RecommendedModel) {
        downloadUseCase.cancelFile(model.modelId, model.fileName)
    }

    /**
     * Friendly copy for a failed recommended-model download. Worker errors keep
     * their specific reason (auth, storage) when present; otherwise fall back to
     * a connection-oriented message since these models are ungated.
     */
    private fun friendlyDownloadError(raw: String?): String {
        if (raw.isNullOrBlank()) {
            return "Download failed. Check your connection and retry."
        }
        // Keep actionable auth/storage messages as-is.
        if (raw.contains("401") || raw.contains("403") || raw.contains("storage", ignoreCase = true)) {
            return raw
        }
        return "Download failed. Check your connection and retry."
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
            _uiState.value = HomeUiState.FirstTime(
                device = device,
                recommended = recommended,
                freeStorageBytes = freeStorageBytes
            )
            return
        }

        // ---- Dashboard ----
        val lastUsedModelId = settingsRepository.getLastUsedModelId()
        val lastUsedModel = lastUsedModelId?.let { modelRepository.getDownloadedModel(it) }
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
            modelsTried = chatRepository.modelsTried()
        )

        val storage = StorageBreakdown(
            modelBytes = modelRepository.observeDownloadedFiles().first()
                .sumOf { it.sizeBytes },
            freeBytes = freeStorageBytes()
        )

        // All downloaded models with their total sizes, newest first.
        val filesByModel = modelRepository.observeDownloadedFiles().first()
            .groupBy { it.modelId }
        val models = modelRepository.observeDownloadedModels().first()
            .sortedByDescending { it.downloadedAt }
            .map { m ->
                val files = filesByModel[m.modelId].orEmpty()
                DownloadedModelInfo(
                    modelId = m.modelId,
                    displayName = m.modelId.substringAfterLast('/'),
                    sizeBytes = files.sumOf { it.sizeBytes },
                    fileCount = files.size,
                    downloadedAt = m.downloadedAt,
                    isLastUsed = m.modelId == lastUsedModelId
                )
            }

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
        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val availableRamForAiMb = (totalRamMb * 0.45).toLong()

        val (capability, maxParams) = when {
            totalRamMb >= 12000 -> AiCapability.EXCELLENT to "8B"
            totalRamMb >= 8000 -> AiCapability.GOOD to "3B"
            totalRamMb >= 4000 -> AiCapability.LIMITED to "1B"
            else -> AiCapability.UNKNOWN to "-"
        }

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
