package com.example.tinymodels.feature.models

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.core.inference.ModelManager
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.DownloadedModelFile
import com.example.tinymodels.domain.repository.ModelRepository
import com.example.tinymodels.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class DownloadedFileDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val modelManager: ModelManager
) : ViewModel() {

    private val modelId: String = checkNotNull(savedStateHandle["modelId"])
    private val fileName: String = checkNotNull(savedStateHandle["fileName"])

    data class UiState(
        val isLoading: Boolean = true,
        val fileInfo: DownloadedModelFile? = null,
        val error: String? = null,
        val isLoadingModel: Boolean = false,
        val loadModelError: String? = null,
        val isDeleting: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val fileInfo = modelRepository.getModelFile(modelId, fileName)
            if (fileInfo != null) {
                _uiState.update { it.copy(isLoading = false, fileInfo = fileInfo) }
            } else {
                _uiState.update { it.copy(isLoading = false, error = "File not found") }
            }
        }
    }

    fun onStartChat(onSuccess: () -> Unit) {
        val fileInfo = _uiState.value.fileInfo ?: return
        _uiState.update { it.copy(isLoadingModel = true, loadModelError = null) }

        viewModelScope.launch {
            try {
                val settings = settingsRepository.settings.first()
                val localPath = fileInfo.localPath ?: throw IllegalStateException("File has no local path")
                val file = File(localPath, fileName.substringAfterLast("/"))
                
                if (!file.exists()) {
                    _uiState.update {
                        it.copy(
                            isLoadingModel = false,
                            loadModelError = "File not found on disk. It may have been deleted."
                        )
                    }
                    return@launch
                }

                val result = modelManager.loadModel(
                    modelId = modelId,
                    modelFile = file,
                    backend = settings.defaultBackend,
                    maxNumTokens = ModelManager.DEFAULT_MAX_TOKENS
                )

                when (result) {
                    is com.example.tinymodels.core.common.AppResult.Success -> {
                        _uiState.update { it.copy(isLoadingModel = false) }
                        onSuccess()
                    }
                    is com.example.tinymodels.core.common.AppResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoadingModel = false,
                                loadModelError = result.error.message ?: "Failed to load model"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingModel = false,
                        loadModelError = e.message ?: "Failed to load model"
                    )
                }
            }
        }
    }

    fun onDelete(onSuccess: () -> Unit) {
        val fileInfo = _uiState.value.fileInfo ?: return
        _uiState.update { it.copy(isDeleting = true) }

        viewModelScope.launch {
            try {
                // If this is the currently loaded model, unload it first
                if (modelManager.loadedModelId == modelId) {
                    modelManager.unloadModel()
                }

                // Delete the file from disk
                val localPath = fileInfo.localPath
                if (localPath != null) {
                    val file = File(localPath, fileName.substringAfterLast("/"))
                    file.delete()
                }

                // Delete from DB
                modelRepository.deleteModelFile(modelId, fileName)

                // Check if this was the last downloaded file for this model
                val remainingFiles = modelRepository.observeModelFiles(modelId).first()
                val hasDownloadedFiles = remainingFiles.any { 
                    it.status == com.example.tinymodels.domain.model.FileDownloadStatus.DOWNLOADED 
                }

                if (!hasDownloadedFiles) {
                    // Delete parent and directory
                    modelRepository.deleteDownloadedModel(modelId)
                }

                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isDeleting = false, error = e.message ?: "Failed to delete file") }
            }
        }
    }

    fun clearLoadError() {
        _uiState.update { it.copy(loadModelError = null) }
    }
}
