package com.example.tinymodels.models.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.tinymodels.R
import com.example.tinymodels.models.data.ModelDetails
import com.example.tinymodels.core.database.entities.DownloadedModelEntity
import com.example.tinymodels.core.database.TinyModelsDatabase
import com.example.tinymodels.utils.OkHttpUtil
import com.example.tinymodels.utils.TinyModelsApiConstants
import com.example.tinymodels.utils.UrlGeneratorUtil
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
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
                val url = UrlGeneratorUtil.getUrl(
                    TinyModelsApiConstants.MODEL_FILE,
                    mapOf("modelId" to modelId, "fileName" to fileName)
                )
                val target = File(modelDirectory, fileName.substringAfterLast("/"))
                OkHttpUtil.download(url, target) { fileDownloaded, _ ->
                    downloaded = previousDownloaded + fileDownloaded
                    val progress = if (total > 0) downloaded.toFloat() / total else 0f
                    setProgress(
                        androidx.work.workDataOf(
                            KEY_DOWNLOADED_BYTES to downloaded,
                            KEY_TOTAL_BYTES to total,
                            KEY_PROGRESS to progress
                        )
                    )
                    notificationManager.notify(NOTIFICATION_ID, createNotification(downloaded, total))
                }
            }

            val database = androidx.room.Room.databaseBuilder(
                applicationContext,
                TinyModelsDatabase::class.java,
                "tiny_models.db"
            ).build()
            database.downloadedModelDao().insert(
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
        } catch (exception: kotlinx.coroutines.CancellationException) {
            modelDirectory.deleteRecursively()
            throw exception
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun createForegroundInfo(downloaded: Long, total: Long): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                createNotification(downloaded, total),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, createNotification(downloaded, total))
        }

    private fun createNotification(downloaded: Long, total: Long) =
        NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Downloading model")
            .setContentText("${formatBytes(downloaded)} / ${formatBytes(total)}")
            .setProgress(if (total > 0) 100 else 0, if (total > 0) (downloaded * 100 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun formatBytes(bytes: Long): String =
        "%.1f MB".format(bytes / 1024.0 / 1024.0)

    companion object {
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
