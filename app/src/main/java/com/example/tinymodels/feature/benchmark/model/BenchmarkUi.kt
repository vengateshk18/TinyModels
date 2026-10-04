package com.example.tinymodels.feature.benchmark.model

import androidx.compose.runtime.Immutable
import com.example.tinymodels.core.inference.BenchmarkResult

/** A downloaded model file, as an option in the benchmark picker. */
@Immutable
data class BenchmarkFileOption(
    val modelId: String,
    val fileName: String,
    val sizeBytes: Long
)

/** Live progress of the run currently executing. */
@Immutable
data class RunProgress(
    val promptIndex: Int,
    val promptTotal: Int,
    val promptLabel: String,
    val runIndex: Int,
    val runTotal: Int,
    val tokensSoFar: Int,
    val isWarmUp: Boolean
)

/**
 * The single, immutable UI state for the Benchmark screen.
 *  - [Picker] — choose a model + file, review the methodology, start.
 *  - [LoadingModel] — cold-loading the model (timed).
 *  - [Running] — prompt loop in flight.
 *  - [Completed] — results ready.
 *  - [Failed] — recoverable error with a way back to the picker.
 */
sealed interface BenchmarkUiState {

    /** Model list still loading from Room. */
    data object Loading : BenchmarkUiState

    /** No benchmark in flight — pick a file and run. */
    data class Picker(
        val files: List<BenchmarkFileOption>,
        val selectedFile: BenchmarkFileOption?,
        val canRun: Boolean
    ) : BenchmarkUiState

    /** Cold-loading the selected model (this load is being timed). */
    data class LoadingModel(val modelId: String) : BenchmarkUiState

    /** Prompt loop in flight. */
    data class Running(val progress: RunProgress) : BenchmarkUiState

    /** All runs finished — results ready to show. */
    data class Completed(val result: BenchmarkResult) : BenchmarkUiState

    /** The run failed before producing results. */
    data class Failed(val message: String) : BenchmarkUiState
}
