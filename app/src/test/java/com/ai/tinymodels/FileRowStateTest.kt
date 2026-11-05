package com.ai.tinymodels

import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.usecase.model.ActiveDownload
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import com.ai.tinymodels.domain.usecase.model.FileRowPhase
import com.ai.tinymodels.domain.usecase.model.fileRowState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * Maps the two independent sources of truth for a model file — the persisted
 * Room status and the live WorkManager job — into one state the row can render.
 * Pure: no Android, no WorkManager.
 */
class FileRowStateTest {

    private fun active(
        progress: Float = 0.25f,
        downloadedBytes: Long = 250L,
        totalBytes: Long = 1000L,
        bytesPerSecond: Long = 4096L
    ) = ActiveDownload(
        workId = UUID.randomUUID(),
        modelId = "owner/model",
        fileName = "model.litertlm",
        status = DownloadStatus.DOWNLOADING,
        progress = progress,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        bytesPerSecond = bytesPerSecond
    )

    @Test
    fun `idle when the file is not downloaded and nothing is running`() {
        val state = fileRowState(FileDownloadStatus.NOT_DOWNLOADED, null)

        assertEquals(FileRowPhase.IDLE, state.phase)
        assertEquals(0f, state.progress, 0f)
    }

    @Test
    fun `downloaded file reports full progress`() {
        val state = fileRowState(FileDownloadStatus.DOWNLOADED, null)

        assertEquals(FileRowPhase.DOWNLOADED, state.phase)
        assertEquals(1f, state.progress, 0f)
    }

    @Test
    fun `paused file without a job stays paused`() {
        val state = fileRowState(FileDownloadStatus.PAUSED, null)

        assertEquals(FileRowPhase.PAUSED, state.phase)
    }

    @Test
    fun `failed file without a job stays failed`() {
        val state = fileRowState(FileDownloadStatus.FAILED, null)

        assertEquals(FileRowPhase.FAILED, state.phase)
    }

    @Test
    fun `a live job wins over a stale paused status`() {
        val state = fileRowState(FileDownloadStatus.PAUSED, active())

        assertEquals(FileRowPhase.DOWNLOADING, state.phase)
    }

    @Test
    fun `a live job wins over a stale idle status`() {
        val state = fileRowState(FileDownloadStatus.NOT_DOWNLOADED, active())

        assertEquals(FileRowPhase.DOWNLOADING, state.phase)
    }

    @Test
    fun `live byte counts, speed and progress are passed through`() {
        val state = fileRowState(
            FileDownloadStatus.DOWNLOADING,
            active(progress = 0.25f, downloadedBytes = 250L, totalBytes = 1000L, bytesPerSecond = 4096L)
        )

        assertEquals(0.25f, state.progress, 0f)
        assertEquals(250L, state.downloadedBytes)
        assertEquals(1000L, state.totalBytes)
        assertEquals(4096L, state.bytesPerSecond)
    }

    @Test
    fun `a job waiting for the network is queued, not downloading`() {
        val state = fileRowState(
            FileDownloadStatus.DOWNLOADING,
            active().copy(status = DownloadStatus.WAITING_FOR_NETWORK)
        )

        assertEquals(FileRowPhase.QUEUED, state.phase)
    }

    @Test
    fun `progress above one is clamped`() {
        val state = fileRowState(FileDownloadStatus.DOWNLOADING, active(progress = 1.7f))

        assertEquals(1f, state.progress, 0f)
    }

    @Test
    fun `negative progress is clamped to zero`() {
        val state = fileRowState(FileDownloadStatus.DOWNLOADING, active(progress = -0.2f))

        assertEquals(0f, state.progress, 0f)
    }

    @Test
    fun `paused file with partial bytes reports how much is downloaded`() {
        val state = fileRowState(
            FileDownloadStatus.PAUSED, null,
            partialBytes = 250L, totalBytes = 1000L
        )

        assertEquals(FileRowPhase.PAUSED, state.phase)
        assertEquals(250L, state.downloadedBytes)
        assertEquals(1000L, state.totalBytes)
        assertEquals(0.25f, state.progress, 0f)
    }

    @Test
    fun `paused file with partial bytes but unknown total reports bytes without progress`() {
        val state = fileRowState(
            FileDownloadStatus.PAUSED, null,
            partialBytes = 250L, totalBytes = 0L
        )

        assertEquals(250L, state.downloadedBytes)
        assertEquals(0L, state.totalBytes)
        assertEquals(0f, state.progress, 0f)
    }

    @Test
    fun `paused file without partial bytes reports no downloaded data`() {
        val state = fileRowState(FileDownloadStatus.PAUSED, null, totalBytes = 1000L)

        assertEquals(0L, state.downloadedBytes)
        assertEquals(0f, state.progress, 0f)
    }
}
