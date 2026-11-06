package com.ai.tinymodels.core.inference

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure math behind the benchmark metrics. No Android dependencies, so every
 * formula is unit-testable without an engine (see BenchmarkMathTest).
 */
object BenchmarkMath {

    /** Median of [values]; 0.0 for an empty list. Even counts average the middle pair. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /**
     * Time-to-first-token in ms. Uses the first stream emission when present;
     * falls back to total duration when the model produced no output.
     */
    fun ttftMs(startNs: Long, firstEmissionNs: Long, endNs: Long): Double =
        if (firstEmissionNs > startNs) (firstEmissionNs - startNs) / 1_000_000.0
        else (endNs - startNs) / 1_000_000.0

    /**
     * Steady-state decode speed (tokens/sec). LiteRT-LM streams one token per
     * emission, so the token count between the first and last emission is
     * `tokenCount - 1`. Null when fewer than two emissions were observed.
     */
    fun decodeTokensPerSec(tokenCount: Int, firstEmissionNs: Long, lastEmissionNs: Long): Double? {
        if (tokenCount < 2 || lastEmissionNs <= firstEmissionNs) return null
        return (tokenCount - 1) * 1_000_000_000.0 / (lastEmissionNs - firstEmissionNs)
    }

    /**
     * Prefill speed (tokens/sec): prompt tokens processed per unit of TTFT.
     * Approximation — TTFT also includes the first decode step; documented in the UI.
     */
    fun prefillTokensPerSec(promptTokens: Int, ttftMs: Double): Double =
        if (ttftMs <= 0.0) 0.0 else promptTokens * 1000.0 / ttftMs

