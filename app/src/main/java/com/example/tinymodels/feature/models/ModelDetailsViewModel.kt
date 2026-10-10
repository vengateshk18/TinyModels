package com.example.tinymodels.feature.models

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.example.tinymodels.core.common.AppError
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.core.common.MemoryUtils
import com.example.tinymodels.core.common.StorageUtils
import com.example.tinymodels.core.network.NetworkErrorMapper
import com.example.tinymodels.core.network.NetworkMonitor
import com.example.tinymodels.domain.model.DownloadedModelFile
import com.example.tinymodels.domain.model.FileDownloadStatus
import com.example.tinymodels.domain.model.ModelDetails
import com.example.tinymodels.domain.repository.ModelRepository
import com.example.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.example.tinymodels.domain.usecase.model.DownloadState
import com.example.tinymodels.domain.usecase.model.DownloadStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val selectedFile: String? = null,
        val fileDownload: DownloadState = DownloadState(),
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
        observeModelFiles()
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
        _uiState.update { 
            it.copy(
                download = DownloadState(),
                fileDownload = DownloadState(),
                selectedFile = null
            ) 
        }
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

    private fun observeModelFiles() {
        viewModelScope.launch {
            modelRepository.observeModelFiles(modelId).collect { files ->
                _uiState.update { it.copy(modelFiles = files) }
                // Resume any in-flight download
                resumeFileDownload(files)
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

    private fun resumeFileDownload(files: List<DownloadedModelFile>) {
        // Find any DOWNLOADING file and bind to its WorkManager job
        val downloading = files.firstOrNull { it.status == FileDownloadStatus.DOWNLOADING }
        if (downloading != null) {
            val existing = downloadModel.observeExistingFile(modelId, downloading.fileName)
            if (existing != null) {
                downloadJob?.cancel()
                downloadJob = viewModelScope.launch {
                    existing.collect { state -> _uiState.update { it.copy(fileDownload = state) } }
                }
                _uiState.update { it.copy(selectedFile = downloading.fileName) }
            }
        }
    }

    /** If a download for this model is already running (e.g. screen re-entry), bind to it. */
    private fun resumeActiveDownload() {
        val existing = downloadModel.observeExisting(modelId) ?: return
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            existing.collect { state -> _uiState.update { it.copy(download = state) } }
        }
    }

    fun onFileSelected(fileName: String) {
        val status = _uiState.value.getFileStatus(fileName)
        when (status) {
            FileDownloadStatus.DOWNLOADED -> {
                // User tapped a downloaded file - handled by UI navigation
            }
            FileDownloadStatus.DOWNLOADING -> {
                // Bind to existing download
                val existing = downloadModel.observeExistingFile(modelId, fileName)
                if (existing != null) {
                    downloadJob?.cancel()
                    downloadJob = viewModelScope.launch {
                        existing.collect { state -> _uiState.update { it.copy(fileDownload = state) } }
                    }
                }
                _uiState.update { it.copy(selectedFile = fileName) }
            }
            FileDownloadStatus.NOT_DOWNLOADED, FileDownloadStatus.FAILED -> {
                // Select this file for download
                _uiState.update { it.copy(selectedFile = fileName, fileDownload = DownloadState()) }
            }
        }
    }

    fun onDownloadFileClick() {
        val model = _uiState.value.model ?: return
        val fileName = _uiState.value.selectedFile ?: return
        val size = _uiState.value.getFileSize(fileName)

        // Storage pre-check
        // Storage pre-check
        if (size > 0 && !storageUtils.hasSpaceFor(size)) {
            _uiState.update {
                it.copy(
                    fileDownload = DownloadState(
                        status = DownloadStatus.FAILED,
                        error = "Not enough free storage for this file."
                    )
                )
            }
            return
        }

        // Update DB status to DOWNLOADING
        viewModelScope.launch {
            modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.DOWNLOADING)
        }

        // Start download
        _uiState.update { it.copy(fileDownload = DownloadState(status = DownloadStatus.CHECKING_SIZE, totalBytes = size)) }
        
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            downloadModel.executeFile(model, fileName, size).collect { state ->
                _uiState.update { it.copy(fileDownload = state) }
                // On completion, update DB status
                if (state.status == DownloadStatus.COMPLETED) {
                    modelRepository.updateFileStatus(
                        modelId, fileName, FileDownloadStatus.DOWNLOADED,
                        sizeBytes = state.totalBytes
                    )
                } else if (state.status == DownloadStatus.FAILED) {
                    modelRepository.updateFileStatus(
                        modelId, fileName, FileDownloadStatus.FAILED,
                        error = state.error
                    )
                }
            }
        }
    }

    fun onCancelFileDownload() {
        val fileName = _uiState.value.selectedFile ?: return
        downloadModel.cancelFile(modelId, fileName)
        downloadJob?.cancel()
        downloadJob = null
        viewModelScope.launch {
            // Reset this file back to NOT_DOWNLOADED
            modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.NOT_DOWNLOADED)
            // If no other files are fully downloaded, remove the parent model entry entirely
            // so the model does not appear in the "Downloaded" section
            val hasAnyDownloaded = _uiState.value.modelFiles.any {
                it.fileName != fileName && it.status == FileDownloadStatus.DOWNLOADED
            }
            if (!hasAnyDownloaded) {
                modelRepository.deleteDownloadedModel(modelId)
            }
        }
        _uiState.update {
            it.copy(
                fileDownload = DownloadState(status = DownloadStatus.IDLE),
                selectedFile = null
            )
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
