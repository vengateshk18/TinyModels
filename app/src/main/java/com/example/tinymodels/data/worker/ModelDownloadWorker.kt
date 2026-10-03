package com.example.tinymodels.data.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.tinymodels.core.common.DownloadNotifier
import com.example.tinymodels.core.database.DownloadedModelDao
import com.example.tinymodels.core.database.ModelFileDao
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import com.example.tinymodels.core.database.entities.ModelFileEntity
import com.example.tinymodels.core.network.HuggingFaceApi
import com.example.tinymodels.domain.model.FileDownloadStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Downloads a model's LiteRT files in the foreground with a throttled progress
 * notification, then records the result in Room. Hilt-injected (DAO + API + OkHttp).
 *
 * Progress is posted to the system notification at most every [NOTIFY_INTERVAL_MS]
 * (or when the percent changes by >= 1) so the system isn't flooded with per-chunk
 * updates; the WorkManager `setProgress` flow to the UI still updates every chunk.
 * Terminal states post a complete / failed notification; a user cancel dismisses
 * the progress notification without a terminal post.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadedModelDao: DownloadedModelDao,
    private val modelFileDao: ModelFileDao,
    private val api: HuggingFaceApi,
    private val client: OkHttpClient
) : CoroutineWorker(appContext, workerParams) {

    // Throttling state for notification updates.
    private var lastNotifyMs = 0L
    private var lastNotifiedPct = -1

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val files = inputData.getStringArray(KEY_FILES)?.toList().orEmpty()
        val total = inputData.getLong(KEY_TOTAL_BYTES, 0L)
        val title = inputData.getString(KEY_MODEL_NAME) ?: DEFAULT_TITLE
        val modelDirectory = File(applicationContext.filesDir, "models/${modelId.replace("/", "_")}")
        var downloaded = 0L

        DownloadNotifier.createChannel(applicationContext)
        setForeground(createForegroundInfo(0L, total, title))

        val startTimeMs = System.currentTimeMillis()
        return try {
            files.forEach { fileName ->
                coroutineContext.ensureActive()
                val previousDownloaded = downloaded
                val url = api.fileUrl(modelId, fileName)
                val target = File(modelDirectory, fileName.substringAfterLast("/"))
                downloadFile(url, target) { fileDownloaded, _ ->
                    downloaded = previousDownloaded + fileDownloaded
                    val progress = if (total > 0) downloaded.toFloat() / total else 0f
                    val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0
                    val speed = if (elapsedSec > 0.5) (downloaded / elapsedSec).toLong() else 0L
                    setProgress(
                        workDataOf(
                            KEY_DOWNLOADED_BYTES to downloaded,
                            KEY_TOTAL_BYTES to total,
                            KEY_PROGRESS to progress,
                            KEY_BYTES_PER_SEC to speed
                        )
                    )
                    maybeNotifyProgress(title, downloaded, total)
                }
            }

            // ── All files finished successfully ──────────────────────────────────────
            // Only NOW insert the parent row into downloaded_models. This ensures
            // the model never appears in the "Downloaded" section unless every file
            // completed without cancellation or error.
            downloadedModelDao.insertIfAbsent(
                DownloadedModelEntity(
                    modelId = modelId,
                    author = inputData.getString(KEY_AUTHOR),
                    libraryName = inputData.getString(KEY_LIBRARY),
                    pipelineTag = inputData.getString(KEY_PIPELINE),
                    localPath = modelDirectory.absolutePath,
                    downloadedAt = System.currentTimeMillis()
                )
            )
            files.forEach { fileName ->
                val target = File(modelDirectory, fileName.substringAfterLast("/"))
                modelFileDao.insert(
                    ModelFileEntity(
                        modelId = modelId,
                        fileName = fileName,
                        status = FileDownloadStatus.DOWNLOADED.name,
                        sizeBytes = target.length(),
                        localPath = modelDirectory.absolutePath,
                        error = null
                    )
                )
            }
            DownloadNotifier.notifyComplete(applicationContext, id, title)
            Result.success()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Cancelled mid-download — reset every file back to NOT_DOWNLOADED,
            // delete partial files, and do NOT insert the parent downloaded_models row.
            files.forEach { fileName ->
                modelFileDao.updateStatus(
                    modelId, fileName, FileDownloadStatus.NOT_DOWNLOADED.name, null, null, null
                )
            }
            modelDirectory.deleteRecursively()
            DownloadNotifier.cancelProgress(applicationContext, id)
            throw cancellation
        } catch (exception: Exception) {
            // Unexpected error — mark files as FAILED, clean up, no parent row inserted.
            files.forEach { fileName ->
                modelFileDao.updateStatus(
                    modelId, fileName, FileDownloadStatus.FAILED.name, null, null, exception.message
                )
            }
            modelDirectory.deleteRecursively()
            DownloadNotifier.notifyFailed(applicationContext, id, title, exception.message)
            Result.retry()
        }
    }

    /** Posts a progress notification, throttled to avoid flooding the system. */
    private fun maybeNotifyProgress(title: String, downloaded: Long, total: Long) {
        val nowMs = System.currentTimeMillis()
        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
        val due = nowMs - lastNotifyMs >= NOTIFY_INTERVAL_MS
        val pctAdvanced = pct != lastNotifiedPct && (pct - lastNotifiedPct >= 1 || pct >= 100)
        if (!due && !pctAdvanced) return
        lastNotifyMs = nowMs
        lastNotifiedPct = pct
        DownloadNotifier.notifyProgress(applicationContext, id, title, downloaded, total)
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val body = response.body ?: throw java.io.IOException("Empty body")
            var total = body.contentLength()
            response.header("x-linked-size")?.toLongOrNull()?.takeIf { it > 0 }?.let { total = it }
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloadedBytes = 0L
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        onProgress(downloadedBytes, if (total > 0) total else -1L)
                    }
                }
            }
        }
    }

    private fun createForegroundInfo(downloaded: Long, total: Long, title: String): ForegroundInfo {
        val notification =
            DownloadNotifier.progressNotification(applicationContext, id, title, downloaded, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadNotifier.notificationIdFor(id), notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(DownloadNotifier.notificationIdFor(id), notification)
        }
    }

    companion object {
        const val TAG = "model_download"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_FILES = "files"
        const val KEY_AUTHOR = "author"
        const val KEY_LIBRARY = "library"
        const val KEY_PIPELINE = "pipeline"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_PROGRESS = "progress"
        const val KEY_BYTES_PER_SEC = "bytes_per_sec"

        private const val DEFAULT_TITLE = "Downloading model"
        private const val NOTIFY_INTERVAL_MS = 400L
    }
}
