package com.example.titymodels.models.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.titymodels.models.data.ModelDetails
import com.example.titymodels.models.domain.GetModelDetails
import com.example.titymodels.models.local.ModelDownloadRepository
import com.example.titymodels.utils.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.titymodels.models.download.ModelDownloadWorker
import android.content.Context

class ModelDetailsViewModel(
    private val getModelDetails: GetModelDetails,
    private val downloads: ModelDownloadRepository,
    private val context: Context
) : ViewModel() {
    private val _model = MutableStateFlow<ModelDetails?>(null)
    val model: StateFlow<ModelDetails?> = _model

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress
    private val _downloadSize = MutableStateFlow(0L)
    val downloadSize: StateFlow<Long> = _downloadSize
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading
    private val _downloadError = MutableStateFlow("")
    val downloadError: StateFlow<String> = _downloadError

    fun load(modelId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = ""
            when (val result = getModelDetails(modelId)) {
                is Result.Success -> {
                    _model.value = result.data
                    // Defer expensive HEAD size calculation until user initiates download to avoid
                    // extra network requests when simply viewing details.
                    _downloadSize.value = 0L
                }
                is Result.Failure -> _error.value = result.str
            }
            _isLoading.value = false
        }

    }

    private var currentDownloadUniqueName: String? = null
    private var currentObservedWorkId: java.util.UUID? = null

    fun downloadModel(model: ModelDetails) {
        if (_isDownloading.value) return
        viewModelScope.launch {
            try {
                // Calculate size now (user-triggered) to avoid extra calls on simple view.
                _downloadSize.value = downloads.calculateSize(model)
            } catch (e: Exception) {
                _downloadSize.value = 0L
            }

            val uniqueName = "model_download_${model.id}"
            currentDownloadUniqueName = uniqueName
            val wm = WorkManager.getInstance(context)

            // Build request (input uses the freshly calculated total)
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInputData(
                    Data.Builder()
                        .putString(ModelDownloadWorker.KEY_MODEL_ID, model.id)
                        .putStringArray(ModelDownloadWorker.KEY_FILES, model.liteRtFiles.toTypedArray())
                        .putString(ModelDownloadWorker.KEY_AUTHOR, model.author)
                        .putString(ModelDownloadWorker.KEY_LIBRARY, model.libraryName)
                        .putString(ModelDownloadWorker.KEY_PIPELINE, model.pipelineTag)
                        .putLong(ModelDownloadWorker.KEY_TOTAL_BYTES, _downloadSize.value)
                        .build()
                )
                .addTag("model_download")
                .build()

            _isDownloading.value = true
            _downloadError.value = ""

            // Check existing unique work; if an active entry exists, observe that instead of enqueueing a duplicate.
            val existing = withContext(kotlinx.coroutines.Dispatchers.IO) {
                wm.getWorkInfosForUniqueWork(uniqueName).get()
            }
            val active = existing.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

            val observeId = if (active != null) {
                active.id
            } else {
                wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)
                request.id
            }

            currentObservedWorkId = observeId

            // Observe progress for the correct WorkInfo id
            wm.getWorkInfoByIdFlow(observeId).collect { info ->
                info ?: return@collect
                _downloadProgress.value = info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, 0f)
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        _isDownloading.value = false
                        currentDownloadUniqueName = null
                        currentObservedWorkId = null
                    }
                    WorkInfo.State.FAILED -> {
                        _isDownloading.value = false
                        _downloadError.value = "Download failed"
                        currentDownloadUniqueName = null
                        currentObservedWorkId = null
                    }
                    WorkInfo.State.CANCELLED -> {
                        _isDownloading.value = false
                        currentDownloadUniqueName = null
                        currentObservedWorkId = null
                    }
                    else -> Unit
                }
            }
        }
    }

    fun cancelDownload() {
        currentDownloadUniqueName?.let {
            WorkManager.getInstance(context).cancelUniqueWork(it)
        } ?: WorkManager.getInstance(context).cancelAllWorkByTag("model_download")
        _isDownloading.value = false
    }
}
