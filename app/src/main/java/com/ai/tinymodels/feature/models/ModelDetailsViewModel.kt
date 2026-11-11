package com.ai.tinymodels.feature.models

import androidx.lifecycle.SavedStateHandle
import androidx.annotation.StringRes
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
import com.ai.tinymodels.domain.usecase.model.DownloadPaths
import com.ai.tinymodels.domain.usecase.model.DownloadState
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import com.ai.tinymodels.domain.usecase.model.FileRowPhase
import com.ai.tinymodels.domain.usecase.model.FileRowState
import com.ai.tinymodels.domain.usecase.model.FileSizeStatus
import com.ai.tinymodels.domain.usecase.model.fileRowState
import dagger.hilt.android.lifecycle.HiltViewModel
import com.ai.tinymodels.R
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
        /** Per-file size resolution: still asking, known, or undetermined. */
        val fileSizeStatuses: Map<String, FileSizeStatus> = emptyMap(),
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

        /**
         * Whether [fileName]'s size is still being fetched, known, or cannot be
         * determined. Until the first resolution every file is LOADING, so a
         * row never has to guess from a zero byte count.
         */
        fun fileSizeStatus(fileName: String): FileSizeStatus =
            fileSizeStatuses[fileName] ?: FileSizeStatus.LOADING

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

        /** Friendly, user-presentable copy for [error], as a localized resource. */
        @get:StringRes
        val errorMessageRes: Int?
            get() = error?.let(NetworkErrorMapper::friendlyMessageRes)

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
                    // Only unfinished files can have bytes on disk. The `.part`
                    // path is a single File.length() — no dedicated dispatcher
                    // needed for a stat.
                    val partialBytes = if (activeByFile[file.fileName] == null &&
                        file.status in setOf(FileDownloadStatus.PAUSED, FileDownloadStatus.FAILED)
                    ) {
                        runCatching {
                            DownloadPaths.partFile(appContext.filesDir, modelId, file.fileName).length()
                        }.getOrDefault(0L)
                    } else {
                        0L
                    }
                    file.fileName to fileRowState(
                        dbStatus = file.status,
                        active = activeByFile[file.fileName],
                        partialBytes = partialBytes,
                        totalBytes = _uiState.value.getFileSize(file.fileName)
                    )
                }
            }.collect { (files, states) ->
                _uiState.update { it.copy(modelFiles = files, fileStates = states) }
            }
        }
    }

    /**
     * HEAD-requests each file's size in turn, publishing Known/Unknown per file
     * as it goes. Sizes already resolved are skipped — there is no reason to
     * ask the server a second time, and the row only needs it once.
     */
    private fun calculateFileSizes(model: ModelDetails) {
        val files = model.runtimeFiles.ifEmpty { model.liteRtFiles }

        // Each size is a HEAD request — no network means no size, so say so
        // instead of leaving the row on "Loading…" forever.
        if (!networkMonitor.isOnline.value) {
            val current = _uiState.value
            _uiState.update {
                it.copy(
                    fileSizeStatuses = files.associateWith { fileName ->
                        if (current.fileSizeStatus(fileName) == FileSizeStatus.KNOWN) {
                            FileSizeStatus.KNOWN
                        } else {
                            FileSizeStatus.UNKNOWN
                        }
                    }
                )
            }
            return
        }

        viewModelScope.launch {
            files.forEach { fileName ->
                // Already resolved in this session — don't re-ask the server.
                if (_uiState.value.fileSizeStatus(fileName) != FileSizeStatus.LOADING) return@forEach
                try {
                    val size = downloadModel.calculateFileSize(model, fileName)
                    _uiState.update {
                        it.copy(
                            fileSizes = it.fileSizes + (fileName to size),
                            fileSizeStatuses = it.fileSizeStatuses + (fileName to FileSizeStatus.KNOWN)
                        )
                    }
                } catch (_: Exception) {
                    _uiState.update {
                        it.copy(
                            fileSizeStatuses = it.fileSizeStatuses + (fileName to FileSizeStatus.UNKNOWN)
                        )
                    }
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
    fun onDownloadFileClicked(fileName: String) = startFileDownload(fileName, replace = false)

    fun onResumeFile(fileName: String) = startFileDownload(fileName, replace = true)

    private fun startFileDownload(fileName: String, replace: Boolean) {
        val model = _uiState.value.model ?: return
        viewModelScope.launch {
            // The background size sweep may not have reached this file yet; a
            // missing size would make the row's progress bar meaningless.
            val size = _uiState.value.getFileSize(fileName).takeIf { it > 0 }
                ?: runCatching { downloadModel.calculateFileSize(model, fileName) }
                    .getOrDefault(0L)
            if (size > 0 && !storageUtils.hasSpaceFor(size)) {
                modelRepository.updateFileStatus(
                    modelId, fileName, FileDownloadStatus.FAILED,
                    error = appContext.getString(R.string.model_details_error_not_enough_storage_file)
                )
                return@launch
            }
            modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.DOWNLOADING)
            // Enqueueing is the side effect; progress is observed centrally.
            downloadModel.executeFile(model, fileName, size, replace)
        }
    }

    /**
     * Stops the job but keeps the partial file. The worker also writes PAUSED
     * when it unwinds; writing it here too makes the row respond immediately.
     */
    fun onPauseFile(fileName: String) {
        downloadModel.cancelFile(modelId, fileName)
        viewModelScope.launch {
            // The last chunk may have landed before the cancel did — never
            // downgrade a finished file to Paused.
            val current = modelRepository.getModelFile(modelId, fileName)?.status
            if (current != FileDownloadStatus.DOWNLOADED) {
                modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.PAUSED)
            }
        }
    }

    /** Throws away a paused/failed partial download and frees its disk space. */
    fun onDiscardFile(fileName: String) {
        downloadModel.cancelFile(modelId, fileName)
        viewModelScope.launch {
            downloadModel.discardPartial(modelId, fileName)
            // The tracking rows stay. They are the only thing the row's live
            // progress attaches to — deleting them would make the next download
            // run (and show in the notification) while the row looks idle.
            modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.NOT_DOWNLOADED)
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
                        error = appContext.getString(R.string.model_details_error_not_enough_storage_model)
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
