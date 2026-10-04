package com.example.tinymodels.core.inference

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
     * Aggregates raw per-run metrics into per-prompt results, preserving the
     * prompt order. Warm-up runs (runIndex 0) are excluded; prompts with no
     * measured runs are dropped.
     */
    fun aggregate(prompts: List<PromptSpec>, metrics: List<RunMetrics>): List<PromptResult> =
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
                medianPrefillTokensPerSec = median(measured.map { it.prefillTokensPerSec })
            )
        }
}
