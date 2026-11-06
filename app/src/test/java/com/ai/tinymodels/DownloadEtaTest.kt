package com.ai.tinymodels

import com.ai.tinymodels.domain.usecase.model.DownloadEta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Time remaining shown next to the speed while a file downloads.
 * Pure: the row supplies counters, this decides what to print.
 */
class DownloadEtaTest {

    @Test
    fun `remaining time is the bytes left divided by the current speed`() {
        // 1000 bytes left at 1000 B/s -> 1 second.
        assertEquals(1_000L, DownloadEta.remainingMillis(downloaded = 0L, total = 1_000L, bytesPerSecond = 1_000L))
    }

    @Test
    fun `no estimate without a known total`() {
        assertNull(DownloadEta.remainingMillis(downloaded = 10L, total = 0L, bytesPerSecond = 1_000L))
    }

    @Test
    fun `no estimate without a speed`() {
        assertNull(DownloadEta.remainingMillis(downloaded = 10L, total = 1_000L, bytesPerSecond = 0L))
    }

    @Test
    fun `no estimate once the file is complete`() {
        assertNull(DownloadEta.remainingMillis(downloaded = 1_000L, total = 1_000L, bytesPerSecond = 1_000L))
    }

    @Test
    fun `downloaded beyond total still yields no estimate`() {
        assertNull(DownloadEta.remainingMillis(downloaded = 1_500L, total = 1_000L, bytesPerSecond = 1_000L))
    }

    @Test
    fun `seconds under a minute`() {
        assertEquals("42s left", DownloadEta.formatRemaining(42_000L))
    }

    @Test
    fun `minutes and seconds`() {
        assertEquals("2m 14s left", DownloadEta.formatRemaining(134_000L))
    }

    @Test
    fun `hours and minutes`() {
        assertEquals("1h 05m left", DownloadEta.formatRemaining(3_900_000L))
    }

    @Test
    fun `very short waits round up to one second`() {
        assertEquals("1s left", DownloadEta.formatRemaining(120L))
    }
}
