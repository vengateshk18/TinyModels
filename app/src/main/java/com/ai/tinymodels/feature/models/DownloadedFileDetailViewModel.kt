package com.ai.tinymodels.feature.models

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.inference.ModelManager
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    @ApplicationContext private val context: Context,
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
        val isLoadingModel: Boolean = false, // Kept for backward compat — always false now
        val loadModelError: String? = null,
        val isDeleting: Boolean = false,
        val isExporting: Boolean = false,
        val exportMessage: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val fileInfo = modelRepository.getModelFile(modelId, fileName)
            if (fileInfo != null) {
                _uiState.update { it.copy(isLoading = false, fileInfo = fileInfo) }
            } else {
                _uiState.update { it.copy(isLoading = false, error = context.getString(R.string.downloaded_file_error_not_found)) }
            }
        }
    }

    /**
     * Navigate to chat room, passing [modelId] as a preferred model.
     * Model loading happens exclusively inside [ChatViewModel] so the
     * loading dialog is always visible in the chat room.
     */
    fun onStartChat(onSuccess: (preferredModelId: String) -> Unit) {
        val fileInfo = _uiState.value.fileInfo ?: return

        // Validate the file still exists on disk before navigating.
        val localPath = fileInfo.localPath
        if (localPath != null) {
            val file = File(localPath, fileName.substringAfterLast("/"))
            if (!file.exists()) {
                _uiState.update {
                    it.copy(loadModelError = context.getString(R.string.downloaded_file_error_not_on_disk))
                }
                return
            }
        }

        // Navigate — do NOT call modelManager.loadModel() here.
        onSuccess(modelId)
    }

    fun onDelete(onSuccess: (parentModelDeleted: Boolean) -> Unit) {
        val fileInfo = _uiState.value.fileInfo ?: return
        _uiState.update { it.copy(isDeleting = true) }

        viewModelScope.launch(Dispatchers.Default) {
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
                    it.status == com.ai.tinymodels.domain.model.FileDownloadStatus.DOWNLOADED
                }

                var parentModelDeleted = false
                if (!hasDownloadedFiles) {
                    // Delete parent and directory
                    modelRepository.deleteDownloadedModel(modelId)
                    parentModelDeleted = true
                }

                onSuccess(parentModelDeleted)
            } catch (e: Exception) {
                _uiState.update { it.copy(isDeleting = false, error = e.message ?: context.getString(R.string.downloaded_file_error_delete_failed)) }
            }
        }
    }

    fun clearLoadError() {
        _uiState.update { it.copy(loadModelError = null) }
    }

    /**
     * Export this file to a user-picked folder (SAF tree URI).
     * The result is surfaced via [UiState.exportMessage] for a snackbar.
     */
    fun exportFile(destinationUri: android.net.Uri) {
        val fileInfo = _uiState.value.fileInfo ?: return
        _uiState.update { it.copy(isExporting = true, exportMessage = null) }

        viewModelScope.launch(Dispatchers.IO) {
            when (val result = modelRepository.exportModelFile(modelId, fileName, destinationUri)) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportMessage = context.getString(R.string.downloaded_file_exported, fileName.substringAfterLast("/"))
                    )
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportMessage = context.getString(R.string.downloaded_file_export_failed, result.error.message ?: "")
                    )
                }
            }
        }
    }

    fun consumeExportMessage() {
        _uiState.update { it.copy(exportMessage = null) }
    }
}
