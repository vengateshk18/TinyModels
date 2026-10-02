package com.example.tinymodels.feature.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.core.inference.ModelManager
import com.example.tinymodels.domain.model.DownloadedModel
import com.example.tinymodels.domain.model.DownloadedModelFile
import com.example.tinymodels.domain.repository.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadedModelsViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
    private val modelManager: ModelManager
) : ViewModel() {

    /** Parent metadata (one row per model) for the downloaded list. */
    val models: StateFlow<List<DownloadedModel>> =
        modelRepository.observeDownloadedModels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All DOWNLOADED files (children), grouped by model in the UI. */
    val downloadedFiles: StateFlow<List<DownloadedModelFile>> =
        modelRepository.observeDownloadedFiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Id of the model currently loaded in memory, if any. */
    val activeModelId: String? get() = modelManager.loadedModelId

    private val _totalSizeBytes = MutableStateFlow(0L)
    val totalSizeBytes: StateFlow<Long> = _totalSizeBytes.asStateFlow()

    init {
        viewModelScope.launch {
            downloadedFiles.collect { files -> _totalSizeBytes.value = files.sumOf { it.sizeBytes } }
        }
    }

    /**
     * Delete a downloaded model. If it is the model currently loaded in memory,
     * unload it from the engine first so we never delete a file out from under a
     * live inference session.
     */
    fun delete(model: DownloadedModel) {
        viewModelScope.launch {
            if (modelManager.loadedModelId == model.modelId) {
                modelManager.unloadModel()
            }
            modelRepository.deleteDownloadedModel(model.modelId)
        }
    }
}
