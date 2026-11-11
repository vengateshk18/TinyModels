package com.ai.tinymodels.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.core.inference.ModelManager
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.usecase.model.ActiveDownload
import com.ai.tinymodels.domain.usecase.model.DownloadModelUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DownloadedModelsViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val modelManager: ModelManager,
    private val downloadUseCase: DownloadModelUseCase
) : ViewModel() {

    /** Parent metadata (one row per model) for the downloaded list. */
    val models: StateFlow<List<DownloadedModel>> =
        modelRepository.observeDownloadedModels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All DOWNLOADED files (children), grouped by model in the UI.
     *
     * Only files still present on disk are shown: if the underlying
     * `.litertlm` was deleted outside the app (e.g. via Files), the stale
     * DB row is hidden instead of offered for chat.
     */
    val downloadedFiles: StateFlow<List<DownloadedModelFile>> =
        modelRepository.observeDownloadedFiles()
            .map { files -> files.filter { it.isFileAvailable() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Live download jobs (RUNNING/ENQUEUED) straight from WorkManager — the
     * "Active downloads" section updates from this without any per-screen
     * binding.
     */
    val activeDownloads: StateFlow<List<ActiveDownload>> =
        downloadUseCase.observeActiveDownloads()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Downloads the user stopped, partial bytes kept — resumable rows the
     * section renders alongside the active jobs.
     */
    val pausedFiles: StateFlow<List<DownloadedModelFile>> =
        modelRepository.observePausedFiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Stops the job and keeps the partial bytes (resume path). The worker
     * marks in-flight files PAUSED on its way down; we write it immediately so
     * the row responds at once (never downgrading a finished file).
     */
    fun pauseDownload(download: ActiveDownload) {
        downloadUseCase.cancelActive(download.workId)
        viewModelScope.launch {
            val files = modelRepository.observeModelFiles(download.modelId).first()
            files.filter {
                (download.fileName == null || it.fileName == download.fileName) &&
                    it.status in setOf(
                        FileDownloadStatus.DOWNLOADING,
                        FileDownloadStatus.NOT_DOWNLOADED
                    )
            }.forEach {
                modelRepository.updateFileStatus(
                    download.modelId,
                    it.fileName,
                    FileDownloadStatus.PAUSED
                )
            }
        }
    }

    /**
     * Re-enqueues a paused file. The worker appends to the partial when one
     * exists, so this resumes from where it stopped rather than starting over.
     */
    fun resumeFile(modelId: String, fileName: String) {
        viewModelScope.launch {
            val file = modelRepository.getModelFile(modelId, fileName) ?: return@launch
            val details = minimalModelDetails(modelId, fileName)
            val size = file.sizeBytes.takeIf { it > 0 }
                ?: runCatching { downloadUseCase.calculateFileSize(details, fileName) }
                    .getOrDefault(0L)
            modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.DOWNLOADING)
            downloadUseCase.executeFile(details, fileName, size, replace = true).collect { }
        }
    }

    /**
     * Throws away a download after the user confirms: stops the job (waiting
     * for the worker to settle so its PAUSED write cannot resurrect the row),
     * deletes the partial bytes, and resets tracking rows to NOT_DOWNLOADED.
     * For a whole-model job [fileName] is null and every unfinished row resets.
     */
    fun discardDownload(workId: UUID?, modelId: String, fileName: String?) {
        viewModelScope.launch {
            if (workId != null) {
                downloadUseCase.cancelActiveAndWait(workId)
            } else {
                // A paused row has no running job — nothing to settle, but make
                // sure there is no stray work either.
                if (fileName != null) downloadUseCase.cancelFileAndWait(modelId, fileName)
            }
            if (fileName != null) {
                downloadUseCase.discardPartial(modelId, fileName)
                modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.NOT_DOWNLOADED)
            } else {
                downloadUseCase.discardModelPartials(modelId)
                val files = modelRepository.observeModelFiles(modelId).first()
                files.filter {
                    it.status == FileDownloadStatus.PAUSED ||
                        it.status == FileDownloadStatus.DOWNLOADING
                }.forEach {
                    modelRepository.updateFileStatus(
                        modelId,
                        it.fileName,
                        FileDownloadStatus.NOT_DOWNLOADED
                    )
                }
            }
        }
    }

    /** Minimal details payload the download pipeline needs to (re-)enqueue a file. */
    private fun minimalModelDetails(modelId: String, fileName: String) = ModelDetails(
        id = modelId,
        author = modelId.substringBefore('/'),
        pipelineTag = "text-generation",
        libraryName = "litertlm",
        tags = emptyList(),
        downloads = null,
        likes = null,
        lastModified = null,
        createdAt = null,
        siblings = listOf(fileName),
        usedStorage = null,
        sha = null,
        gated = "false",
        disabled = false,
        widgetPrompts = emptyList(),
        baseModel = null
    )

    /** Id of the model currently loaded in memory, if any. */
    val activeModelId: String? get() = modelManager.loadedModelId

    private val _totalSizeBytes = MutableStateFlow(0L)
    val totalSizeBytes: StateFlow<Long> = _totalSizeBytes.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            downloadedFiles.collect { files -> _totalSizeBytes.value = files.sumOf { it.sizeBytes } }
        }
    }

    /**
     * True when the file's `.litertlm` still exists on disk.
     * `localPath` is the model directory; the file lives inside it.
     */
    private fun DownloadedModelFile.isFileAvailable(): Boolean {
        val dir = localPath ?: return false
        return try {
            File(dir, fileName.substringAfterLast("/")).exists()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Delete a downloaded model. If it is the model currently loaded in memory,
     * unload it from the engine first so we never delete a file out from under a
     * live inference session.
     */
    fun delete(model: DownloadedModel) {
        viewModelScope.launch(Dispatchers.Default) {
            if (modelManager.loadedModelId == model.modelId) {
                modelManager.unloadModel()
            }
            modelRepository.deleteDownloadedModel(model.modelId)
        }
    }
}