    /**
     * Linear-interpolated percentile, [p] in 0..1. p50 is the median and
     * matches [median]; p95 is the tail risk used in the report.
     */
    fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val rank = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(rank).toInt()
        val upper = ceil(rank).toInt()
        if (lower == upper) return sorted[lower]
        val weight = rank - lower
        return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
    }

    /** Population standard deviation. 0.0 for fewer than two values. */
    fun stdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }

    /** Spread relative to the mean. 0.0 when the mean is 0 or input is empty. */
    fun coefficientOfVariation(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        if (mean == 0.0) return 0.0
        return stdDev(values) / abs(mean)
    }

    /**
     * How much throughput dropped from the start of a sustained run to the end.
     * Positive means thermal throttling; negative means the device sped up.
     */
    fun throttlePercent(earlyTokensPerSec: Double, lateTokensPerSec: Double): Double {
        if (earlyTokensPerSec <= 0.0) return 0.0
        return (earlyTokensPerSec - lateTokensPerSec) / earlyTokensPerSec * 100.0
    }

    /**
     * Composite 0-100 score, weighted so decode speed dominates (60), then
     * prefill (15), responsiveness (15), and memory overhead (10).
     * Reference anchors: 30 tok/s decode, 300 tok/s prefill, 200 ms TTFT,
     * and a peak footprint at most 1.3x the model file.
     */
    fun compositeScore(
        decodeTokensPerSec: Double,
        prefillTokensPerSec: Double,
        ttftMs: Double,
        peakPssBytes: Long,
        modelFileBytes: Long
    ): Int {
        val decodeScore = 60.0 * (decodeTokensPerSec / 30.0).coerceIn(0.0, 1.0)
        val prefillScore = 15.0 * (prefillTokensPerSec / 300.0).coerceIn(0.0, 1.0)
        // 200 ms -> full marks, 2000 ms -> none.
        val ttftScore = 15.0 * ((2_000.0 - ttftMs) / 1_800.0).coerceIn(0.0, 1.0)
        // 1.3x the model file -> full marks, 3.0x -> none. No file size -> no credit.
        val memScore = if (modelFileBytes > 0L) {
            val ratio = peakPssBytes.toDouble() / modelFileBytes.toDouble()
            10.0 * ((3.0 - ratio) / 1.7).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return (decodeScore + prefillScore + ttftScore + memScore)
            .roundToInt()
            .coerceIn(0, 100)
    }

    /** Letter grade for a [compositeScore]. */
    fun gradeFor(score: Int): String = when {
        score >= 85 -> "S"
        score >= 70 -> "A"
        score >= 55 -> "B"
        score >= 40 -> "C"
        else -> "D"
    }

    /** Run-to-run spread tier; the UI supplies the localized wording. */
    enum class Stability { STABLE, FAIR, UNSTABLE }

    fun stabilityFor(coefficientOfVariation: Double): Stability = when {
        coefficientOfVariation < 0.05 -> Stability.STABLE
        coefficientOfVariation < 0.15 -> Stability.FAIR
        else -> Stability.UNSTABLE
    }

    /**
     * Words for the thermal change, with the sign made explicit.
     *
     * A negative [throttlePercent] means the device sped up, which must not be
     * rendered as a negative "drop". Sub-half-percent movement is noise.
     */
    enum class ThrottleTrend { DROPPED, FASTER, NONE }

    fun throttleFor(throttlePercent: Double): ThrottleTrend = when {
        throttlePercent > NOISE_PERCENT -> ThrottleTrend.DROPPED
        throttlePercent < -NOISE_PERCENT -> ThrottleTrend.FASTER
        else -> ThrottleTrend.NONE
    }

    /** Movement below this counts as measurement noise, not thermal behaviour. */
    private const val NOISE_PERCENT = 0.5

    /**
     * Builds the scored [BenchmarkResult] from the raw run metrics: per-prompt
     * percentiles, the overall median, run-to-run stability, and the composite
     * score derived from all of them.
     */
    fun composeResult(
        modelId: String,
        fileName: String,
        backendUsed: String,
        loadTimeMs: Double,
        baselinePssBytes: Long,
        peakPssBytes: Long,
        modelFileBytes: Long,
        prompts: List<PromptSpec>,
        metrics: List<RunMetrics>,
        sustainedTokensPerSec: Double,
        sustainedThrottlePercent: Double,
        deviceName: String,
        completedAt: Long
    ): BenchmarkResult {
        val promptResults = aggregate(prompts, metrics)
        val measured = promptResults.flatMap { it.measuredRuns }
        val decodes = measured.mapNotNull { it.decodeTokensPerSec }
        val overallDecode = median(decodes)
        val variation = coefficientOfVariation(decodes)
        // Each prompt is weighted equally, so one fast prompt cannot hide a slow one.
        val prefill = median(promptResults.map { it.medianPrefillTokensPerSec })
        val ttft = median(promptResults.map { it.medianTtftMs })
        val score = compositeScore(
            decodeTokensPerSec = overallDecode,
            prefillTokensPerSec = prefill,
            ttftMs = ttft,
            peakPssBytes = peakPssBytes,
            modelFileBytes = modelFileBytes
        )

        return BenchmarkResult(
            modelId = modelId,
            fileName = fileName,
            backendUsed = backendUsed,
            loadTimeMs = loadTimeMs,
            baselinePssBytes = baselinePssBytes,
            peakPssBytes = peakPssBytes,
            promptResults = promptResults,
            overallDecodeTokensPerSec = overallDecode,
            deviceName = deviceName,
            completedAt = completedAt,
            score = score,
            grade = gradeFor(score),
            modelFileBytes = modelFileBytes,
            decodeCoefficientOfVariation = variation,
            stability = stabilityFor(variation),
            sustainedTokensPerSec = sustainedTokensPerSec,
            sustainedThrottlePercent = sustainedThrottlePercent
        )
    }

    /**
     * Summarises a sustained run: average throughput across every sample, plus
     * the drop between the first and second halves (thermal throttling).
     */
    fun sustainSummary(samples: List<Double>): SustainSummary {
        if (samples.isEmpty()) return SustainSummary(0.0, 0.0)
        val half = samples.size / 2
        if (half == 0) return SustainSummary(samples.first(), 0.0)
        val early = samples.subList(0, half).average()
        val late = samples.subList(samples.size - half, samples.size).average()
        return SustainSummary(
            averageTokensPerSec = samples.average(),
            throttlePercent = throttlePercent(early, late)
        )
    }

    /**
     * Aggregates raw per-run metrics into per-prompt results, preserving the
     * prompt order. Warm-up runs (runIndex 0) are excluded; prompts with no
     * measured runs are dropped.
     */
    fun aggregate(
        prompts: List<PromptSpec>, metrics: List<RunMetrics>): List<PromptResult> =
        prompts.mapNotNull { prompt ->
            val measured = metrics.filter { it.promptId == prompt.id && !it.isWarmUp }
            if (measured.isEmpty()) return@mapNotNull null
            val decodes = measured.mapNotNull { it.decodeTokensPerSec }
            PromptResult(
                prompt = prompt,
                measuredRuns = measured,
                medianTtftMs = median(measured.map { it.ttftMs }),
                medianDecodeTokensPerSec = median(decodes),
                decodeMinTokensPerSec = decodes.minOrNull() ?: 0.0,
                decodeMaxTokensPerSec = decodes.maxOrNull() ?: 0.0,
                medianPrefillTokensPerSec = median(measured.map { it.prefillTokensPerSec }),
                decodeP95TokensPerSec = percentile(decodes, 0.95),
                decodeStdDev = stdDev(decodes),
                ttftP95Ms = percentile(measured.map { it.ttftMs }, 0.95)
            )
        }
}

/** Outcome of the sustained (thermal) phase. */
data class SustainSummary(
    /** Mean decode speed across the whole sustained phase. */
    val averageTokensPerSec: Double,
    /** Throughput lost between the first and second half. Positive = throttled. */
    val throttlePercent: Double
)
