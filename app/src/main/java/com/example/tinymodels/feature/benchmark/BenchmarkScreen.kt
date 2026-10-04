package com.example.tinymodels.feature.benchmark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tinymodels.core.inference.BenchmarkResult
import com.example.tinymodels.core.ui.Formatters
import com.example.tinymodels.core.ui.components.ErrorState
import com.example.tinymodels.feature.benchmark.model.BenchmarkUiState

/**
 * Full-screen benchmark: pick a downloaded model, run the fixed prompt loop,
 * and see load time, TTFT, decode/prefill speed, and peak memory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    onBack: () -> Unit,
    viewModel: BenchmarkViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current
    val context = LocalContext.current

    // Keep the screen on while a benchmark runs so CPU throttling
    // (screen-off/Doze) doesn't skew the numbers.
    val running = state is BenchmarkUiState.Running || state is BenchmarkUiState.LoadingModel
    view.keepScreenOn = running

    // Guard against abandoning a running benchmark by accident.
    var showCancelDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = running) { showCancelDialog = true }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        topBar = {
            TopAppBar(
                title = { Text("Benchmark") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (running) showCancelDialog = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        when (val s = state) {
            BenchmarkUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            is BenchmarkUiState.Picker -> PickerContent(
                state = s,
                onSelectModel = viewModel::selectModel,
                onSelectFile = viewModel::selectFile,
                onRun = viewModel::runBenchmark,
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            is BenchmarkUiState.LoadingModel -> StageContent(
                title = "Loading model…",
                subtitle = "Cold load — this is being timed.",
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            is BenchmarkUiState.Running -> RunningContent(
                progress = s.progress,
                onCancel = {
                    viewModel.cancel()
                    showCancelDialog = false
                },
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            is BenchmarkUiState.Completed -> ResultContent(
                result = s.result,
                onBackToPicker = viewModel::backToPicker,
                onRerun = viewModel::runBenchmark,
                context = context,
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            is BenchmarkUiState.Failed -> ErrorState(
                title = "Benchmark failed",
                message = s.message,
                onRetry = viewModel::backToPicker,
                retryLabel = "Back to models",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Stop benchmark?") },
            text = { Text("The run will be cancelled and results discarded.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancel()
                    showCancelDialog = false
                }) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Continue") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Picker
// ---------------------------------------------------------------------------

@Composable
private fun PickerContent(
    state: BenchmarkUiState.Picker,
    onSelectModel: (String) -> Unit,
    onSelectFile: (String) -> Unit,
    onRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        if (state.models.isEmpty()) {
            Text(
                "No downloaded models. Download a model first, then come back to benchmark it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            return@Column
        }

        Text(
            "Choose a model",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        state.models.forEach { model ->
            val isSelected = model.modelId == state.selectedModelId
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer
                ),
                onClick = { onSelectModel(model.modelId) }
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Memory,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            model.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (model.files.size > 1 && isSelected) {
                        Spacer(modifier = Modifier.height(8.dp))
                        model.files.forEach { file ->
                            val fileSelected = file.fileName == state.selectedFileName
                            Text(
                                text = file.fileName.substringAfterLast("/")
                                    .removeSuffix(".litertlm")
                                    .uppercase() + " • ${Formatters.formatBytes(file.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (fileSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (fileSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickableNoRipple { onSelectFile(file.fileName) }
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Methodology card ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "How this works",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "2 fixed prompts (short + medium) × 3 runs each, capped at 256 " +
                        "output tokens. The first run is discarded as a warm-up; the median " +
                        "of the remaining runs is reported. Fixed sampler (0.7 / 40 / 0.95). " +
                        "Model load time is one cold load. Peak memory is sampled every " +
                        "250 ms during the run.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onRun,
            enabled = state.canRun,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Run benchmark")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------------------
// Running
// ---------------------------------------------------------------------------

@Composable
private fun StageContent(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RunningContent(
    progress: com.example.tinymodels.feature.benchmark.model.RunProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Running benchmark",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "${progress.promptLabel} prompt • run ${progress.runIndex + 1} of ${progress.runTotal}" +
                (if (progress.isWarmUp) " (warm-up)" else ""),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Overall progress across prompts × runs.
        val totalRuns = progress.promptTotal * progress.runTotal
        val doneRuns = progress.promptIndex * progress.runTotal + progress.runIndex
        LinearProgressIndicator(
            progress = { doneRuns.toFloat() / totalRuns },
            modifier = Modifier.fillMaxWidth().height(8.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Prompt ${progress.promptIndex + 1}/${progress.promptTotal} • " +
                "Run ${progress.runIndex + 1}/${progress.runTotal} • " +
                "${progress.tokensSoFar} tokens",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------

@Composable
private fun ResultContent(
    result: BenchmarkResult,
    onBackToPicker: () -> Unit,
    onRerun: () -> Unit,
    context: Context,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ---- Headline: decode speed ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Decode speed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "%.1f tok/s".format(result.overallDecodeTokensPerSec),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                val spread = result.promptResults
                    .flatMap { it.measuredRuns.mapNotNull { m -> m.decodeTokensPerSec } }
                if (spread.size >= 2) {
                    Text(
                        "%.1f – %.1f across runs".format(
                            spread.minOrNull() ?: 0.0, spread.maxOrNull() ?: 0.0
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Per-prompt metrics ----
        result.promptResults.forEach { pr ->
            SectionTitle("${pr.prompt.label} prompt")
            InfoRow("Time to first token", "%.0f ms".format(pr.medianTtftMs))
            InfoRow("Decode speed", "%.1f tok/s".format(pr.medianDecodeTokensPerSec))
            InfoRow(
                "Prefill speed",
                "%.0f tok/s".format(pr.medianPrefillTokensPerSec)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ---- Load + memory ----
        SectionTitle("Model")
        InfoRow("Load time (cold)", "%.1f s".format(result.loadTimeMs / 1000.0))
        InfoRow("Peak memory (PSS)", Formatters.formatBytes(result.peakPssBytes))
        InfoRow("Baseline before load", Formatters.formatBytes(result.baselinePssBytes))
        InfoRow("Backend", result.backendUsed)
        InfoRow("Device", result.deviceName)

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Tokens are counted from stream emissions (one token per emission). " +
                "Prefill speed is prompt tokens / TTFT and includes the first decode step.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { copySummary(context, result) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Copy summary")
            }
            Button(onClick = onRerun, modifier = Modifier.weight(1f)) {
                Text("Re-run")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onBackToPicker, modifier = Modifier.fillMaxWidth()) {
            Text("Back to models")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ---- Shared bits ----

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun copySummary(context: Context, result: BenchmarkResult) {
    val sb = StringBuilder()
    sb.append(
        "TinyModels benchmark — ${result.modelId.substringAfterLast('/')} " +
            "(${result.fileName.substringAfterLast('/')}, ${result.backendUsed})\n"
    )
    sb.append("Load: %.1fs".format(result.loadTimeMs / 1000.0))
    result.promptResults.forEach { pr ->
        sb.append(
            " · ${pr.prompt.label}: TTFT %.0fms, decode %.1f tok/s, prefill %.0f tok/s".format(
                pr.medianTtftMs, pr.medianDecodeTokensPerSec, pr.medianPrefillTokensPerSec
            )
        )
    }
    sb.append("\n")
    sb.append(
        "Peak PSS: ${Formatters.formatBytes(result.peakPssBytes)} " +
            "(baseline ${Formatters.formatBytes(result.baselinePssBytes)})\n"
    )
    sb.append("Device: ${result.deviceName}")

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("benchmark", sb.toString()))
}

/** Clickable without ripple — used for the compact file rows inside a card. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick
    )
