package com.ai.tinymodels.core.ui

import android.content.res.Resources
import com.ai.tinymodels.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pin the shared display formatters so all screens read them the same way. */
class FormattersTest {

    private val res = mockk<Resources>(relaxed = true) {
        every { getString(R.string.common_unknown) } returns "Unknown"
        every { getString(R.string.common_time_just_now) } returns "just now"
        every { getString(R.string.common_time_minutes_ago, any()) } answers { "${firstArg<Int>()}m ago" }
        every { getString(R.string.common_time_hours_ago, any()) } answers { "${firstArg<Int>()}h ago" }
        every { getString(R.string.common_time_days_ago, any()) } answers { "${firstArg<Int>()}d ago" }
    }

    @Test
    fun `formatCount keeps small numbers as-is`() {
        assertEquals("247", Formatters.formatCount(247))
        assertEquals("0", Formatters.formatCount(0))
    }

    @Test
    fun `formatCount compacts thousands and millions`() {
        assertEquals("1K", Formatters.formatCount(1_000))
        assertEquals("1.2K", Formatters.formatCount(1_200))
        assertEquals("12.3K", Formatters.formatCount(12_345))
        assertEquals("3.4M", Formatters.formatCount(3_400_000))
        assertEquals("100K", Formatters.formatCount(100_000))
    }

    @Test
    fun `formatCount trims trailing zeros for whole compacts`() {
        assertEquals("5K", Formatters.formatCount(5_000))
        assertEquals("8M", Formatters.formatCount(8_000_000))
    }

    @Test
    fun `formatBytes unknown for non-positive values`() {
        assertEquals("Unknown", Formatters.formatBytes(0, res))
        assertEquals("Unknown", Formatters.formatBytes(-1, res))
    }

    @Test
    fun `formatBytes picks the right unit`() {
        assertEquals("512 B", Formatters.formatBytes(512, res))
        assertEquals("1.00 KB", Formatters.formatBytes(1_024, res))
        assertEquals("1.00 MB", Formatters.formatBytes(1_048_576, res))
        assertEquals("1.00 GB", Formatters.formatBytes(1_073_741_824, res))
    }

    @Test
    fun `formatMbAsGb always two decimals in GB`() {
        assertEquals("0.0 GB", Formatters.formatMbAsGb(0))
        assertEquals("1.0 GB", Formatters.formatMbAsGb(1_024))
        assertEquals("7.5 GB", Formatters.formatMbAsGb(7_650))
    }
}
