package com.ai.tinymodels.feature.benchmark

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.tinymodels.core.inference.BenchmarkMath
import com.ai.tinymodels.core.inference.BenchmarkProtocol
import com.ai.tinymodels.core.inference.BenchmarkResult
import com.ai.tinymodels.core.inference.PromptResult
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.core.ui.components.ErrorState
import com.ai.tinymodels.feature.benchmark.model.BenchmarkFileOption
import com.ai.tinymodels.feature.benchmark.model.BenchmarkUiState
import com.ai.tinymodels.feature.benchmark.model.RunProgress

/**
 * Full-screen benchmark.
 *
 * The screen mirrors the protocol: a picker that explains what will be
 * measured, a live run view, and a scored result built from median/p95
 * throughput, memory overhead, and thermal behaviour.
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

    var showCancelDialog by remember { mutableStateOf(false) }
    BackHandler(enabled = running) { showCancelDialog = true }

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Benchmark", fontWeight = FontWeight.Bold)
                        Text(
                            text = when (state) {
                                is BenchmarkUiState.Completed -> "Result"
                                is BenchmarkUiState.Running -> "Measuring"
                                is BenchmarkUiState.LoadingModel -> "Loading model"
                                else -> "Inference speed on this device"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (running) showCancelDialog = true else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Pinned so the primary action never scrolls out of reach.
            val s = state
            if (s is BenchmarkUiState.Picker && s.files.isNotEmpty()) {
                RunBar(
                    canRun = s.canRun,
                    selectedFile = s.selectedFile,
                    onRun = viewModel::runBenchmark
                )
            }
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
                modifier = Modifier.fillMaxSize().padding(padding)
            )

            is BenchmarkUiState.LoadingModel -> LoadingModelContent(
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
    onSelectFile: (BenchmarkFileOption) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        HeroCard()

        Spacer(modifier = Modifier.height(24.dp))

        if (state.files.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "No models yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Download a model from the Models tab, then come back to measure it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            return@Column
        }

        SectionHeading("Choose a model", "${state.files.size} available")
        Spacer(modifier = Modifier.height(10.dp))

        state.files.forEach { file ->
            ModelOptionCard(
                file = file,
                selected = file == state.selectedFile,
                onClick = { onSelectFile(file) }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))

        SectionHeading("What gets measured", null)
        Spacer(modifier = Modifier.height(10.dp))
        MetricGrid(
            tiles = listOf(
                MetricTileData(Icons.Filled.Speed, "Decode", "tokens / second"),
                MetricTileData(Icons.Filled.Timer, "Time to first token", "responsiveness"),
                MetricTileData(Icons.Filled.Bolt, "Prefill", "prompt processing"),
                MetricTileData(Icons.Filled.Memory, "Peak memory", "footprint")
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        ProtocolCard()

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Pinned primary action. Stays visible while the picker scrolls, so the user
 * never has to hunt for the button that starts the run.
 */
@Composable
private fun RunBar(
    canRun: Boolean,
    selectedFile: BenchmarkFileOption?,
    onRun: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            selectedFile?.let {
                Text(
                    "Measuring ${it.fileName.substringAfterLast("/")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(
                onClick = onRun,
                enabled = canRun,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Run benchmark", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Usually 3–10 min, depending on the model",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HeroCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.secondaryContainer
                    )
                )
            )
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "How fast is this phone, really?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Runs the same three prompts at greedy decoding, discards a warm-up " +
                    "pass, then measures steady-state speed, memory, and thermal drop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroChip("${BenchmarkProtocol.PROMPTS.size} prompts")
                HeroChip("${BenchmarkProtocol.MEASURED_RUNS} runs each")
                HeroChip("temp 0")
            }
        }
    }
}

@Composable
private fun HeroChip(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ModelOptionCard(
    file: BenchmarkFileOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.fileName.substringAfterLast("/"),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${Formatters.formatBytes(file.sizeBytes)} · ${file.modelId}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun ProtocolCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Methodology",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            BulletPoint(
                "${BenchmarkProtocol.PROMPTS.size} prompts (short → long) to separate " +
                    "time-to-first-token from prefill throughput"
            )
            BulletPoint(
                "1 warm-up + ${BenchmarkProtocol.MEASURED_RUNS} measured runs each, " +
                    "warm-up discarded"
            )
            BulletPoint("Greedy decoding (temperature 0, top-k 1) so runs are repeatable")
            BulletPoint(
                "${BenchmarkProtocol.SUSTAIN_DURATION_MS / 1000}s sustained loop to catch " +
                    "thermal throttling"
            )
            BulletPoint("Peak memory sampled every 250 ms; output capped at " +
                "${BenchmarkProtocol.MAX_OUTPUT_TOKENS} tokens")
        }
    }
}

