package com.ai.tinymodels.feature.benchmark

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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.TinyConstants
import com.ai.tinymodels.core.inference.BenchmarkResult
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.core.ui.components.ErrorState
import com.ai.tinymodels.feature.benchmark.model.BenchmarkFileOption
import com.ai.tinymodels.feature.benchmark.model.BenchmarkUiState

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
                title = { Text(stringResource(R.string.benchmark_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (running) showCancelDialog = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                onSelectFile = viewModel::selectFile,
                onRun = viewModel::runBenchmark,
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            is BenchmarkUiState.LoadingModel -> StageContent(
                title = stringResource(R.string.benchmark_loading_title),
                subtitle = stringResource(R.string.benchmark_loading_subtitle),
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
                title = stringResource(R.string.benchmark_failed_title),
                message = s.message,
                onRetry = viewModel::backToPicker,
                retryLabel = stringResource(R.string.benchmark_back_to_models),
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }

    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text(stringResource(R.string.benchmark_stop_title)) },
            text = { Text(stringResource(R.string.benchmark_stop_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancel()
                    showCancelDialog = false
                }) { Text(stringResource(R.string.benchmark_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text(stringResource(R.string.benchmark_continue)) }
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
    onSelectFile: (BenchmarkFileOption) -> Unit,
    onRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        if (state.files.isEmpty()) {
            Text(
                stringResource(R.string.benchmark_no_models),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            return@Column
        }

        Text(
            stringResource(R.string.benchmark_choose_model),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        state.files.forEach { file ->
            val isSelected = file == state.selectedFile
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer
                ),
                onClick = { onSelectFile(file) }
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                file.fileName.substringAfterLast("/"),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "${file.modelId} • ${Formatters.formatBytes(file.sizeBytes, LocalContext.current.resources)}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
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
                    stringResource(R.string.benchmark_how_it_works),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                BulletPoint(stringResource(R.string.benchmark_bullet_prompts))
                BulletPoint(stringResource(R.string.benchmark_bullet_output_cap))
                BulletPoint(stringResource(R.string.benchmark_bullet_warmup))
                BulletPoint(stringResource(R.string.benchmark_bullet_median))
                BulletPoint(stringResource(R.string.benchmark_bullet_sampler))
                BulletPoint(stringResource(R.string.benchmark_bullet_load_time))
                BulletPoint(stringResource(R.string.benchmark_bullet_memory))
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
            Text(stringResource(R.string.benchmark_run))
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
    progress: com.ai.tinymodels.feature.benchmark.model.RunProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Animated indicator
        CircularProgressIndicator(modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            stringResource(R.string.benchmark_running),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Current stage chip
        val stageLabel = buildString {
            append(stringResource(R.string.benchmark_progress_prompt, progress.promptLabel))
            append(stringResource(R.string.benchmark_progress_run, progress.runIndex + 1, progress.runTotal))
            if (progress.isWarmUp) append(stringResource(R.string.benchmark_progress_warmup))
        }
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large
        ) {
            Text(
                stageLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // ---- Progress card ----
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Overall progress across prompts × runs.
                val totalRuns = progress.promptTotal * progress.runTotal
                val doneRuns = progress.promptIndex * progress.runTotal + progress.runIndex
                val fraction = doneRuns.toFloat() / totalRuns

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.benchmark_overall_progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.common_percent, (fraction * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProgressStat(stringResource(R.string.benchmark_stat_prompt), "${progress.promptIndex + 1}/${progress.promptTotal}", Modifier.weight(1f))
                    ProgressStat(stringResource(R.string.benchmark_stat_run), "${progress.runIndex + 1}/${progress.runTotal}", Modifier.weight(1f))
                    ProgressStat(stringResource(R.string.benchmark_stat_tokens), "${progress.tokensSoFar}", Modifier.weight(1f))
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Cancel pinned to the bottom.
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.benchmark_cancel))
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun ProgressStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun BulletPoint(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            "•",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
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
                    stringResource(R.string.benchmark_decode_speed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.benchmark_format_tok_per_sec, result.overallDecodeTokensPerSec),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                val spread = result.promptResults
                    .flatMap { it.measuredRuns.mapNotNull { m -> m.decodeTokensPerSec } }
                if (spread.size >= 2) {
                    Text(
                        stringResource(
                            R.string.benchmark_decode_speed_range,
                            spread.minOrNull() ?: 0.0,
                            spread.maxOrNull() ?: 0.0
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
            SectionTitle(stringResource(R.string.benchmark_progress_prompt, pr.prompt.label))
            InfoRow(
                stringResource(R.string.benchmark_stat_time_to_first_token),
                stringResource(R.string.benchmark_format_ms, pr.medianTtftMs)
            )
            InfoRow(
                stringResource(R.string.benchmark_decode_speed),
                stringResource(R.string.benchmark_format_tok_per_sec, pr.medianDecodeTokensPerSec)
            )
            InfoRow(
                stringResource(R.string.benchmark_stat_prefill_speed),
                stringResource(R.string.benchmark_format_tok_per_sec, pr.medianPrefillTokensPerSec)
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // ---- Load + memory ----
        SectionTitle(stringResource(R.string.benchmark_section_model))
        InfoRow(
            stringResource(R.string.benchmark_stat_load_time),
            stringResource(R.string.benchmark_format_seconds, result.loadTimeMs / 1000.0)
        )
        InfoRow(
            stringResource(R.string.benchmark_stat_peak_memory),
            Formatters.formatBytes(result.peakPssBytes, LocalContext.current.resources)
        )
        InfoRow(
            stringResource(R.string.benchmark_stat_baseline_load),
            Formatters.formatBytes(result.baselinePssBytes, LocalContext.current.resources)
        )
        InfoRow(stringResource(R.string.benchmark_stat_backend), result.backendUsed)
        InfoRow(stringResource(R.string.benchmark_stat_device), result.deviceName)

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.benchmark_token_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { copySummary(context, result) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.benchmark_copy_summary))
            }
            Button(onClick = onRerun, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.benchmark_rerun))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onBackToPicker, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.benchmark_back_to_models))
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
        context.getString(
            R.string.benchmark_summary_header,
            result.modelId.substringAfterLast('/'),
            result.fileName.substringAfterLast('/'),
            result.backendUsed
        ) + "\n"
    )
    sb.append(context.getString(R.string.benchmark_summary_load, result.loadTimeMs / 1000.0))
    result.promptResults.forEach { pr ->
        sb.append(
            context.getString(
                R.string.benchmark_summary_prompt,
                pr.prompt.label,
                pr.medianTtftMs,
                pr.medianDecodeTokensPerSec,
                pr.medianPrefillTokensPerSec
            )
        )
    }
    sb.append("\n")
    sb.append(
        context.getString(
            R.string.benchmark_summary_peak,
            Formatters.formatBytes(result.peakPssBytes, context.resources),
            Formatters.formatBytes(result.baselinePssBytes, context.resources)
        ) + "\n"
    )
    sb.append(context.getString(R.string.benchmark_summary_device, result.deviceName))

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(TinyConstants.CLIP_LABEL_BENCHMARK, sb.toString()))
}

/** Clickable without ripple — used for the compact file rows inside a card. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(
        interactionSource = MutableInteractionSource(),
        indication = null,
        onClick = onClick
    )
