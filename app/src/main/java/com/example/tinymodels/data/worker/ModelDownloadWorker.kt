package com.example.tinymodels.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.tinymodels.R
import com.example.tinymodels.core.database.DownloadedModelDao
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import com.example.tinymodels.core.network.HuggingFaceApi
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
 * Downloads a model's LiteRT files in the foreground with a progress notification,
 * then records the result in Room. Hilt-injected (DAO + API + OkHttp).
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val downloadedModelDao: DownloadedModelDao,
    private val api: HuggingFaceApi,
    private val client: OkHttpClient
) : CoroutineWorker(appContext, workerParams) {

    private val notificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val files = inputData.getStringArray(KEY_FILES)?.toList().orEmpty()
        val total = inputData.getLong(KEY_TOTAL_BYTES, 0L)
        val modelDirectory = File(applicationContext.filesDir, "models/${modelId.replace("/", "_")}")
        var downloaded = 0L

        createChannel()
        setForeground(createForegroundInfo(0L, total))

        return try {
            files.forEach { fileName ->
                coroutineContext.ensureActive()
                val previousDownloaded = downloaded
                val url = api.fileUrl(modelId, fileName)
                val target = File(modelDirectory, fileName.substringAfterLast("/"))
                downloadFile(url, target) { fileDownloaded, _ ->
                    downloaded = previousDownloaded + fileDownloaded
                    val progress = if (total > 0) downloaded.toFloat() / total else 0f
                    setProgress(
                        workDataOf(
                            KEY_DOWNLOADED_BYTES to downloaded,
                            KEY_TOTAL_BYTES to total,
                            KEY_PROGRESS to progress
                        )
                    )
                    notificationManager.notify(NOTIFICATION_ID, createNotification(downloaded, total))
                }
            }

            downloadedModelDao.insert(
                DownloadedModelEntity(
                    modelId = modelId,
                    author = inputData.getString(KEY_AUTHOR),
                    libraryName = inputData.getString(KEY_LIBRARY),
                    pipelineTag = inputData.getString(KEY_PIPELINE),
                    localPath = modelDirectory.absolutePath,
                    files = files.joinToString("\n"),
                    sizeBytes = modelDirectory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    downloadedAt = System.currentTimeMillis()
                )
            )
            Result.success()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            modelDirectory.deleteRecursively()
            throw cancellation
        } catch (exception: Exception) {
            modelDirectory.deleteRecursively()
            Result.retry()
        }
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

    private fun createForegroundInfo(downloaded: Long, total: Long): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, createNotification(downloaded, total),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, createNotification(downloaded, total))
        }

    private fun createNotification(downloaded: Long, total: Long) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Downloading model")
            .setContentText("${formatMb(downloaded)} / ${formatMb(total)}")
            .setProgress(if (total > 0) 100 else 0,
                if (total > 0) (downloaded * 100 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)

    companion object {
        const val TAG = "model_download"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_FILES = "files"
        const val KEY_AUTHOR = "author"
        const val KEY_LIBRARY = "library"
        const val KEY_PIPELINE = "pipeline"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_PROGRESS = "progress"
        const val CHANNEL_ID = "model_downloads"
        const val NOTIFICATION_ID = 1001
    }
}
