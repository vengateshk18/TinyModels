package com.ai.tinymodels

import com.ai.tinymodels.core.inference.BenchmarkMath
import com.ai.tinymodels.core.inference.BenchmarkProtocol
import com.ai.tinymodels.core.inference.PromptSpec
import com.ai.tinymodels.core.inference.RunMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The benchmark protocol: deterministic sampling, the prompt suite, and the
 * aggregation into a scored result. Pure — no engine, no Android.
 */
class BenchmarkProtocolTest {

    // ---- deterministic sampling ----

    @Test
    fun `benchmark sampling is greedy so every run is reproducible`() {
        assertEquals(0.0, BenchmarkProtocol.SAMPLER.temperature, 1e-9)
    }

    @Test
    fun `benchmark sampler is fixed across models and devices`() {
        // Any change here invalidates comparison with previously reported runs.
        assertEquals(1, BenchmarkProtocol.SAMPLER.topK)
        assertEquals(1.0, BenchmarkProtocol.SAMPLER.topP, 1e-9)
    }

    // ---- prompt suite ----

    @Test
    fun `suite has three prompts covering prefill-light to prefill-heavy`() {
        val prompts = BenchmarkProtocol.PROMPTS
        assertEquals(3, prompts.size)
        assertEquals(listOf("short", "medium", "long"), prompts.map { it.id })
    }

    @Test
    fun `prompt sizes increase across the suite`() {
        val sizes = BenchmarkProtocol.PROMPTS.map { it.text.length }
        assertTrue("sizes=$sizes", sizes[0] < sizes[1])
        assertTrue("sizes=$sizes", sizes[1] < sizes[2])
    }

    @Test
    fun `run zero is the warm-up and later runs are measured`() {
        assertTrue(BenchmarkProtocol.isWarmUp(0))
        assertTrue(!BenchmarkProtocol.isWarmUp(1))
        assertTrue(!BenchmarkProtocol.isWarmUp(BenchmarkProtocol.MEASURED_RUNS))
    }

    // ---- aggregation: percentiles and spread ----

    private val prompt = PromptSpec("short", "Short", "hi")

    private fun run(index: Int, decode: Double?, ttft: Double) = RunMetrics(
        promptId = "short",
        runIndex = index,
        isWarmUp = index == 0,
        ttftMs = ttft,
        decodeTokensPerSec = decode,
        prefillTokensPerSec = 100.0,
        outputTokens = 128,
        durationMs = 1_000.0
    )

    @Test
    fun `aggregate reports p95 decode above the median for skewed runs`() {
        val metrics = listOf(
            run(0, 999.0, 50.0),                       // warm-up, ignored
            run(1, 20.0, 100.0),
            run(2, 10.0, 100.0),
            run(3, 10.0, 100.0),
            run(4, 10.0, 100.0),
            run(5, 10.0, 100.0)
        )

        val result = BenchmarkMath.aggregate(listOf(prompt), metrics).single()

        assertEquals(10.0, result.medianDecodeTokensPerSec, 1e-9)
        // (n-1)*0.95 = 3.8 -> 10 + 0.8*(20-10) = 18
        assertEquals(18.0, result.decodeP95TokensPerSec, 1e-9)
        assertTrue(result.decodeP95TokensPerSec > result.medianDecodeTokensPerSec)
    }

    @Test
    fun `aggregate reports the spread across measured runs`() {
        val metrics = listOf(
            run(0, 0.0, 100.0),
            run(1, 10.0, 100.0),
            run(2, 20.0, 100.0)
        )

        val result = BenchmarkMath.aggregate(listOf(prompt), metrics).single()

        // population stddev of [10, 20] = 5
        assertEquals(5.0, result.decodeStdDev, 1e-9)
    }

    @Test
    fun `aggregate reports p95 ttft`() {
        val metrics = listOf(
            run(0, 10.0, 999.0),
            run(1, 10.0, 100.0),
            run(2, 10.0, 200.0)
        )

        val result = BenchmarkMath.aggregate(listOf(prompt), metrics).single()

        assertEquals(150.0, result.medianTtftMs, 1e-9)
        assertEquals(195.0, result.ttftP95Ms, 1e-9)
    }

