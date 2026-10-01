package com.example.tinymodels.feature.models

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.domain.model.ModelDetails
import com.example.tinymodels.domain.repository.ModelRepository
import com.example.tinymodels.domain.usecase.model.DownloadModelUseCase
import com.example.tinymodels.domain.usecase.model.DownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val modelRepository: ModelRepository,
    private val downloadModel: DownloadModelUseCase
) : ViewModel() {

    private val modelId: String = checkNotNull(savedStateHandle["modelId"])

    data class UiState(
        val isLoading: Boolean = true,
        val model: ModelDetails? = null,
        val error: String? = null,
        val isDownloaded: Boolean = false,
        val download: DownloadState = DownloadState(),
        val downloadSizeBytes: Long = 0L
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    init {
        load()
        observeDownloaded()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = modelRepository.getModelDetails(modelId)) {
                is AppResult.Success -> _uiState.update { it.copy(isLoading = false, model = result.data) }
                is AppResult.Error -> _uiState.update {
                    it.copy(isLoading = false, error = result.error.message ?: "Failed to load details")
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

    fun onDownloadClick() {
        val model = _uiState.value.model ?: return
        if (_uiState.value.download.isDownloading) {
            downloadModel.cancel(model.id)
            _uiState.update { it.copy(download = DownloadState()) }
            return
        }
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val size = runCatching { downloadModel.calculateSize(model) }.getOrDefault(0L)
            _uiState.update { it.copy(downloadSizeBytes = size) }
            downloadModel.execute(model, size).collect { downloadState ->
                _uiState.update { it.copy(download = downloadState) }
            }
        }
    }
}
