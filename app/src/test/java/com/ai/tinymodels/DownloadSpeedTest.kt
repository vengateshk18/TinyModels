package com.ai.tinymodels

import com.ai.tinymodels.domain.usecase.model.DownloadSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The speed shown in the row must describe the transfer happening now, not
 * bytes that were already on disk before the resume.
 */
class DownloadSpeedTest {

    @Test
    fun `speed is bytes transferred over elapsed time`() {
        assertEquals(1_000L, DownloadSpeed.bytesPerSecond(transferredBytes = 5_000L, elapsedMs = 5_000L))
    }

    @Test
    fun `no speed is reported before half a second has elapsed`() {
        assertEquals(0L, DownloadSpeed.bytesPerSecond(transferredBytes = 100_000L, elapsedMs = 400L))
    }

    @Test
    fun `resumed bytes are not counted as transferred`() {
        // Nothing new transferred yet: a resumed job must not report a huge rate.
        assertEquals(0L, DownloadSpeed.bytesPerSecond(transferredBytes = 0L, elapsedMs = 10_000L))
    }

    @Test
    fun `negative transfer is clamped to zero`() {
        assertEquals(0L, DownloadSpeed.bytesPerSecond(transferredBytes = -10L, elapsedMs = 10_000L))
    }

    @Test
    fun `zero elapsed time does not divide by zero`() {
        assertEquals(0L, DownloadSpeed.bytesPerSecond(transferredBytes = 1_000L, elapsedMs = 0L))
    }
}
