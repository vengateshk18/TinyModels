package com.example.tinymodels.core.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import com.example.tinymodels.domain.model.SamplerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A fixed benchmark prompt. */
data class PromptSpec(val id: String, val label: String, val text: String)

/** Per-run measurements for a single prompt execution. */
data class RunMetrics(
    val promptId: String,
    /** 0-based; run 0 is the warm-up and excluded from aggregation. */
    val runIndex: Int,
    val isWarmUp: Boolean,
    val ttftMs: Double,
    /** Null when fewer than two emissions were observed. */
    val decodeTokensPerSec: Double?,
    val prefillTokensPerSec: Double,
    val outputTokens: Int,
    val durationMs: Double
)

/** Aggregated per-prompt results (warm-up excluded). */
data class PromptResult(
    val prompt: PromptSpec,
    val measuredRuns: List<RunMetrics>,
    val medianTtftMs: Double,
    val medianDecodeTokensPerSec: Double,
    val decodeMinTokensPerSec: Double,
    val decodeMaxTokensPerSec: Double,
    val medianPrefillTokensPerSec: Double
)

/** Full benchmark output for one model on this device. */
data class BenchmarkResult(
    val modelId: String,
    val fileName: String,
    val backendUsed: String,
    val loadTimeMs: Double,
    val baselinePssBytes: Long,
    val peakPssBytes: Long,
    val promptResults: List<PromptResult>,
    /** Median decode speed across ALL measured runs (both prompts). */
    val overallDecodeTokensPerSec: Double,
    val deviceName: String,
    val completedAt: Long
)

/**
 * Executes one benchmark run against the live engine.
 *
 * Methodology (fixed — see ai/BENCHMARK_PLAN.md):
 *  - A fresh [ConversationSession] per run (clean KV cache, no history).
 *  - The prompt is sent and the stream is collected with `System.nanoTime()`
 *    timestamps per emission; LiteRT-LM emits one token per emission, so the
 *    emission count is the token proxy.
 *  - Collection is capped at [MAX_OUTPUT_TOKENS] via [take].
 *  - The whole run executes inside [ModelManager.withEngine], holding the
 *    engine mutex so the model cannot be unloaded mid-run.
 */
@Singleton
class BenchmarkRunner @Inject constructor(
    private val modelManager: ModelManager
) {

    /**
     * Runs [prompt] once (run [runIndex], where 0 = warm-up) and returns the
     * measured metrics. [onToken] fires with the running token count per emission.
     */
    suspend fun runOnce(
        prompt: PromptSpec,
        runIndex: Int,
        maxOutputTokens: Int = MAX_OUTPUT_TOKENS,
        onToken: (Int) -> Unit = {}
    ): RunMetrics = modelManager.withEngine { engine, config ->
        val session = ConversationSession.create(
            engine = engine,
            systemInstruction = null,
            history = emptyList(),
            sampler = BENCHMARK_SAMPLER,
            maxContextTokens = config.maxNumTokens
        )
        try {
            val startNs = System.nanoTime()
            var firstEmissionNs = -1L
            var lastEmissionNs = -1L
            var tokens = 0

            session.send(prompt.text)
                .take(maxOutputTokens)
                .collect {
                    val nowNs = System.nanoTime()
                    if (firstEmissionNs < 0L) firstEmissionNs = nowNs
                    lastEmissionNs = nowNs
                    tokens++
                    onToken(tokens)
                }
            val endNs = System.nanoTime()

            val ttftMs = BenchmarkMath.ttftMs(startNs, firstEmissionNs, endNs)
            RunMetrics(
                promptId = prompt.id,
                runIndex = runIndex,
                isWarmUp = runIndex == 0,
                ttftMs = ttftMs,
                decodeTokensPerSec = BenchmarkMath.decodeTokensPerSec(
                    tokens, firstEmissionNs, lastEmissionNs
                ),
                prefillTokensPerSec = BenchmarkMath.prefillTokensPerSec(
                    ConversationSession.estimateTokens(prompt.text), ttftMs
                ),
                outputTokens = tokens,
                durationMs = (endNs - startNs) / 1_000_000.0
            )
        } finally {
            session.close()
        }
    }

    companion object {
        /** Fixed sampler so every run/model is measured identically. */
        val BENCHMARK_SAMPLER = SamplerSettings(temperature = 0.7, topK = 40, topP = 0.95)

        const val MAX_OUTPUT_TOKENS = 256
        const val RUNS_PER_PROMPT = 3

        /**
         * The fixed prompt set: a short (~9 token) and a medium (~120 token)
         * prompt, so every model/device does the same work.
         */
        val PROMPTS = listOf(
            PromptSpec(
                id = "short",
                label = "Short",
                text = "Describe the sky in one sentence."
            ),
            PromptSpec(
                id = "medium",
                label = "Medium",
                text = "Summarize the following in three sentences: " +
                    "The industrial revolution began in Britain in the late eighteenth century " +
                    "and spread across Europe and North America over the following hundred years. " +
                    "Steam power, new iron production techniques, and the factory system transformed " +
                    "economies that had previously relied on agriculture and handcrafts. Cities grew " +
                    "rapidly as workers moved from rural areas, and new social classes emerged. " +
                    "Railways and steamships connected distant markets, while advances in communication " +
                    "such as the telegraph accelerated trade. Living conditions for many workers were " +
                    "difficult, with long hours, child labor, and crowded housing, but wages and " +
                    "literacy gradually improved over the following decades."
            )
        )
    }
}

/**
 * Samples the process's total PSS (proportional set size) on a timer to
 * capture the PEAK memory during a benchmark, not just a snapshot.
 *
 * PSS is read via [ActivityManager.getProcessMemoryInfo] — a blocking binder
 * call — so reads happen off the main thread. Values are in bytes.
 */
class PssMonitor(private val context: Context, private val scope: CoroutineScope) {

    private var job: Job? = null

    var baselineBytes: Long = 0L
        private set

    var peakBytes: Long = 0L
        private set

    /** Records the baseline and starts the peak-sampling loop. */
    suspend fun start(pollIntervalMs: Long = POLL_INTERVAL_MS) {
        baselineBytes = readPssBytes()
        peakBytes = baselineBytes
        job?.cancel()
        job = scope.launch {
            while (true) {
                delay(pollIntervalMs)
                peakBytes = maxOf(peakBytes, readPssBytes())
            }
        }
    }

    /** Stops sampling. [baselineBytes]/[peakBytes] remain readable. */
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun readPssBytes(): Long = withContext(Dispatchers.Default) {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = activityManager
            .getProcessMemoryInfo(intArrayOf(Process.myPid()))
            .firstOrNull()
        (info?.totalPss ?: 0) * 1024L
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
    }
}
