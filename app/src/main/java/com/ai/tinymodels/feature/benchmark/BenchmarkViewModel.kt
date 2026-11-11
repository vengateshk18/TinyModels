package com.ai.tinymodels.feature.benchmark

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.MemoryUtils
import com.ai.tinymodels.core.inference.BenchmarkMath
import com.ai.tinymodels.core.inference.BenchmarkProtocol
import com.ai.tinymodels.core.inference.BenchmarkResult
import com.ai.tinymodels.core.inference.BenchmarkRunner
import com.ai.tinymodels.core.inference.ModelManager
import com.ai.tinymodels.core.inference.PssMonitor
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.repository.SettingsRepository
import com.ai.tinymodels.feature.benchmark.model.BenchmarkFileOption
import com.ai.tinymodels.feature.benchmark.model.BenchmarkUiState
import com.ai.tinymodels.feature.benchmark.model.RunProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Orchestrates the benchmark: model/file selection, cold load (timed),
 * the fixed prompt loop, PSS monitoring, and result aggregation.
 *
 * The benchmark swaps the shared [ModelManager] engine (same as switching
 * models in chat), so any open chat's ConversationSession becomes stale —
 * ChatViewModel rebuilds its session on next use.
 */
@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val modelManager: ModelManager,
    private val modelRepository: ModelRepository,
    private val settingsRepository: SettingsRepository,
    private val benchmarkRunner: BenchmarkRunner
) : ViewModel() {

    private val _uiState = MutableStateFlow<BenchmarkUiState>(BenchmarkUiState.Loading)
    val uiState: StateFlow<BenchmarkUiState> = _uiState.asStateFlow()

    private var benchmarkJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val options = modelRepository.observeDownloadedFiles().first().map {
                BenchmarkFileOption(modelId = it.modelId, fileName = it.fileName, sizeBytes = it.sizeBytes)
            }
            _uiState.value = BenchmarkUiState.Picker(
                files = options,
                selectedFile = options.firstOrNull(),
                canRun = options.isNotEmpty()
            )
        }
    }

    /** Selects a specific file in the picker. */
    fun selectFile(file: BenchmarkFileOption) {
        val state = _uiState.value as? BenchmarkUiState.Picker ?: return
        _uiState.value = state.copy(selectedFile = file)
    }

    /**
     * Runs the full benchmark on the selected model + file:
     *  1. OOM guard.
     *  2. Cold load (unload → load, timed) — the load-time metric.
     *  3. For each prompt × 3 runs: measure (run 0 = warm-up, discarded).
     *  4. Aggregate medians → [BenchmarkUiState.Completed].
     */
    fun runBenchmark() {
        val state = _uiState.value as? BenchmarkUiState.Picker ?: return
        val selected = state.selectedFile ?: return
        val modelId = selected.modelId
        val fileName = selected.fileName

        if (benchmarkJob?.isActive == true) return
        benchmarkJob = viewModelScope.launch(Dispatchers.Default) {
            // ---- Resolve the file on disk ----
            val downloaded = modelRepository.getDownloadedModel(modelId)
            val fileRecord = modelRepository.getModelFile(modelId, fileName)
            if (downloaded == null || fileRecord == null) {
                _uiState.value = BenchmarkUiState.Failed(context.getString(R.string.benchmark_error_model_file_missing))
                return@launch
            }
            val modelFile = File(File(downloaded.localPath), fileRecord.fileName.substringAfterLast("/"))
            if (!modelFile.exists()) {
                _uiState.value = BenchmarkUiState.Failed(context.getString(R.string.benchmark_error_model_file_missing))
                return@launch
            }

            // ---- OOM guard (same policy as chat) ----
            val snapshot = MemoryUtils.snapshot(context)
            val required = MemoryUtils.requiredBytesForModel(modelFile)
            if (!MemoryUtils.hasHeadroom(snapshot, required)) {
                _uiState.value = BenchmarkUiState.Failed(
                    context.getString(
                        R.string.benchmark_error_not_enough_ram,
                        required / (1024 * 1024),
                        snapshot.availableBytes / (1024 * 1024)
                    )
                )
                return@launch
            }

            _uiState.value = BenchmarkUiState.LoadingModel(modelId)

            // ---- PSS monitoring across the load + run ----
            // Baseline must be sampled with NO model resident, otherwise it
            // already includes the model we are about to measure and the
            // overhead figure collapses to zero.
            val pssMonitor = PssMonitor(context, this)

            try {
                // ---- Cold load, timed ----
                modelManager.unloadModel()
                // Give the freed weight buffers a moment to be reclaimed
                // before the baseline sample, so it reflects app overhead.
                delay(PSS_SETTLE_MS)
                pssMonitor.start()

                val loadStartNs = System.nanoTime()
                val settings = settingsRepository.settings.first()
                val loadResult = modelManager.loadModel(
                    modelId = modelId,
                    modelFile = modelFile,
                    backend = settings.defaultBackend,
                    maxNumTokens = ModelManager.DEFAULT_MAX_TOKENS
                )
                val loadTimeMs = (System.nanoTime() - loadStartNs) / 1_000_000.0
                when (loadResult) {
                    is AppResult.Error -> {
                        _uiState.value = BenchmarkUiState.Failed(
                            loadResult.error.message ?: context.getString(R.string.benchmark_error_failed_to_load)
                        )
                        return@launch
                    }
                    is AppResult.Success -> Unit
                }
                val backendUsed = modelManager.loadedModelId?.let {
                    (modelManager.engineState.value as? ModelManager.EngineState.Ready)
                        ?.model?.backendUsed?.name
                } ?: settings.defaultBackend.name

                // ---- Prompt suite: 1 warm-up + N measured runs per prompt ----
                val prompts = BenchmarkProtocol.PROMPTS
                val runsPerPrompt = BenchmarkProtocol.WARM_UP_RUNS + BenchmarkProtocol.MEASURED_RUNS
                val totalSuiteRuns = prompts.size * runsPerPrompt
                var completedRuns = 0
                val allMetrics = mutableListOf<com.ai.tinymodels.core.inference.RunMetrics>()

                prompts.forEachIndexed { promptIndex, prompt ->
                    repeat(runsPerPrompt) { runIndex ->
                        _uiState.value = BenchmarkUiState.Running(
                            RunProgress(
                                promptIndex = promptIndex,
                                promptTotal = prompts.size,
                                promptLabelRes = when (prompt.id) {
                                    "short" -> R.string.benchmark_prompt_short
                                    "medium" -> R.string.benchmark_prompt_medium
                                    else -> R.string.benchmark_prompt_long
                                },
                                runIndex = runIndex,
                                runTotal = runsPerPrompt,
                                tokensSoFar = 0,
                                isWarmUp = BenchmarkProtocol.isWarmUp(runIndex),
                                phase = RunProgress.Phase.SUITE,
                                overallFraction = SUITE_WEIGHT * completedRuns / totalSuiteRuns
                            )
                        )
                        allMetrics += benchmarkRunner.runOnce(prompt, runIndex) { tokens ->
                            // Live token counter without flooding state updates:
                            // only bump the Running state's token count.
                            val current = _uiState.value as? BenchmarkUiState.Running
                            if (current != null) {
                                _uiState.value = current.copy(
                                    progress = current.progress.copy(tokensSoFar = tokens)
                                )
                            }
                        }
                        completedRuns++
                    }
                }

                // ---- Sustained phase: is the device throttling? ----
                val sustainSamples = benchmarkRunner.runSustained { elapsedMs, _ ->
                    val fraction = (elapsedMs.toFloat() / BenchmarkProtocol.SUSTAIN_DURATION_MS)
                        .coerceIn(0f, 1f)
                    _uiState.value = BenchmarkUiState.Running(
                        RunProgress(
                            promptIndex = prompts.lastIndex,
                            promptTotal = prompts.size,
                            promptLabelRes = R.string.benchmark_phase_thermal,
                            runIndex = 0,
                            runTotal = 1,
                            tokensSoFar = 0,
                            isWarmUp = false,
                            phase = RunProgress.Phase.SUSTAINED,
                            overallFraction = SUITE_WEIGHT + (1f - SUITE_WEIGHT) * fraction,
                            sustainedRemainingSec =
                                ((BenchmarkProtocol.SUSTAIN_DURATION_MS - elapsedMs) / 1000L)
                                    .toInt().coerceAtLeast(0)
                        )
                    )
                }
                val sustain = BenchmarkMath.sustainSummary(sustainSamples)

                // ---- Aggregate + score ----
                _uiState.value = BenchmarkUiState.Completed(
                    BenchmarkMath.composeResult(
                        modelId = modelId,
                        fileName = fileName,
                        backendUsed = backendUsed,
                        loadTimeMs = loadTimeMs,
                        baselinePssBytes = pssMonitor.baselineBytes,
                        peakPssBytes = pssMonitor.peakBytes,
                        modelFileBytes = modelFile.length(),
                        prompts = prompts,
                        metrics = allMetrics,
                        sustainedTokensPerSec = sustain.averageTokensPerSec,
                        sustainedThrottlePercent = sustain.throttlePercent,
                        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                        completedAt = System.currentTimeMillis()
                    )
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                // Never crash the app from a benchmark run (e.g. a native
                // conversation failure on a text-only model) — report it.
                android.util.Log.e("Benchmark", "Benchmark failed: ${throwable.message}", throwable)
                _uiState.value = BenchmarkUiState.Failed(
                    throwable.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.benchmark_failed_title)
                )
            } finally {
                pssMonitor.stop()
            }
        }
    }

    /** Cancels the in-flight benchmark and returns to the picker. */
    fun cancel() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        _uiState.value = BenchmarkUiState.Picker(
            files = emptyList(),
            selectedFile = null,
            canRun = false
        )
        // Re-populate the picker from Room.
        viewModelScope.launch(Dispatchers.Default) {
            val options = modelRepository.observeDownloadedFiles().first().map {
                BenchmarkFileOption(it.modelId, it.fileName, it.sizeBytes)
            }
            _uiState.value = BenchmarkUiState.Picker(
                files = options,
                selectedFile = options.firstOrNull(),
                canRun = options.isNotEmpty()
            )
        }
    }

    /** Returns to the picker from a Failed/Completed state. */
    fun backToPicker() {
        if (_uiState.value is BenchmarkUiState.Completed ||
            _uiState.value is BenchmarkUiState.Failed
        ) {
            // Re-populate the picker (keeps the previous selection if possible).
            viewModelScope.launch(Dispatchers.Default) {
                val options = modelRepository.observeDownloadedFiles().first().map {
                    BenchmarkFileOption(it.modelId, it.fileName, it.sizeBytes)
                }
                _uiState.value = BenchmarkUiState.Picker(
                    files = options,
                    selectedFile = options.firstOrNull(),
                    canRun = options.isNotEmpty()
                )
            }
        }
    }

    private companion object {
        /** Share of the progress bar taken by the prompt suite; the rest is thermal. */
        const val SUITE_WEIGHT = 0.8f

        /** Settle time after unloading, before the memory baseline is sampled. */
        const val PSS_SETTLE_MS = 600L
    }
}
