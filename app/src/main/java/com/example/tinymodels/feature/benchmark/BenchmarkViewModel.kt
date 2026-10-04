package com.example.tinymodels.feature.benchmark

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.core.common.MemoryUtils
import com.example.tinymodels.core.inference.BenchmarkMath
import com.example.tinymodels.core.inference.BenchmarkResult
import com.example.tinymodels.core.inference.BenchmarkRunner
import com.example.tinymodels.core.inference.ModelManager
import com.example.tinymodels.core.inference.PssMonitor
import com.example.tinymodels.domain.repository.ModelRepository
import com.example.tinymodels.domain.repository.SettingsRepository
import com.example.tinymodels.feature.benchmark.model.BenchmarkFileOption
import com.example.tinymodels.feature.benchmark.model.BenchmarkUiState
import com.example.tinymodels.feature.benchmark.model.RunProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
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
        viewModelScope.launch {
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
        benchmarkJob = viewModelScope.launch {
            // ---- Resolve the file on disk ----
            val downloaded = modelRepository.getDownloadedModel(modelId)
            val fileRecord = modelRepository.getModelFile(modelId, fileName)
            if (downloaded == null || fileRecord == null) {
                _uiState.value = BenchmarkUiState.Failed("Model file missing on device")
                return@launch
            }
            val modelFile = File(File(downloaded.localPath), fileRecord.fileName.substringAfterLast("/"))
            if (!modelFile.exists()) {
                _uiState.value = BenchmarkUiState.Failed("Model file missing on device")
                return@launch
            }

            // ---- OOM guard (same policy as chat) ----
            val snapshot = MemoryUtils.snapshot(context)
            val required = MemoryUtils.requiredBytesForModel(modelFile)
            if (!MemoryUtils.hasHeadroom(snapshot, required)) {
                _uiState.value = BenchmarkUiState.Failed(
                    "Not enough free RAM to load this model " +
                        "(need ~${required / (1024 * 1024)} MB, have " +
                        "${snapshot.availableBytes / (1024 * 1024)} MB). " +
                        "Close other apps and try again."
                )
                return@launch
            }

            _uiState.value = BenchmarkUiState.LoadingModel(modelId)

            // ---- PSS monitoring across the whole run ----
            val pssMonitor = PssMonitor(context, this)
            pssMonitor.start()

            try {
                // ---- Cold load, timed ----
                modelManager.unloadModel()
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
                            loadResult.error.message ?: "Failed to load model"
                        )
                        return@launch
                    }
                    is AppResult.Success -> Unit
                }
                val backendUsed = modelManager.loadedModelId?.let {
                    (modelManager.engineState.value as? ModelManager.EngineState.Ready)
                        ?.model?.backendUsed?.name
                } ?: settings.defaultBackend.name

                // ---- Prompt loop ----
                val allMetrics = mutableListOf<com.example.tinymodels.core.inference.RunMetrics>()
                val prompts = BenchmarkRunner.PROMPTS
                prompts.forEachIndexed { promptIndex, prompt ->
                    repeat(BenchmarkRunner.RUNS_PER_PROMPT) { runIndex ->
                        _uiState.value = BenchmarkUiState.Running(
                            RunProgress(
                                promptIndex = promptIndex,
                                promptTotal = prompts.size,
                                promptLabel = prompt.label,
                                runIndex = runIndex,
                                runTotal = BenchmarkRunner.RUNS_PER_PROMPT,
                                tokensSoFar = 0,
                                isWarmUp = runIndex == 0
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
                    }
                }

                // ---- Aggregate ----
                val promptResults = BenchmarkMath.aggregate(prompts, allMetrics)
                val overallDecode = promptResults
                    .flatMap { it.measuredRuns.mapNotNull { m -> m.decodeTokensPerSec } }
                    .let { BenchmarkMath.median(it) }

                _uiState.value = BenchmarkUiState.Completed(
                    BenchmarkResult(
                        modelId = modelId,
                        fileName = fileName,
                        backendUsed = backendUsed,
                        loadTimeMs = loadTimeMs,
                        baselinePssBytes = pssMonitor.baselineBytes,
                        peakPssBytes = pssMonitor.peakBytes,
                        promptResults = promptResults,
                        overallDecodeTokensPerSec = overallDecode,
                        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                        completedAt = System.currentTimeMillis()
                    )
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
        viewModelScope.launch {
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
            viewModelScope.launch {
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
}
