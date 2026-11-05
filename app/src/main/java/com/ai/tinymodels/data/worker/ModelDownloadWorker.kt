package com.ai.tinymodels.data.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.ai.tinymodels.core.common.DownloadNotifier
import com.ai.tinymodels.core.database.DownloadedModelDao
import com.ai.tinymodels.core.database.ModelFileDao
import com.ai.tinymodels.core.database.entities.DownloadedModelEntity
import com.ai.tinymodels.core.database.entities.ModelFileEntity
import com.ai.tinymodels.core.network.HuggingFaceApi
import com.ai.tinymodels.core.network.HuggingFaceAuth
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.usecase.model.ContentRangeParser
import com.ai.tinymodels.domain.usecase.model.DownloadPaths
import com.ai.tinymodels.domain.usecase.model.DownloadResumePolicy
import com.ai.tinymodels.domain.usecase.model.DownloadSpeed
import com.ai.tinymodels.domain.usecase.model.ResumeAction
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.currentCoroutineContext
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
    private val auth: HuggingFaceAuth,
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
        val modelDirectory = DownloadPaths.modelDirectory(applicationContext.filesDir, modelId)
        var downloaded = 0L
        // The notification can carry a destructive Cancel action per file, so
        // a per-file job hands over its single file; a whole-model job cannot
        // (its Cancel must discard everything, handled by passing null).
        val singleFile = if (files.size == 1) files[0] else null

        DownloadNotifier.createChannel(applicationContext)
        setForeground(createForegroundInfo(0L, total, title, modelId, singleFile))

        val startTimeMs = System.currentTimeMillis()
        // Bytes actually written by this job — resumed bytes are excluded so the
        // speed readout describes the transfer happening now.
        var transferredBytes = 0L
        return try {
            files.forEach { fileName ->
                coroutineContext.ensureActive()
                val previousDownloaded = downloaded
                val url = api.fileUrl(modelId, fileName)
                val target = DownloadPaths.finalFile(applicationContext.filesDir, modelId, fileName)
                val part = DownloadPaths.partFile(applicationContext.filesDir, modelId, fileName)
                // A per-file job knows its exact size; a whole-model job only
                // knows the sum, so it passes 0 and still resumes safely.
                val fileTotal = if (files.size == 1) total else 0L
                downloadFile(url, target, part, fileTotal) { fileDownloaded, delta ->
                    downloaded = previousDownloaded + fileDownloaded
                    transferredBytes += delta
                    val progress = if (total > 0) downloaded.toFloat() / total else 0f
                    val speed = DownloadSpeed.bytesPerSecond(
                        transferredBytes = transferredBytes,
                        elapsedMs = System.currentTimeMillis() - startTimeMs
                    )
                    setProgress(
                        workDataOf(
                            KEY_DOWNLOADED_BYTES to downloaded,
                            KEY_TOTAL_BYTES to total,
                            KEY_PROGRESS to progress,
                            KEY_BYTES_PER_SEC to speed
                        )
                    )
                    maybeNotifyProgress(title, downloaded, total, speed, modelId, singleFile)
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
                val target = DownloadPaths.finalFile(applicationContext.filesDir, modelId, fileName)
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
            // Paused (or stopped by the system): KEEP the .part files so Resume
            // continues where it left off. Files that already finished stay
            // DOWNLOADED — only the ones still in flight become PAUSED.
            // Room's suspend DAO would throw immediately in this cancelled
            // coroutine, so the bookkeeping must run un-cancellable.
            withContext(NonCancellable) {
                markUnfinished(modelId, files, FileDownloadStatus.PAUSED, null)
                DownloadNotifier.cancelProgress(applicationContext, id)
            }
            throw cancellation
        } catch (exception: Exception) {
            // Unexpected error — mark unfinished files FAILED but keep their
            // partial bytes, so a retry can still resume them.
            markUnfinished(modelId, files, FileDownloadStatus.FAILED, exception.message)
            DownloadNotifier.notifyFailed(applicationContext, id, title, exception.message)
            val message = exception.message ?: "Download failed"
            // 401/403 (gated repo, missing/invalid token or unaccepted license)
            // will never succeed on retry — fail fast with the reason.
            val authError = message.contains("HTTP 401") || message.contains("HTTP 403")
            if (authError) {
                Result.failure(workDataOf(KEY_ERROR to message))
            } else {
                Result.retry()
            }
        }
    }

    /** Posts a progress notification, throttled to avoid flooding the system. */
    private fun maybeNotifyProgress(
        title: String,
        downloaded: Long,
        total: Long,
        bytesPerSecond: Long,
        modelId: String,
        singleFile: String?
    ) {
        val nowMs = System.currentTimeMillis()
        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
        val due = nowMs - lastNotifyMs >= NOTIFY_INTERVAL_MS
        val pctAdvanced = pct != lastNotifiedPct && (pct - lastNotifiedPct >= 1 || pct >= 100)
        if (!due && !pctAdvanced) return
        lastNotifyMs = nowMs
        lastNotifiedPct = pct
        DownloadNotifier.notifyProgress(
            applicationContext, id, title, downloaded, total, bytesPerSecond, modelId, singleFile
        )
    }

    /**
     * Downloads [url] into [target], resuming a previous `.part` file when the
     * server allows it. The final file only appears once every byte arrived, so
     * a half-written model can never be mistaken for a complete one.
     *
     * [onProgress] receives (absolute bytes for this file, bytes written by this
     * chunk) — the second value feeds the speed readout so resumed bytes are
     * never counted as transfer.
     */
    private suspend fun downloadFile(
        url: String,
        target: File,
        part: File,
        totalBytes: Long,
        onProgress: suspend (downloaded: Long, delta: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        part.parentFile?.mkdirs()
        var offset = DownloadResumePolicy.resumeOffset(part.length(), totalBytes)
        var expectedTotal = totalBytes
        var askedAgainWithoutRange = false

        while (true) {
            val request = auth.authorize(
                Request.Builder()
                    .url(url)
                    .get()
                    .apply {
                        DownloadResumePolicy.rangeHeader(offset)?.let { header("Range", it) }
                    }
            ).build()
            val call = client.newCall(request)
            // Abort a stalled socket read the moment this work is cancelled
            // (notification Cancel, in-app Pause, or preemption). Without this,
            // coroutine cancellation cannot preempt the blocking read and the
            // download lingers up to the 60s socket timeout.
            coroutineContext.job.invokeOnCompletion { cause ->
                if (cause is kotlinx.coroutines.CancellationException) call.cancel()
            }

            var repeatWithoutRange = false
            try {
                call.execute().use { response ->
                    when (val action = DownloadResumePolicy.actionFor(offset, response.code)) {
                        ResumeAction.FAIL -> {
                            if (response.code == 401) {
                                throw java.io.IOException(
                                    "HTTP 401 — this model is gated. Add your Hugging Face " +
                                        "access token in Settings, or accept the model's " +
                                        "license on huggingface.co first."
                                )
                            }
                            throw java.io.IOException("HTTP ${response.code}")
                        }

                        ResumeAction.RESTART_WITHOUT_RANGE -> {
                            // 416: the body is an error page. Never write it.
                            if (askedAgainWithoutRange) {
                                throw java.io.IOException("HTTP ${response.code}")
                            }
                            askedAgainWithoutRange = true
                            repeatWithoutRange = true
                        }

                        ResumeAction.RESTART -> {
                            expectedTotal = expectedSize(totalBytes, response)
                            part.delete()
                            offset = 0L
                            writeBody(part, response, append = false, startAt = 0L, onProgress = onProgress)
                        }

                        ResumeAction.APPEND -> {
                            val range = ContentRangeParser.parse(response.header("Content-Range"))
                            if (range != null && range.start != offset) {
                                // The server continued from somewhere else. Appending
                                // would splice unrelated bytes into the file.
                                if (askedAgainWithoutRange) {
                                    throw java.io.IOException(
                                        "Server resumed at ${range.start}, expected $offset"
                                    )
                                }
                                askedAgainWithoutRange = true
                                repeatWithoutRange = true
                            } else {
                                expectedTotal = expectedSize(totalBytes, response)
                                writeBody(part, response, append = true, startAt = offset, onProgress = onProgress)
                            }
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                // A cancelled call surfaces as SocketException("Socket closed"),
                // NOT CancellationException — convert it so doWork() takes the
                // cancel path (keep partial, mark PAUSED) instead of FAILED+retry.
                coroutineContext.ensureActive()
                throw e
            }

            if (repeatWithoutRange) {
                part.delete()
                offset = 0L
                continue
            }
            break
        }

        // A short file must never be promoted: it would be recorded as
        // DOWNLOADED and only explode when the model is loaded.
        if (expectedTotal > 0L && part.length() != expectedTotal) {
            throw java.io.IOException(
                "Incomplete download: ${part.length()} of $expectedTotal bytes"
            )
        }

        // Promote the complete download. Deleting first avoids a stale
        // file from an earlier attempt blocking the rename.
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            throw java.io.IOException("Could not finalize ${target.name}")
        }
    }

    /** Body size from the response, preferring the size we were told up front. */
    private fun expectedSize(totalBytes: Long, response: Response): Long = when {
        totalBytes > 0L -> totalBytes
        else -> ContentRangeParser.parse(response.header("Content-Range"))?.total
            ?: response.body?.contentLength()
            ?: 0L
    }

    private suspend fun writeBody(
        part: File,
        response: Response,
        append: Boolean,
        startAt: Long,
        onProgress: suspend (downloaded: Long, delta: Long) -> Unit
    ) {
        val body = response.body ?: throw java.io.IOException("Empty body")
        part.parentFile?.mkdirs()
        // Report the resumed bytes immediately, otherwise a resumed job looks
        // stuck at 0% until the first chunk lands.
        if (startAt > 0L) onProgress(startAt, 0L)
        body.byteStream().use { input ->
            FileOutputStream(part, append).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var downloadedBytes = startAt
                var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    // Cooperative cancel: aborts promptly even when the socket
                    // close hasn't propagated yet.
                    currentCoroutineContext().ensureActive()
                    output.write(buffer, 0, count)
                    downloadedBytes += count
                    onProgress(downloadedBytes, count.toLong())
                }
            }
        }
    }

    /** Marks every file that is not already fully downloaded with [status]. */
    private suspend fun markUnfinished(
        modelId: String,
        files: List<String>,
        status: FileDownloadStatus,
        error: String?
    ) {
        val existing = modelFileDao.getByModelId(modelId)
        files.forEach { fileName ->
            val alreadyDownloaded = existing.firstOrNull { it.fileName == fileName }
                ?.status == FileDownloadStatus.DOWNLOADED.name
            if (!alreadyDownloaded) {
                modelFileDao.updateStatus(modelId, fileName, status.name, null, null, error)
            }
        }
    }

    private fun createForegroundInfo(
        downloaded: Long,
        total: Long,
        title: String,
        modelId: String,
        singleFile: String?
    ): ForegroundInfo {
        val notification = DownloadNotifier.progressNotification(
            applicationContext, id, title, downloaded, total, 0L, modelId, singleFile
        )
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
        const val KEY_ERROR = "error"

        private const val DEFAULT_TITLE = "Downloading model"
        private const val NOTIFY_INTERVAL_MS = 400L
    }
}
