package com.ai.tinymodels.feature.models.components

import android.content.res.Resources
import com.ai.tinymodels.R
import com.ai.tinymodels.domain.usecase.model.ActiveDownload
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * The one-line status shown under an active download row in the Models tab.
 * Lives outside the composable so the exact format is pinned by unit tests.
 */
class ActiveDownloadsStatusTest {

    private val res = mockk<Resources>(relaxed = true) {
        every { getString(R.string.downloads_progress_bytes, any(), any()) } answers {
            val a = invocation.args[1] as Array<*>
            "${a[0]} / ${a[1]}"
        }
        every { getString(R.string.downloads_progress_percent, any()) } answers {
            val a = invocation.args[1] as Array<*>
            "(${a[0]}%)"
        }
        every { getString(R.string.downloads_progress_speed, any()) } answers {
            val a = invocation.args[1] as Array<*>
            " • ${a[0]}/s"
        }
        every { getString(R.string.downloads_waiting_for_network) } returns "waiting"
        every { getString(R.string.common_unknown) } returns "Unknown"
    }

    private fun download(
        downloaded: Long = 10_485_760L,
        total: Long = 104_857_600L,
        status: DownloadStatus = DownloadStatus.DOWNLOADING,
        speed: Long = 0L,
        progress: Float = 0.1f
    ) = ActiveDownload(
        workId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
        modelId = "m",
        fileName = "f.gguf",
        downloadedBytes = downloaded,
        totalBytes = total,
        status = status,
        bytesPerSecond = speed,
        progress = progress
    )

    @Test
    fun `waiting for network renders the waiting copy`() {
        assertEquals(
            "waiting",
            ActiveDownloadsStatus.line(download(status = DownloadStatus.WAITING_FOR_NETWORK), res)
        )
    }

    @Test
    fun `shows bytes of total with percentage`() {
        assertEquals(
            "10.00 MB / 100.00 MB(10%)",
            ActiveDownloadsStatus.line(download(), res)
        )
    }

    @Test
    fun `falls back to just the downloaded size when the total is unknown`() {
        assertEquals(
            "Unknown(10%)",
            ActiveDownloadsStatus.line(download(total = 0L, downloaded = 0L), res)
        )
    }

    @Test
    fun `percentage is clamped to 0`() {
        assertEquals(
            "Unknown(0%)",
            ActiveDownloadsStatus.line(download(total = 0L, downloaded = 0L, progress = -0.5f), res)
        )
    }

    @Test
    fun `percentage is clamped to 100`() {
        assertEquals(
            "10.00 MB / 100.00 MB(100%)",
            ActiveDownloadsStatus.line(download(progress = 1.7f), res)
        )
    }

    @Test
    fun `appends speed when the connection is reporting one`() {
        assertEquals(
            "10.00 MB / 100.00 MB(10%) • 1.00 MB/s",
            ActiveDownloadsStatus.line(download(speed = 1_048_576L), res)
        )
    }
}