@Composable
private fun BulletPoint(text: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            "•",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Running
// ---------------------------------------------------------------------------

@Composable
private fun LoadingModelContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(44.dp), strokeWidth = 3.dp)
        Spacer(modifier = Modifier.height(20.dp))
        Text("Loading model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Cold load — this time is part of the score.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RunningContent(
    progress: RunProgress,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sustained = progress.phase == RunProgress.Phase.SUSTAINED

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(0.6f))

        ScoreRing(
            fraction = progress.overallFraction,
            primaryText = "${(progress.overallFraction * 100).toInt()}%",
            secondaryText = if (sustained) "${progress.sustainedRemainingSec}s left" else "measuring",
            accent = MaterialTheme.colorScheme.primary,
            size = 168.dp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            if (sustained) "Thermal check" else "${progress.promptLabel} prompt",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (sustained) {
                "Sustained load — watching for throttling"
            } else {
                "Run ${progress.runIndex + 1} of ${progress.runTotal}" +
                    if (progress.isWarmUp) " · warm-up (discarded)" else ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        PhaseTimeline(sustained = sustained)

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatTile(
                icon = Icons.Filled.Speed,
                label = "Tokens",
                value = if (sustained) "—" else "${progress.tokensSoFar}",
                modifier = Modifier.weight(1f)
            )
            StatTile(
                icon = Icons.Filled.Timer,
                label = "Prompt",
                value = "${progress.promptIndex + 1}/${progress.promptTotal}",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.weight(0.8f))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Cancel")
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun PhaseTimeline(sustained: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PhaseStep("Prompt suite", done = sustained, active = !sustained, modifier = Modifier.weight(1f))
        PhaseStep("Thermal", done = false, active = sustained, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PhaseStep(label: String, done: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    val color = when {
        done -> MaterialTheme.colorScheme.primary
        active -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            } else if (active) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (done || active) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
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
    val accent = gradeColor(result.grade)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // ---- Score hero ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    )
                )
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ScoreRing(
                    fraction = result.score / 100f,
                    primaryText = "${result.score}",
                    secondaryText = "score",
                    accent = accent,
                    size = 156.dp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Surface(color = accent, shape = RoundedCornerShape(50)) {
                    Text(
                        "Grade ${result.grade}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "%.1f tok/s median decode".format(result.overallDecodeTokensPerSec),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${result.fileName.substringAfterLast("/")} · ${result.backendUsed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Headline metrics (median across prompts, matching the score) ----
        val p50Decode = BenchmarkMath.median(result.promptResults.map { it.medianDecodeTokensPerSec })
        val ttft = BenchmarkMath.median(result.promptResults.map { it.medianTtftMs })
        val prefill = BenchmarkMath.median(result.promptResults.map { it.medianPrefillTokensPerSec })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                icon = Icons.Filled.Speed,
                label = "Decode p50",
                value = "%.1f".format(p50Decode),
                suffix = "tok/s",
                modifier = Modifier.weight(1f)
            )
            StatTile(
                icon = Icons.Filled.Timer,
                label = "TTFT p50",
                value = "%.0f".format(ttft),
                suffix = "ms",
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                icon = Icons.Filled.Bolt,
                label = "Prefill p50",
                value = "%.0f".format(prefill),
                suffix = "tok/s",
                modifier = Modifier.weight(1f)
            )
            StatTile(
                icon = Icons.Filled.Memory,
                label = "Peak memory",
                value = Formatters.formatBytes(result.peakPssBytes),
                suffix = null,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ---- Per-prompt breakdown ----
        SectionHeading("By prompt", "p50 · p95")
        Spacer(modifier = Modifier.height(12.dp))
        val maxDecode = result.promptResults.maxOfOrNull { it.medianDecodeTokensPerSec } ?: 1.0
        result.promptResults.forEach { pr ->
            PromptBar(pr, maxDecode, accent)
            Spacer(modifier = Modifier.height(14.dp))
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ---- Thermal ----
        SectionHeading("Thermal", "sustained load")
        Spacer(modifier = Modifier.height(10.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Thermostat,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = accent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        thermalVerdict(result.sustainedThrottlePercent),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                InfoRow(
                    "Sustained speed",
                    "%.1f tok/s".format(result.sustainedTokensPerSec)
                )
                InfoRow(
                    "Change over run",
                    BenchmarkMath.throttleLabel(result.sustainedThrottlePercent)
                )
                InfoRow(
                    "Run-to-run spread",
                    "${result.stability} (%.1f%%)".format(
                        result.decodeCoefficientOfVariation * 100
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Model + device ----
        SectionHeading("Model", null)
        Spacer(modifier = Modifier.height(10.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                InfoRow("Cold load", "%.1f s".format(result.loadTimeMs / 1000.0))
                InfoRow("Model size", Formatters.formatBytes(result.modelFileBytes))
                InfoRow("Peak memory", Formatters.formatBytes(result.peakPssBytes))
                // Peak-vs-model ratio is stable; a same-process "baseline" is not,
                // because freed weight buffers are not returned to the OS.
                InfoRow(
                    "Memory / model",
                    if (result.modelFileBytes > 0L) {
                        "%.2f×".format(
                            result.peakPssBytes.toDouble() / result.modelFileBytes
                        )
                    } else {
                        "—"
                    }
                )
                InfoRow("Backend", result.backendUsed)
                InfoRow("Device", result.deviceName)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Tokens are counted from stream emissions. Prefill is prompt tokens / " +
                "time-to-first-token and includes the first decode step. Scores are relative " +
                "to a reference phone, not absolute quality.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { copySummary(context, result) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Copy")
            }
            Button(
                onClick = onRerun,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Re-run")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onBackToPicker, modifier = Modifier.fillMaxWidth()) {
            Text("Back to models")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun PromptBar(result: PromptResult, maxDecode: Double, accent: Color) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                result.prompt.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                "%.1f · %.1f tok/s".format(
                    result.medianDecodeTokensPerSec,
                    result.decodeP95TokensPerSec
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(
                        (result.medianDecodeTokensPerSec / maxDecode.coerceAtLeast(0.001))
                            .toFloat().coerceIn(0.02f, 1f)
                    )
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(accent)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "TTFT %.0f ms · p95 %.0f ms".format(result.medianTtftMs, result.ttftP95Ms),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun ScoreRing(
    fraction: Float,
    primaryText: String,
    secondaryText: String,
    accent: Color,
    size: androidx.compose.ui.unit.Dp
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.075f
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                this.size.width - stroke,
                this.size.height - stroke
            )
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = StrokeCap.Round
                )
            )
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = StrokeCap.Round
                )
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                primaryText,
                fontSize = (size.value * 0.24f).sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                secondaryText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeading(title: String, trailing: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        trailing?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class MetricTileData(val icon: ImageVector, val label: String, val caption: String)

@Composable
private fun MetricGrid(tiles: List<MetricTileData>) {
    tiles.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { tile ->
                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Icon(
                            tile.icon,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            tile.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            tile.caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    suffix: String? = null
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                suffix?.let {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun gradeColor(grade: String): Color {
    val scheme: ColorScheme = MaterialTheme.colorScheme
    return when (grade) {
        "S", "A" -> scheme.primary
        "B" -> scheme.tertiary
        else -> scheme.error
    }
}

private fun thermalVerdict(throttlePercent: Double): String = when {
    throttlePercent <= 2.0 -> "Holds full speed"
    throttlePercent <= 8.0 -> "Slight thermal drop"
    throttlePercent <= 18.0 -> "Noticeable throttling"
    else -> "Heavy throttling"
}

private fun copySummary(context: Context, result: BenchmarkResult) {
    val sb = StringBuilder()
    sb.append(
        "TinyModels benchmark — ${result.modelId.substringAfterLast('/')} " +
            "(${result.fileName.substringAfterLast('/')}, ${result.backendUsed})\n"
    )
    sb.append("Score: ${result.score} (${result.grade}) · " +
        "%.1f tok/s median decode\n".format(result.overallDecodeTokensPerSec))
    result.promptResults.forEach { pr ->
        sb.append(
            "  ${pr.prompt.label}: TTFT %.0fms (p95 %.0f), decode %.1f tok/s (p95 %.1f, sd %.1f), prefill %.0f tok/s".format(
                pr.medianTtftMs,
                pr.ttftP95Ms,
                pr.medianDecodeTokensPerSec,
                pr.decodeP95TokensPerSec,
                pr.decodeStdDev,
                pr.medianPrefillTokensPerSec
            )
        )
        sb.append("\n")
    }
    sb.append(
        "Sustained: %.1f tok/s, %s (%s)\n".format(
            result.sustainedTokensPerSec,
            BenchmarkMath.throttleLabel(result.sustainedThrottlePercent),
            result.stability
        )
    )
    sb.append(
        "Load %.1fs · peak PSS %s · model %s".format(
            result.loadTimeMs / 1000.0,
            Formatters.formatBytes(result.peakPssBytes),
            Formatters.formatBytes(result.modelFileBytes)
        )
    )
    sb.append("Device: ${result.deviceName}")

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("benchmark", sb.toString()))
}
