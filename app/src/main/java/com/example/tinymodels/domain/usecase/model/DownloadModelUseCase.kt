package com.example.tinymodels.domain.usecase.model

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.tinymodels.core.network.HuggingFaceApi
import com.example.tinymodels.data.worker.ModelDownloadWorker
import com.example.tinymodels.domain.model.ModelDetails
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Lifecycle status of a model download. */
enum class DownloadStatus {
    IDLE,
    /** Resolving total size / enqueueing work. */
    CHECKING_SIZE,
    DOWNLOADING,
    COMPLETED,
    FAILED
}

/** State of an in-flight model download, surfaced to the UI. */
data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** Smoothed throughput for the UI, bytes/sec. 0 when unknown. */
    val bytesPerSecond: Long = 0L,
    val error: String? = null
) {
    /** Back-compat convenience for existing call sites. */
    val isDownloading: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.CHECKING_SIZE
}

/**
 * Enqueues + observes model downloads via WorkManager, with a unique-work name per
 * model so re-taps observe the existing job instead of duplicating it.
 */
@Singleton
class DownloadModelUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: HuggingFaceApi
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private fun uniqueName(modelId: String) = "model_download_$modelId"

    /** Unique work name for a single-file download (fileName sanitized for uniqueness). */
    private fun fileUniqueName(modelId: String, fileName: String) =
        "model_download_${modelId}_${fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")}"

    /** Size of a single model file via one HEAD request. */
    suspend fun calculateFileSize(model: ModelDetails, fileName: String): Long =
        withContext(Dispatchers.IO) {
            api.contentLength(api.fileUrl(model.id, fileName))
        }

    /** Enqueue a single-file download (or observe the active one) and stream progress. */
    fun executeFile(model: ModelDetails, fileName: String, fileSize: Long): Flow<DownloadState> {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(
                Data.Builder()
                    .putString(ModelDownloadWorker.KEY_MODEL_ID, model.id)
                    .putStringArray(ModelDownloadWorker.KEY_FILES, arrayOf(fileName))
                    .putString(ModelDownloadWorker.KEY_AUTHOR, model.author)
                    .putString(ModelDownloadWorker.KEY_LIBRARY, model.libraryName)
                    .putString(ModelDownloadWorker.KEY_PIPELINE, model.pipelineTag)
                    .putLong(ModelDownloadWorker.KEY_TOTAL_BYTES, fileSize)
                    .build()
            )
            .addTag(ModelDownloadWorker.TAG)
            .build()

        workManager.enqueueUniqueWork(
            fileUniqueName(model.id, fileName),
            ExistingWorkPolicy.KEEP,
            request
        )
        return observe(request.id)
    }

    /** Observe an already-running per-file download, if any. */
    fun observeExistingFile(modelId: String, fileName: String): Flow<DownloadState>? {
        val infos = workManager.getWorkInfosForUniqueWork(fileUniqueName(modelId, fileName)).get()
        val active = infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        } ?: return null
        return observe(active.id)
    }

    /** Cancel an in-flight per-file download. */
    fun cancelFile(modelId: String, fileName: String) {
        workManager.cancelUniqueWork(fileUniqueName(modelId, fileName))
    }

    /** Compute total download size on demand (user-initiated, avoids extra calls on view). */
    suspend fun calculateSize(model: ModelDetails): Long = withContext(Dispatchers.IO) {
        val files = model.runtimeFiles.ifEmpty { model.liteRtFiles }
        files.sumOf { fileName ->
            api.contentLength(api.fileUrl(model.id, fileName))
        }
    }

    /** Enqueue a download (or observe the active one) and stream its progress. */
    fun execute(model: ModelDetails, totalBytes: Long): Flow<DownloadState> {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(
                Data.Builder()
                    .putString(ModelDownloadWorker.KEY_MODEL_ID, model.id)
                    .putStringArray(ModelDownloadWorker.KEY_FILES, model.runtimeFiles.ifEmpty { model.liteRtFiles }.toTypedArray())
                    .putString(ModelDownloadWorker.KEY_AUTHOR, model.author)
                    .putString(ModelDownloadWorker.KEY_LIBRARY, model.libraryName)
                    .putString(ModelDownloadWorker.KEY_PIPELINE, model.pipelineTag)
                    .putLong(ModelDownloadWorker.KEY_TOTAL_BYTES, totalBytes)
                    .build()
            )
            .addTag(ModelDownloadWorker.TAG)
            .build()

        workManager.enqueueUniqueWork(uniqueName(model.id), ExistingWorkPolicy.KEEP, request)
        return observe(request.id)
    }

    /** Observe an already-running download for [modelId], if any. */
    fun observeExisting(modelId: String): Flow<DownloadState>? {
        val infos = workManager.getWorkInfosForUniqueWork(uniqueName(modelId)).get()
        val active = infos.firstOrNull {
            it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
        } ?: return null
        return observe(active.id)
    }

    private fun observe(workId: UUID): Flow<DownloadState> =
        workManager.getWorkInfoByIdFlow(workId).map { info ->
            if (info == null) return@map DownloadState()
            val progress = info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, 0f)
            val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L)
            val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L)
            val speed = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_PER_SEC, 0L)
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> DownloadState(
                    status = DownloadStatus.COMPLETED, progress = 1f,
                    downloadedBytes = downloaded, totalBytes = total
                )
                WorkInfo.State.FAILED -> DownloadState(
                    status = DownloadStatus.FAILED, error = "Download failed. Check your connection and retry."
                )
                WorkInfo.State.CANCELLED -> DownloadState(status = DownloadStatus.IDLE)
                else -> DownloadState(
                    status = DownloadStatus.DOWNLOADING,
                    progress = progress,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    bytesPerSecond = speed
                )
            }
        }

    fun cancel(modelId: String) {
        workManager.cancelUniqueWork(uniqueName(modelId))
    }
}
