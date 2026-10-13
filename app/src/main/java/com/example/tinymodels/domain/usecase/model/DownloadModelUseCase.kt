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
import com.example.tinymodels.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Lifecycle status of a model download. */
enum class DownloadStatus {
    IDLE,
    /** Resolving total size / enqueueing work. */
    CHECKING_SIZE,
    /** Work is enqueued but blocked on the network constraint. */
    WAITING_FOR_NETWORK,
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
        get() = status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.CHECKING_SIZE ||
            status == DownloadStatus.WAITING_FOR_NETWORK
}

/** One active WorkManager download job, for the global downloads section. */
data class ActiveDownload(
    val workId: UUID,
    val modelId: String,
    /** File name for single-file jobs; null for whole-model jobs. */
    val fileName: String?,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L
)

/**
 * Enqueues + observes model downloads via WorkManager, with a unique-work name per
 * model so re-taps observe the existing job instead of duplicating it.
 */
@Singleton
class DownloadModelUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: HuggingFaceApi,
    private val settingsRepository: SettingsRepository
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Cached Wi-Fi-only preference. Read off the main thread at enqueue time —
     * DataStore has no synchronous read, and blocking the UI thread for it
     * on every download tap is worse than a briefly-stale flag.
     */
    private var wifiOnlyDownloads = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settingsRepository.settings
                .map { it.wifiOnlyDownloads }
                .collect { wifiOnlyDownloads = it }
        }
    }

    private fun downloadConstraints() = Constraints.Builder()
        .setRequiredNetworkType(
            if (wifiOnlyDownloads) NetworkType.UNMETERED else NetworkType.CONNECTED
        )
        .build()

    /** WorkManager tag carrying the model id (see [ActiveDownload]). */
    internal fun modelTag(modelId: String) = "model:$modelId"

    /** WorkManager tag carrying the file name for single-file jobs. */
    internal fun fileTag(fileName: String) = "file:$fileName"

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
        // KEEP discards the new request when a job already exists — observe
        // THAT job instead of the stillborn request id (which would read IDLE).
        observeExistingFile(model.id, fileName)?.let { return it }
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(downloadConstraints())
            .setInputData(
                Data.Builder()
                    .putString(ModelDownloadWorker.KEY_MODEL_ID, model.id)
                    .putString(ModelDownloadWorker.KEY_MODEL_NAME, model.id)
                    .putStringArray(ModelDownloadWorker.KEY_FILES, arrayOf(fileName))
                    .putString(ModelDownloadWorker.KEY_AUTHOR, model.author)
                    .putString(ModelDownloadWorker.KEY_LIBRARY, model.libraryName)
                    .putString(ModelDownloadWorker.KEY_PIPELINE, model.pipelineTag)
                    .putLong(ModelDownloadWorker.KEY_TOTAL_BYTES, fileSize)
                    .build()
            )
            .addTag(ModelDownloadWorker.TAG)
            .addTag(modelTag(model.id))
            .addTag(fileTag(fileName))
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
        observeExisting(model.id)?.let { return it }
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(downloadConstraints())
            .setInputData(
                Data.Builder()
                    .putString(ModelDownloadWorker.KEY_MODEL_ID, model.id)
                    .putString(ModelDownloadWorker.KEY_MODEL_NAME, model.id)
                    .putStringArray(ModelDownloadWorker.KEY_FILES, model.runtimeFiles.ifEmpty { model.liteRtFiles }.toTypedArray())
                    .putString(ModelDownloadWorker.KEY_AUTHOR, model.author)
                    .putString(ModelDownloadWorker.KEY_LIBRARY, model.libraryName)
                    .putString(ModelDownloadWorker.KEY_PIPELINE, model.pipelineTag)
                    .putLong(ModelDownloadWorker.KEY_TOTAL_BYTES, totalBytes)
                    .build()
            )
            .addTag(ModelDownloadWorker.TAG)
            .addTag(modelTag(model.id))
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
                    status = DownloadStatus.FAILED,
                    error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                        ?: "Download failed. Check your connection and retry."
                )
                WorkInfo.State.CANCELLED -> DownloadState(status = DownloadStatus.IDLE)
                // ENQUEUED = waiting on a constraint (network) — show an honest
                // "waiting" state instead of fake download progress.
                WorkInfo.State.ENQUEUED -> DownloadState(
                    status = DownloadStatus.WAITING_FOR_NETWORK
                )
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

    /**
     * All live download jobs (RUNNING or ENQUEUED) across every model, mapped
     * from the shared work tag. Backs the "Active downloads" section, which
     * updates lively from WorkManager progress without any per-screen binding.
     * Finished jobs (success/cancel/fail) disappear automatically.
     */
    fun observeActiveDownloads(): Flow<List<ActiveDownload>> =
        workManager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG).map { infos ->
            infos
                .filter {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                .mapNotNull { info ->
                    val modelId = info.tags.firstOrNull { it.startsWith("model:") }
                        ?.removePrefix("model:")
                        ?: return@mapNotNull null
                    val fileName = info.tags.firstOrNull { it.startsWith("file:") }
                        ?.removePrefix("file:")
                    val status = if (info.state == WorkInfo.State.ENQUEUED) {
                        DownloadStatus.WAITING_FOR_NETWORK
                    } else {
                        DownloadStatus.DOWNLOADING
                    }
                    ActiveDownload(
                        workId = info.id,
                        modelId = modelId,
                        fileName = fileName,
                        status = status,
                        progress = info.progress.getFloat(ModelDownloadWorker.KEY_PROGRESS, 0f),
                        downloadedBytes = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0L),
                        totalBytes = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0L),
                        bytesPerSecond = info.progress.getLong(ModelDownloadWorker.KEY_BYTES_PER_SEC, 0L)
                    )
                }
                .sortedBy { it.modelId }
        }

    /** Cancel one active job by its work id (notification + section share this). */
    fun cancelActive(workId: UUID) {
        workManager.cancelWorkById(workId)
    }
}