    // ---- result composition ----

    private fun compose(
        decode: Double = 12.0,
        ttft: Double = 400.0,
        peakPss: Long = 1_200_000_000L,
        modelBytes: Long = 1_000_000_000L
    ) = BenchmarkMath.composeResult(
        modelId = "owner/model",
        fileName = "model.task",
        backendUsed = "GPU",
        loadTimeMs = 1_500.0,
        baselinePssBytes = 400_000_000L,
        peakPssBytes = peakPss,
        modelFileBytes = modelBytes,
        prompts = listOf(prompt),
        metrics = listOf(
            run(0, decode, ttft),
            run(1, decode, ttft),
            run(2, decode, ttft)
        ),
        sustainedTokensPerSec = decode,
        sustainedThrottlePercent = 5.0,
        deviceName = "Test Device",
        completedAt = 0L
    )

    @Test
    fun `composeResult produces a score with a matching grade`() {
        val result = compose()

        assertTrue("score=${result.score}", result.score in 0..100)
        assertEquals(BenchmarkMath.gradeFor(result.score), result.grade)
    }

    @Test
    fun `composeResult carries model size for the memory ratio`() {
        val result = compose(modelBytes = 1_000_000_000L)

        assertEquals(1_000_000_000L, result.modelFileBytes)
    }

    @Test
    fun `composeResult reports a stability label from the run spread`() {
        val result = compose()

        assertEquals(
            BenchmarkMath.stabilityLabel(result.decodeCoefficientOfVariation),
            result.stability
        )
    }

    @Test
    fun `composeResult keeps the overall decode speed as the median across prompts`() {
        val result = compose(decode = 12.0)

        assertEquals(12.0, result.overallDecodeTokensPerSec, 1e-9)
    }

    @Test
    fun `composeResult passes the sustained metrics through`() {
        val result = compose()

        assertEquals(5.0, result.sustainedThrottlePercent, 1e-9)
        assertEquals(12.0, result.sustainedTokensPerSec, 1e-9)
    }

    // ---- sustained / thermal ----

    @Test
    fun `sustained summary averages the samples and reports the thermal drop`() {
        val summary = BenchmarkMath.sustainSummary(listOf(20.0, 20.0, 15.0, 15.0))

        assertEquals(17.5, summary.averageTokensPerSec, 1e-9)
        // early mean 20 -> late mean 15 = 25% drop
        assertEquals(25.0, summary.throttlePercent, 1e-9)
    }

    @Test
    fun `sustained summary of no samples is zero`() {
        val summary = BenchmarkMath.sustainSummary(emptyList())

        assertEquals(0.0, summary.averageTokensPerSec, 1e-9)
        assertEquals(0.0, summary.throttlePercent, 1e-9)
    }

    @Test
    fun `sustained summary of a single sample reports no drop`() {
        val summary = BenchmarkMath.sustainSummary(listOf(18.0))

        assertEquals(18.0, summary.averageTokensPerSec, 1e-9)
        assertEquals(0.0, summary.throttlePercent, 1e-9)
    }

    // ---- throttle wording ----

    @Test
    fun `throttle label reports a drop as a positive percentage`() {
        assertEquals("12.5% drop", BenchmarkMath.throttleLabel(12.5))
    }

    @Test
    fun `throttle label calls a speed-up faster, not a negative drop`() {
        assertEquals("5.0% faster", BenchmarkMath.throttleLabel(-5.0))
    }

    @Test
    fun `throttle label ignores sub-half-percent noise`() {
        assertEquals("No change", BenchmarkMath.throttleLabel(0.3))
        assertEquals("No change", BenchmarkMath.throttleLabel(-0.4))
        assertEquals("No change", BenchmarkMath.throttleLabel(0.0))
    }
}
