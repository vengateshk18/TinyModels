package com.example.tinymodels

import com.example.tinymodels.core.inference.BenchmarkMath
import com.example.tinymodels.core.inference.PromptResult
import com.example.tinymodels.core.inference.PromptSpec
import com.example.tinymodels.core.inference.RunMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkMathTest {

    // ---- median ----

    @Test
    fun `median of odd count picks middle value`() {
        assertEquals(2.0, BenchmarkMath.median(listOf(1.0, 2.0, 3.0)), 1e-9)
    }

    @Test
    fun `median of even count averages middle pair`() {
        assertEquals(2.5, BenchmarkMath.median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9)
    }

    @Test
    fun `median of empty list is zero`() {
        assertEquals(0.0, BenchmarkMath.median(emptyList()), 1e-9)
    }

    @Test
    fun `median ignores order of input`() {
        assertEquals(3.0, BenchmarkMath.median(listOf(5.0, 1.0, 3.0)), 1e-9)
    }

    // ---- TTFT ----

    @Test
    fun `ttft uses first emission when present`() {
        // 150ms between send and first token.
        val start = 1_000_000_000L
        val first = start + 150_000_000L
        val end = start + 5_000_000_000L
        assertEquals(150.0, BenchmarkMath.ttftMs(start, first, end), 1e-6)
    }

    @Test
    fun `ttft falls back to duration when no emission`() {
        val start = 1_000_000_000L
        val end = start + 800_000_000L
        assertEquals(800.0, BenchmarkMath.ttftMs(start, -1L, end), 1e-6)
    }

    // ---- decode speed ----

    @Test
    fun `decode speed counts tokens between first and last emission`() {
        // 9 tokens between first and last emission over 900ms → 10 tok/s.
        val first = 1_000_000_000L
        val last = first + 900_000_000L
        assertEquals(10.0, BenchmarkMath.decodeTokensPerSec(10, first, last)!!, 1e-6)
    }

    @Test
    fun `decode speed null when fewer than two tokens`() {
        assertNull(BenchmarkMath.decodeTokensPerSec(1, 1_000_000_000L, 2_000_000_000L))
    }

    @Test
    fun `decode speed null when zero tokens`() {
        assertNull(BenchmarkMath.decodeTokensPerSec(0, 1_000_000_000L, 2_000_000_000L))
    }

    // ---- prefill speed ----

    @Test
    fun `prefill speed divides prompt tokens by ttft`() {
        // 120 tokens in 600ms → 200 tok/s.
        assertEquals(200.0, BenchmarkMath.prefillTokensPerSec(120, 600.0), 1e-6)
    }

    @Test
    fun `prefill speed zero when ttft is zero`() {
        assertEquals(0.0, BenchmarkMath.prefillTokensPerSec(120, 0.0), 1e-9)
    }

    // ---- aggregation ----

    private val shortPrompt = PromptSpec("short", "Short", "hi")
    private val mediumPrompt = PromptSpec("medium", "Medium", "hello world")

    private fun run(
        promptId: String,
        runIndex: Int,
        ttft: Double,
        decode: Double?,
        prefill: Double
    ) = RunMetrics(
        promptId = promptId,
        runIndex = runIndex,
        isWarmUp = runIndex == 0,
        ttftMs = ttft,
        decodeTokensPerSec = decode,
        prefillTokensPerSec = prefill,
        outputTokens = 256,
        durationMs = 1000.0
    )

    @Test
    fun `aggregate excludes warm-up runs`() {
        val metrics = listOf(
            run("short", 0, 999.0, 1.0, 1.0),   // warm-up — must be ignored
            run("short", 1, 100.0, 10.0, 50.0),
            run("short", 2, 200.0, 20.0, 25.0)
        )
        val results = BenchmarkMath.aggregate(listOf(shortPrompt), metrics)

        assertEquals(1, results.size)
        val r = results.first()
        assertEquals(2, r.measuredRuns.size)
        assertEquals(150.0, r.medianTtftMs, 1e-6)      // median(100, 200)
        assertEquals(15.0, r.medianDecodeTokensPerSec, 1e-6) // median(10, 20)
        assertEquals(37.5, r.medianPrefillTokensPerSec, 1e-6) // median(50, 25)
    }

    @Test
    fun `aggregate keeps prompt order and drops empty prompts`() {
        val metrics = listOf(
            run("medium", 1, 100.0, 10.0, 50.0),
            run("short", 1, 200.0, 20.0, 25.0)
        )
        val results = BenchmarkMath.aggregate(listOf(shortPrompt, mediumPrompt), metrics)

        assertEquals(2, results.size)
        assertEquals("short", results[0].prompt.id)
        assertEquals("medium", results[1].prompt.id)
    }

    @Test
    fun `aggregate handles missing decode values`() {
        val metrics = listOf(
            run("short", 1, 100.0, null, 50.0),
            run("short", 2, 100.0, 10.0, 50.0)
        )
        val results = BenchmarkMath.aggregate(listOf(shortPrompt), metrics)

        val r = results.first()
        // Only one decode value — median is that value; min/max reflect it.
        assertEquals(10.0, r.medianDecodeTokensPerSec, 1e-6)
        assertEquals(10.0, r.decodeMinTokensPerSec, 1e-6)
        assertEquals(10.0, r.decodeMaxTokensPerSec, 1e-6)
    }

    @Test
    fun `aggregate returns empty for no measured runs`() {
        val results = BenchmarkMath.aggregate(
            listOf(shortPrompt),
            listOf(run("short", 0, 100.0, 10.0, 50.0)) // warm-up only
        )
        assertEquals(0, results.size)
    }
}
