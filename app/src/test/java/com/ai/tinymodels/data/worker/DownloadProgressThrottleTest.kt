package com.ai.tinymodels.data.worker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressThrottleTest {

    @Test
    fun `first update is always allowed`() {
        assertTrue(DownloadProgressThrottle.allowsEmit(lastEmitMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun `updates inside the window are dropped`() {
        assertFalse(
            DownloadProgressThrottle.allowsEmit(
                lastEmitMs = 1_000L,
                nowMs = 1_000L + DownloadProgressThrottle.INTERVAL_MS - 1
            )
        )
    }

    @Test
    fun `update at the window boundary is allowed`() {
        assertTrue(
            DownloadProgressThrottle.allowsEmit(
                lastEmitMs = 1_000L,
                nowMs = 1_000L + DownloadProgressThrottle.INTERVAL_MS
            )
        )
    }

    @Test
    fun `updates after a long gap are allowed`() {
        assertTrue(
            DownloadProgressThrottle.allowsEmit(
                lastEmitMs = 1_000L,
                nowMs = 1_000L + DownloadProgressThrottle.INTERVAL_MS * 10
            )
        )
    }
}
