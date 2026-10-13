package com.example.tinymodels.feature.home.components

import androidx.compose.ui.geometry.Offset
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for the [RamUsageChart] math helper [toPoints].
 * The composable itself is tested via UI tests; here we only verify
 * the pure coordinate-mapping logic.
 */
class RamUsageChartTest {

    @Test
    fun `toPoints maps available bytes to chart coordinates`() {
        val total = 8000L * 1024 * 1024
        val samples = listOf(6000L * 1024 * 1024, 4000L * 1024 * 1024) // 25%, 50% used
        val pts = toPoints(samples, total, 300f, 120f)
        assertEquals(2, pts.size)
        // 25% used -> 75% from top (y = 0.75 * 120 = 90)
        assertEquals(90f, pts[0].y, 1f)
        // 50% used -> 50% from top (y = 0.50 * 120 = 60)
        assertEquals(60f, pts[1].y, 1f)
    }

    @Test
    fun `toPoints distributes points evenly across width`() {
        val total = 8000L * 1024 * 1024
        val samples = listOf(
            6000L * 1024 * 1024,
            4000L * 1024 * 1024,
            2000L * 1024 * 1024
        ) // 25%, 50%, 75% used
        val pts = toPoints(samples, total, 300f, 120f)
        assertEquals(3, pts.size)
        // x coordinates should be evenly spaced: 0, 150, 300
        assertEquals(0f, pts[0].x, 1f)
        assertEquals(150f, pts[1].x, 1f)
        assertEquals(300f, pts[2].x, 1f)
    }

    @Test
    fun `toPoints handles empty samples`() {
        val total = 8000L * 1024 * 1024
        val samples = emptyList<Long>()
        val pts = toPoints(samples, total, 300f, 120f)
        assertTrue(pts.isEmpty())
    }

    @Test
    fun `toPoints handles single sample`() {
        val total = 8000L * 1024 * 1024
        val samples = listOf(4000L * 1024 * 1024) // 50% used
        val pts = toPoints(samples, total, 300f, 120f)
        assertEquals(1, pts.size)
        assertEquals(0f, pts[0].x, 1f)
        assertEquals(60f, pts[0].y, 1f)
    }

    @Test
    fun `toPoints clamps usage to 0-100%`() {
        val total = 8000L * 1024 * 1024
        // More available than total (shouldn't happen but defensive)
        val samples = listOf(10000L * 1024 * 1024)
        val pts = toPoints(samples, total, 300f, 120f)
        assertEquals(1, pts.size)
        // Usage clamped to 0% -> y = height (120)
        assertEquals(120f, pts[0].y, 1f)

        // Negative available (shouldn't happen but defensive)
        val samples2 = listOf(-1000L)
        val pts2 = toPoints(samples2, total, 300f, 120f)
        assertEquals(1, pts2.size)
        // Usage clamped to 100% -> y = 0
        assertEquals(0f, pts2[0].y, 1f)
    }
}