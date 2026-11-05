package com.ai.tinymodels.domain.usecase.model

import com.ai.tinymodels.domain.model.FileDownloadStatus

/** What a single model-file row should show right now. */
enum class FileRowPhase {
    IDLE,
    /** Enqueued but blocked on a network constraint. */
    QUEUED,
    DOWNLOADING,
    PAUSED,
    DOWNLOADED,
    FAILED
}

/** Render-ready state of one file row, combining Room status and a live job. */
data class FileRowState(
    val phase: FileRowPhase,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L
)

/**
 * Combines the two sources of truth for a file: the status persisted in Room,
 * and the live WorkManager job (if one is running).
 *
 * A live job always wins — the DB status can lag behind a resume by a moment,
 * and showing "Paused" while bytes are arriving would be a lie. Progress is
 * clamped because WorkManager is fed by a remote server whose reported sizes
 * are not something we control.
 */
fun fileRowState(dbStatus: FileDownloadStatus, active: ActiveDownload?): FileRowState {
    if (active != null) {
        return FileRowState(
            phase = if (active.status == DownloadStatus.WAITING_FOR_NETWORK) {
                FileRowPhase.QUEUED
            } else {
                FileRowPhase.DOWNLOADING
            },
            progress = active.progress.coerceIn(0f, 1f),
            downloadedBytes = active.downloadedBytes,
            totalBytes = active.totalBytes,
            bytesPerSecond = active.bytesPerSecond
        )
    }
    return when (dbStatus) {
        FileDownloadStatus.DOWNLOADED -> FileRowState(FileRowPhase.DOWNLOADED, progress = 1f)
        FileDownloadStatus.PAUSED -> FileRowState(FileRowPhase.PAUSED)
        FileDownloadStatus.FAILED -> FileRowState(FileRowPhase.FAILED)
        FileDownloadStatus.NOT_DOWNLOADED,
        FileDownloadStatus.DOWNLOADING -> FileRowState(FileRowPhase.IDLE)
    }
}
