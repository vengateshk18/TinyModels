package com.ai.tinymodels.core.inference

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import com.google.ai.edge.litertlm.Engine
import com.ai.tinymodels.domain.model.EngineLoadConfig
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
    val medianPrefillTokensPerSec: Double,
    /** 95th-percentile decode speed — the slow tail, not the typical run. */
    val decodeP95TokensPerSec: Double = 0.0,
    /** Population standard deviation of decode speed across measured runs. */
    val decodeStdDev: Double = 0.0,
    /** 95th-percentile time to first token. */
    val ttftP95Ms: Double = 0.0
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
    val completedAt: Long,
    /** Composite 0-100 score and its letter grade. */
    val score: Int = 0,
    val grade: String = "",
    /** Size of the model file on disk — the denominator of the memory ratio. */
    val modelFileBytes: Long = 0L,
    /** Run-to-run spread of decode speed, and the matching human label. */
    val decodeCoefficientOfVariation: Double = 0.0,
    val stability: String = "",
    /** Result of the sustained loop: steady-state speed and thermal drop. */
    val sustainedTokensPerSec: Double = 0.0,
    val sustainedThrottlePercent: Double = 0.0
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
        maxOutputTokens: Int = BenchmarkProtocol.MAX_OUTPUT_TOKENS,
        onToken: (Int) -> Unit = {}
    ): RunMetrics = modelManager.withEngine { engine, config ->
        execute(engine, config, prompt, runIndex, maxOutputTokens, onToken)
    }

    /**
     * Sustained (thermal) phase: repeats the medium prompt until [durationMs]
     * has elapsed and returns the decode speed of each run, in order.
     *
     * The engine is held for the whole phase, so no model swap can perturb it,
     * and a falling tail is the device throttling rather than a cold cache.
     */
    suspend fun runSustained(
        durationMs: Long = BenchmarkProtocol.SUSTAIN_DURATION_MS,
        onProgress: (elapsedMs: Long, tokensPerSec: Double) -> Unit = { _, _ -> }
    ): List<Double> = modelManager.withEngine { engine, config ->
        val prompt = BenchmarkProtocol.PROMPTS.first { it.id == "medium" }
        val samples = mutableListOf<Double>()
        val startedNs = System.nanoTime()
        while ((System.nanoTime() - startedNs) / 1_000_000L < durationMs) {
            // Every sustained run is measured: there is no warm-up left to do.
            val metrics = execute(
                engine = engine,
                config = config,
                prompt = prompt,
                runIndex = BenchmarkProtocol.WARM_UP_RUNS,
                maxOutputTokens = BenchmarkProtocol.MAX_OUTPUT_TOKENS,
                onToken = {}
            )
            val tps = metrics.decodeTokensPerSec ?: continue
            samples += tps
            onProgress((System.nanoTime() - startedNs) / 1_000_000L, tps)
        }
        samples
    }

    private suspend fun execute(
        engine: Engine,
        config: EngineLoadConfig,
        prompt: PromptSpec,
        runIndex: Int,
        maxOutputTokens: Int,
        onToken: (Int) -> Unit
    ): RunMetrics {
        val session = ConversationSession.create(
            engine = engine,
            systemInstruction = null,
            history = emptyList(),
            sampler = BenchmarkProtocol.SAMPLER,
            maxContextTokens = config.maxNumTokens
        )
        Log.i(
            JOURNEY_TAG,
            "JOURNEY: benchmark ${prompt.id} run #$runIndex — session created " +
                "(systemPrompt=<none>, tools=0, history=0), prompt: \"${prompt.text}\""
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
            return RunMetrics(
                promptId = prompt.id,
                runIndex = runIndex,
                isWarmUp = BenchmarkProtocol.isWarmUp(runIndex),
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
        // Same tag as ChatViewModel/WebSearchToolSet so one logcat filter
        // (adb logcat -s TinyModelsJourney) shows the entire turn.
        private const val JOURNEY_TAG = "TinyModelsJourney"
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
