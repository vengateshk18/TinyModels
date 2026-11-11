package com.ai.tinymodels

import com.ai.tinymodels.core.inference.BenchmarkMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Statistical primitives behind the benchmark: percentiles, spread, thermal
 * degradation, and the composite score. All pure — no engine, no Android.
 */
class BenchmarkStatsTest {

    // ---- percentile ----

    @Test
    fun `median percentile matches the existing median helper`() {
        assertEquals(2.5, BenchmarkMath.percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.50), 1e-9)
    }

    @Test
    fun `p95 interpolates between the top two values`() {
        // 20 values 1..20 -> index (n-1)*0.95 = 18.05 -> 19 + 0.05*(20-19)
        assertEquals(19.05, BenchmarkMath.percentile((1..20).map { it.toDouble() }, 0.95), 1e-9)
    }

    @Test
    fun `p0 is the minimum and p100 the maximum`() {
        val values = listOf(5.0, 1.0, 9.0, 3.0)
        assertEquals(1.0, BenchmarkMath.percentile(values, 0.0), 1e-9)
        assertEquals(9.0, BenchmarkMath.percentile(values, 1.0), 1e-9)
    }

    @Test
    fun `percentile of a single value is that value`() {
        assertEquals(7.0, BenchmarkMath.percentile(listOf(7.0), 0.95), 1e-9)
    }

    @Test
    fun `percentile of an empty list is zero`() {
        assertEquals(0.0, BenchmarkMath.percentile(emptyList(), 0.95), 1e-9)
    }

    // ---- standard deviation ----

    @Test
    fun `population standard deviation`() {
        // Classic textbook set: mean 5, population stddev 2.
        assertEquals(
            2.0,
            BenchmarkMath.stdDev(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0)),
            1e-9
        )
    }

    @Test
    fun `standard deviation of identical values is zero`() {
        assertEquals(0.0, BenchmarkMath.stdDev(listOf(10.0, 10.0, 10.0)), 1e-9)
    }

    @Test
    fun `standard deviation of empty or single value is zero`() {
        assertEquals(0.0, BenchmarkMath.stdDev(emptyList()), 1e-9)
        assertEquals(0.0, BenchmarkMath.stdDev(listOf(3.0)), 1e-9)
    }

    // ---- coefficient of variation ----

    @Test
    fun `coefficient of variation is stddev over mean`() {
        // mean 10, stddev 5 -> 0.5
        assertEquals(0.5, BenchmarkMath.coefficientOfVariation(listOf(5.0, 15.0)), 1e-9)
    }

    @Test
    fun `coefficient of variation is zero when mean is zero`() {
        assertEquals(0.0, BenchmarkMath.coefficientOfVariation(listOf(0.0, 0.0)), 1e-9)
        assertEquals(0.0, BenchmarkMath.coefficientOfVariation(emptyList()), 1e-9)
    }

    // ---- thermal throttling ----

    @Test
    fun `throttle percent is the drop from early to late throughput`() {
        assertEquals(25.0, BenchmarkMath.throttlePercent(20.0, 15.0), 1e-9)
    }

    @Test
    fun `throttle percent is zero when throughput holds`() {
        assertEquals(0.0, BenchmarkMath.throttlePercent(20.0, 20.0), 1e-9)
    }

    @Test
    fun `throttle percent is negative when the device speeds up`() {
        assertEquals(-10.0, BenchmarkMath.throttlePercent(20.0, 22.0), 1e-9)
    }

    @Test
    fun `throttle percent is zero when early throughput is zero`() {
        assertEquals(0.0, BenchmarkMath.throttlePercent(0.0, 15.0), 1e-9)
    }

    // ---- composite score ----

    @Test
    fun `composite score is 100 for reference-class results`() {
        assertEquals(
            100,
            BenchmarkMath.compositeScore(
                decodeTokensPerSec = 30.0,
                prefillTokensPerSec = 300.0,
                ttftMs = 200.0,
                peakPssBytes = 1_000_000_000L,
                modelFileBytes = 1_000_000_000L
            )
        )
    }

    @Test
    fun `composite score is 0 for unusable results`() {
        assertEquals(
            0,
            BenchmarkMath.compositeScore(
                decodeTokensPerSec = 0.0,
                prefillTokensPerSec = 0.0,
                ttftMs = 5_000.0,
                peakPssBytes = 5_000_000_000L,
                modelFileBytes = 1_000_000_000L
            )
        )
    }

    @Test
    fun `composite score rewards faster decode`() {
        val slow = BenchmarkMath.compositeScore(5.0, 100.0, 500.0, 1_000_000_000L, 1_000_000_000L)
        val fast = BenchmarkMath.compositeScore(25.0, 100.0, 500.0, 1_000_000_000L, 1_000_000_000L)
        assertTrue("fast=$fast should beat slow=$slow", fast > slow)
    }

    @Test
    fun `composite score rewards lower memory overhead`() {
        val lean = BenchmarkMath.compositeScore(20.0, 200.0, 400.0, 1_100_000_000L, 1_000_000_000L)
        val bloated = BenchmarkMath.compositeScore(20.0, 200.0, 400.0, 2_800_000_000L, 1_000_000_000L)
        assertTrue("lean=$lean should beat bloated=$bloated", lean > bloated)
    }

    @Test
    fun `composite score stays within 0 and 100`() {
        val huge = BenchmarkMath.compositeScore(999.0, 9_999.0, 0.0, 1L, 1_000_000_000L)
        assertTrue("huge=$huge", huge in 0..100)
    }

    @Test
    fun `composite score handles a zero model size without dividing by zero`() {
        val score = BenchmarkMath.compositeScore(20.0, 200.0, 400.0, 1_000_000_000L, 0L)
        assertTrue("score=$score", score in 0..100)
    }

    // ---- grade ----

    @Test
    fun `grade bands map scores to letters`() {
        assertEquals("S", BenchmarkMath.gradeFor(95))
        assertEquals("S", BenchmarkMath.gradeFor(85))
        assertEquals("A", BenchmarkMath.gradeFor(84))
        assertEquals("A", BenchmarkMath.gradeFor(70))
        assertEquals("B", BenchmarkMath.gradeFor(69))
        assertEquals("B", BenchmarkMath.gradeFor(55))
        assertEquals("C", BenchmarkMath.gradeFor(54))
        assertEquals("C", BenchmarkMath.gradeFor(40))
        assertEquals("D", BenchmarkMath.gradeFor(39))
        assertEquals("D", BenchmarkMath.gradeFor(0))
    }

    // ---- stability label ----

    @Test
    fun `low variation across runs counts as stable`() {
        assertEquals(BenchmarkMath.Stability.STABLE, BenchmarkMath.stabilityFor(0.03))
        assertEquals(BenchmarkMath.Stability.FAIR, BenchmarkMath.stabilityFor(0.12))
        assertEquals(BenchmarkMath.Stability.UNSTABLE, BenchmarkMath.stabilityFor(0.30))
    }
}
