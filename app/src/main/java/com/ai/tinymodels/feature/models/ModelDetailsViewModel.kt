package com.ai.tinymodels.feature.models

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.MemoryUtils
import com.ai.tinymodels.core.common.StorageUtils
import com.ai.tinymodels.core.network.NetworkErrorMapper
import com.ai.tinymodels.core.network.NetworkMonitor
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.ai.tinymodels.domain.usecase.model.DownloadState
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import com.ai.tinymodels.domain.usecase.model.FileRowPhase
import com.ai.tinymodels.domain.usecase.model.FileRowState
import com.ai.tinymodels.domain.usecase.model.fileRowState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val modelRepository: ModelRepository,
    private val downloadModel: DownloadModelUseCase,
    private val storageUtils: StorageUtils,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val modelId: String = checkNotNull(savedStateHandle["modelId"])

    data class UiState(
        val isLoading: Boolean = true,
        val model: ModelDetails? = null,
        /** Typed error from the last failed load — drives offline vs error UI. */
        val error: AppError? = null,
        val isDownloaded: Boolean = false,
        val download: DownloadState = DownloadState(),
        // Per-file state
        val modelFiles: List<DownloadedModelFile> = emptyList(),
        val fileSizes: Map<String, Long> = emptyMap(),
        /** Render-ready state for every file row, keyed by file name. */
        val fileStates: Map<String, FileRowState> = emptyMap(),
        /** Total device RAM — used for the per-file capacity indicator. */
        val deviceRamBytes: Long = 0L
    ) {
        /** Best-known total size: model repo size, else what the worker reports. */
        val totalSizeBytes: Long
            get() = model?.usedStorage?.takeIf { it > 0 } ?: download.totalBytes
        
        /** Get status of a specific file. */
        fun getFileStatus(fileName: String): FileDownloadStatus =
            modelFiles.firstOrNull { it.fileName == fileName }?.status ?: FileDownloadStatus.NOT_DOWNLOADED
        
        /** Get size of a specific file (0 if not yet calculated). */
        fun getFileSize(fileName: String): Long = fileSizes[fileName] ?: 0L

        /** Live state of one file row; idle until the first DB emission. */
        fun fileState(fileName: String): FileRowState =
            fileStates[fileName] ?: FileRowState(FileRowPhase.IDLE)

        /** Last error recorded for a failed file, if any. */
        fun fileError(fileName: String): String? =
            modelFiles.firstOrNull { it.fileName == fileName }?.error

        /**
         * True when this file can realistically be loaded and used on this
         * device (based on total RAM). Unknown sizes are assumed loadable.
         */
        fun isFileLoadable(fileName: String): Boolean =
            MemoryUtils.canLoadModelOfSize(getFileSize(fileName), deviceRamBytes)

        /** Friendly, user-presentable copy for [error]. */
        val errorMessage: String?
            get() = error?.let(NetworkErrorMapper::friendlyMessage)

        /** True when the current error is simply "no connection". */
        val isOfflineError: Boolean
            get() = error is AppError.NoConnection
    }

    private val _uiState = MutableStateFlow(UiState(deviceRamBytes = MemoryUtils.totalRamBytes(appContext)))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    init {
        load()
        observeDownloaded()
        observeFileStates()
        resumeActiveDownload()
        observeConnectivity()
    }

    /** Tracks connectivity and auto-retries the last failed load on reconnect. */
    private fun observeConnectivity() {
        viewModelScope.launch {
            var wasOnline: Boolean? = null
            networkMonitor.isOnline.collect { online ->
                if (wasOnline == false && online && _uiState.value.error != null) {
                    load()
                }
                wasOnline = online
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        downloadJob = null
        // Reset state to prevent stale UI
        _uiState.update { it.copy(download = DownloadState()) }
    }

    fun load() {
        viewModelScope.launch {
            // Fail fast when the device is offline — details need the HF API.
            if (!networkMonitor.isOnline.value) {
                _uiState.update {
                    it.copy(isLoading = false, error = AppError.NoConnection())
                }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = modelRepository.getModelDetails(modelId)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, model = result.data) }
                    // Pre-register model files for per-file tracking
                    modelRepository.preRegisterModel(result.data)
                    // Calculate per-file sizes in parallel
                    calculateFileSizes(result.data)
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(isLoading = false, error = result.error)
                }
            }
        }
    }

    private fun observeDownloaded() {
        viewModelScope.launch {
            modelRepository.observeDownloadedModels().collect { models ->
                _uiState.update { it.copy(isDownloaded = models.any { m -> m.modelId == modelId }) }
            }
        }
    }

    /**
     * Merges persisted status with every live WorkManager job for this model.
     *
     * This is the whole reason progress survives navigation: the jobs outlive
     * the ViewModel, and this flow re-attaches to all of them on re-entry —
     * not just the one the user last tapped.
     */
    private fun observeFileStates() {
        viewModelScope.launch {
            combine(
                modelRepository.observeModelFiles(modelId),
                downloadModel.observeActiveDownloads()
            ) { files, active ->
                val activeByFile = active
                    .filter { it.modelId == modelId && it.fileName != null }
                    .associateBy { it.fileName!! }
                files to files.associate { file ->
                    file.fileName to fileRowState(file.status, activeByFile[file.fileName])
                }
            }.collect { (files, states) ->
                _uiState.update { it.copy(modelFiles = files, fileStates = states) }
            }
        }
    }

    private fun calculateFileSizes(model: ModelDetails) {
        viewModelScope.launch {
            // Each size is a HEAD request — skip entirely when offline.
            // Rows keep showing "—" until a later successful load fills them in.
            if (!networkMonitor.isOnline.value) return@launch
            val files = model.runtimeFiles.ifEmpty { model.liteRtFiles }
            val sizes = mutableMapOf<String, Long>()
            files.forEach { fileName ->
                try {
                    val size = downloadModel.calculateFileSize(model, fileName)
                    sizes[fileName] = size
                    _uiState.update { it.copy(fileSizes = sizes.toMap()) }
                } catch (_: Exception) {
                    // Size calculation failed, keep as 0
                }
            }
        }
    }

    /** If a whole-model download is already running (e.g. screen re-entry), bind to it. */
    private fun resumeActiveDownload() {
        val existing = downloadModel.observeExisting(modelId) ?: return
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            existing.collect { state -> _uiState.update { it.copy(download = state) } }
        }
    }

    /**
     * Starts (or resumes) one file. Resuming is the same call as starting: the
     * worker finds the `.part` file and asks the server to continue from it.
     */
    fun onDownloadFileClicked(fileName: String) {
        val model = _uiState.value.model ?: return
        val size = _uiState.value.getFileSize(fileName)
        viewModelScope.launch {
            if (size > 0 && !storageUtils.hasSpaceFor(size)) {
                modelRepository.updateFileStatus(
                    modelId, fileName, FileDownloadStatus.FAILED,
                    error = "Not enough free storage for this file."
                )
                return@launch
            }
            modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.DOWNLOADING)
            // Enqueueing is the side effect; progress is observed centrally.
            downloadModel.executeFile(model, fileName, size)
        }
    }

    fun onResumeFile(fileName: String) = onDownloadFileClicked(fileName)

    /**
     * Stops the job but keeps the partial file. The worker also writes PAUSED
     * when it unwinds; writing it here too makes the row respond immediately.
     */
    fun onPauseFile(fileName: String) {
        downloadModel.cancelFile(modelId, fileName)
        viewModelScope.launch {
            modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.PAUSED)
        }
    }

    /** Throws away a paused/failed partial download and frees its disk space. */
    fun onDiscardFile(fileName: String) {
        downloadModel.cancelFile(modelId, fileName)
        viewModelScope.launch {
            downloadModel.discardPartial(modelId, fileName)
            modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.NOT_DOWNLOADED)
            val stillUsed = _uiState.value.modelFiles.any { file ->
                file.fileName != fileName && file.status in setOf(
                    FileDownloadStatus.DOWNLOADED,
                    FileDownloadStatus.PAUSED,
                    FileDownloadStatus.DOWNLOADING
                )
            }
            if (!stillUsed) modelRepository.deleteDownloadedModel(modelId)
        }
    }

    fun onDownloadedFileClick(fileName: String) {
        // Navigation handled by screen
    }

    fun onDownloadClick() {
        val model = _uiState.value.model ?: return
        val current = _uiState.value.download

        // Toggle: tapping while active cancels with proper cleanup
        if (current.isDownloading) {
            downloadModel.cancel(model.id)
            viewModelScope.launch {
                // Give WorkManager time to cancel
                kotlinx.coroutines.delay(500)
                _uiState.update {
                    it.copy(download = DownloadState(status = DownloadStatus.IDLE))
                }
            }
            return
        }

        // Use the repo size (usedStorage) as the known total; fall back to a HEAD-based estimate
        // only when the API didn't provide one. Never block the button on network.
        val total = model.usedStorage?.takeIf { it > 0 } ?: 0L

        // Storage pre-check (only when we know the size).
        if (total > 0 && !storageUtils.hasSpaceFor(total)) {
            _uiState.update {
                it.copy(
                    download = DownloadState(
                        status = DownloadStatus.FAILED,
                        error = "Not enough free storage for this model."
                    )
                )
            }
            return
        }

        // Immediately reflect that we're starting so the UI never looks dead.
        _uiState.update { it.copy(download = DownloadState(status = DownloadStatus.CHECKING_SIZE, totalBytes = total)) }

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            downloadModel.execute(model, total).collect { state ->
                _uiState.update { it.copy(download = state) }
            }
        }
    }
}
