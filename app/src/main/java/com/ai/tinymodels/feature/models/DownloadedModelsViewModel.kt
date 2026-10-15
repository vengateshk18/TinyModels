package com.ai.tinymodels.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.core.inference.ModelManager
import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.usecase.model.ActiveDownload
import com.ai.tinymodels.domain.usecase.model.DownloadModelUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     * Only files still present on disk are shown: if the underlying model
     * file was deleted outside the app (e.g. via Files), the stale
     * DB row is hidden instead of offered for chat.
     */
    val downloadedFiles: StateFlow<List<DownloadedModelFile>> =
        modelRepository.observeDownloadedFiles()
            .map { files -> files.filter { it.isFileAvailable() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Live download jobs (RUNNING/ENQUEUED) straight from WorkManager — the
     * "Active downloads" section updates from this without any per-screen
     * binding, and each row cancels its own job.
     */
    val activeDownloads: StateFlow<List<ActiveDownload>> =
        downloadUseCase.observeActiveDownloads()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Cancel one active download (same path the notification uses). */
    fun cancelActiveDownload(workId: UUID) {
        downloadUseCase.cancelActive(workId)
    }

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
     * True when the model file still exists on disk.
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
