package com.ai.tinymodels.feature.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.domain.model.modelFileDisplayName
import com.ai.tinymodels.feature.home.components.DeviceInsights
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import com.ai.tinymodels.feature.home.model.HomeUiState
import com.ai.tinymodels.feature.home.model.RecommendedModel

/**
 * Home tab. Two states:
 *  - FirstTime (no models): hero, capability verdict, 3-step strip, storage
 *    note, and a recommended model with one-tap download.
 *  - Dashboard (≥1 model): last-used model launchpad, device stats, storage
 *    breakdown, usage stats, and a thermal advisory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBrowseModels: () -> Unit,
    onResumeChat: (String) -> Unit = {},
    onNewChat: (String?) -> Unit = {},
    onManageModels: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val download by viewModel.recommendedDownload.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    // Surface one-shot errors as snackbars.
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    // Tab screen — the outer app Scaffold already consumed the bottom
    // navigation-bar inset. Request only Top + Horizontal.
    val safeTopHorizontal = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

    Scaffold(
        contentWindowInsets = safeTopHorizontal,
        topBar = {
            TopAppBar(
                title = { Text("TinyModels") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (val s = state) {
            HomeUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            is HomeUiState.FirstTime -> FirstTimeHome(
                state = s,
                download = download,
                onDownload = { viewModel.downloadRecommended(it) },
                onCancelDownload = { viewModel.cancelRecommendedDownload(it) },
                onOpenModelPage = { url ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                },
                onOpenSettings = onOpenSettings,
                onBrowseModels = onBrowseModels,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
            is HomeUiState.Dashboard -> DashboardHome(
                state = s,
                onResumeChat = onResumeChat,
                onNewChat = onNewChat,
                onManageModels = onManageModels,
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// First-time (onboarding) state
// ---------------------------------------------------------------------------

@Composable
private fun FirstTimeHome(
    state: HomeUiState.FirstTime,
    download: com.ai.tinymodels.domain.usecase.model.DownloadState?,
    onDownload: (RecommendedModel) -> Unit,
    onCancelDownload: (RecommendedModel) -> Unit,
    onOpenModelPage: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBrowseModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ---- Hero ----
        Text(
            "Run AI models entirely on your phone.",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "No internet. No cloud. No data leaves your device.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Capability verdict card ----
        CapabilityVerdictCard(state)

        Spacer(modifier = Modifier.height(20.dp))

        // ---- 3-step strip ----
        ThreeStepStrip()

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Storage note ----
        Text(
            "Models range from 600 MB to 2 GB. You have " +
                Formatters.formatBytes(state.freeStorageBytes) + " free.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Recommended model card ----
        state.recommended?.let { recommended ->
            RecommendedModelCard(
                recommended = recommended,
                download = download,
                onDownload = onDownload,
                onCancelDownload = onCancelDownload,
                onOpenModelPage = onOpenModelPage,
                onOpenSettings = onOpenSettings
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---- Browse-all fallback ----
        OutlinedButton(onClick = onBrowseModels, modifier = Modifier.fillMaxWidth()) {
            Text("Browse all models")
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun CapabilityVerdictCard(state: HomeUiState.FirstTime) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Your device",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                state.device.aiCapabilityLevel.label + " —",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "can comfortably run up to ${state.device.recommendedMaxParams} models",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Gauge: proportion of RAM usable for AI.
            val fraction = if (state.device.totalRamMb > 0) {
                (state.device.availableRamForAiMb.toFloat() / state.device.totalRamMb)
                    .coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            // The three tiers, with the device's pick highlighted.
            RecommendedModels.all.forEach { model ->
                val isPick = state.recommended?.modelId == model.modelId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    if (isPick) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        "${model.tier}: ${model.displayName} (${Formatters.formatBytes(model.sizeBytes)})",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isPick) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreeStepStrip() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StepItem("1", "Download", "Takes a few minutes", Modifier.weight(1f))
        StepItem("2", "Load", "First load can be slow", Modifier.weight(1f))
        StepItem("3", "Chat offline", "Stays on your device", Modifier.weight(1f))
    }
}

@Composable
private fun StepItem(
    number: String,
    title: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                number,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecommendedModelCard(
    recommended: RecommendedModel,
    download: com.ai.tinymodels.domain.usecase.model.DownloadState?,
    onDownload: (RecommendedModel) -> Unit,
    onCancelDownload: (RecommendedModel) -> Unit,
    onOpenModelPage: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Recommended for your device",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    recommended.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${recommended.tier} tier • ${Formatters.formatBytes(recommended.sizeBytes)} • ${recommended.fileName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(12.dp))

            when {
                download == null || download.status == DownloadStatus.IDLE -> {
                    Button(
                        onClick = { onDownload(recommended) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Download (${Formatters.formatBytes(recommended.sizeBytes)})")
                    }
                }
                download.status == DownloadStatus.COMPLETED -> {
                    // State flips to Dashboard automatically; brief confirmation.
                    Text(
                        "Downloaded! Opening your dashboard…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                download.status == DownloadStatus.FAILED -> {
                    // Recommended models are ungated, so failures are usually
                    // network-related — but surface the real reason either way.
                    val isAuthError = download.error?.contains("401") == true ||
                        download.error?.contains("403") == true
                    Text(
                        download.error ?: "Download failed. Check your connection and retry.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onDownload(recommended) }) {
                            Text("Retry")
                        }
                        if (isAuthError) {
                            OutlinedButton(onClick = onOpenSettings) {
                                Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add token")
                            }
                        }
                        OutlinedButton(onClick = { onOpenModelPage(recommended.hfUrl) }) {
                            Icon(Icons.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Model page")
                        }
                    }
                }
                download.status == DownloadStatus.WAITING_FOR_NETWORK -> {
                    // Enqueued but blocked on the network constraint — be honest
                    // instead of showing fake progress.
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Waiting for connection… download starts automatically.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        TextButton(onClick = { onCancelDownload(recommended) }) {
                            Text("Cancel")
                        }
                    }
                }
                else -> {
                    // CHECKING_SIZE or DOWNLOADING
                    LinearProgressIndicator(
                        progress = { download.progress },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${(download.progress * 100).toInt()}%" +
                                (if (download.bytesPerSecond > 0)
                                    " • ${Formatters.formatBytes(download.bytesPerSecond)}/s" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        TextButton(onClick = { onCancelDownload(recommended) }) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Dashboard (returning user) state
// ---------------------------------------------------------------------------

@Composable
private fun DashboardHome(
    state: HomeUiState.Dashboard,
    onResumeChat: (String) -> Unit,
    onNewChat: (String?) -> Unit,
    onManageModels: () -> Unit,
    modifier: Modifier = Modifier,
    deviceViewModel: DeviceViewModel = hiltViewModel()
) {
    // Thermal advisory — dismissible for this session only.
    var advisoryDismissed by rememberSaveable { mutableStateOf(false) }

    // Live device state (thermal, battery, GPU, storage, RAM history) from the
    // same source as the Device Information screen. HomeViewModel's snapshot
    // only carries static fields, so feeding it to DeviceInsights leaves the
    // storage bar at 0%, battery "Unknown" and thermal stuck on "Normal".
    val liveDevice by deviceViewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // ---- Last-used model launchpad card ----
        LastUsedModelCard(
            state = state,
            onResumeChat = onResumeChat,
            onNewChat = onNewChat
        )

        // ---- Thermal/battery advisory (contextual, dismissible) ----
        if (!advisoryDismissed) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Large models may warm your device and use more battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { advisoryDismissed = true }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Your models ----
        SectionTitle("Your models")
        val displayModels = state.models.take(5)
        displayModels.forEach { model ->
            ModelRow(model = model, onNewChat = onNewChat)
        }
        if (state.models.size > 5) {
            TextButton(
                onClick = onManageModels,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Text("Show ${state.models.size - 5} more models")
            }
        }
        ManageModelsRow(onManageModels = onManageModels)

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Usage stats ----
        SectionTitle("Your usage")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatTile("${state.usage.totalChats}", "chats", Modifier.weight(1f))
            StatTile("${state.usage.totalTokens}", "tokens", Modifier.weight(1f))
            StatTile("${state.usage.modelsTried}", "models tried", Modifier.weight(1f))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatTile("${state.usage.totalMessages}", "messages", Modifier.weight(1f))
            StatTile("${state.usage.chatsThisWeek}", "chats this week", Modifier.weight(1f))
            StatTile("${state.usage.compactionsRun}", "compactions", Modifier.weight(1f))
        }
        state.tokensPerSecond?.let { tps ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Last session: ~${"%.1f".format(tps)} tok/s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        state.usage.favoriteModel?.let { favorite ->
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text("Most chatted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            favorite,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
        if (state.usage.webSearchChats > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        "Web search on in ${state.usage.webSearchChats} chat${if (state.usage.webSearchChats == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ---- Device Insights (revamped style) ----
        DeviceInsights(state = liveDevice)

        Spacer(modifier = Modifier.height(32.dp))
    }
}

/** A single downloaded model in the "Your models" list. */
@Composable
private fun ModelRow(
    model: com.ai.tinymodels.feature.home.model.DownloadedModelInfo,
    onNewChat: (String?) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onNewChat(model.modelId) },
        colors = CardDefaults.cardColors(
            containerColor = if (model.isLastUsed) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Memory,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (model.isLastUsed) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    model.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (model.isLastUsed) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (model.isLastUsed) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${Formatters.formatBytes(model.sizeBytes)} • ${model.fileCount} file" +
                        (if (model.fileCount != 1) "s" else "") +
                        (if (model.isLastUsed) " • last used" else " • ${Formatters.formatRelativeTime(model.downloadedAt)}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model.isLastUsed) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Chat with ${model.displayName}",
                tint = if (model.isLastUsed) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Full-width "Manage models" affordance under the models list. */
@Composable
private fun ManageModelsRow(onManageModels: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onManageModels)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Manage models",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun LastUsedModelCard(
    state: HomeUiState.Dashboard,
    onResumeChat: (String) -> Unit,
    onNewChat: (String?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Ready to chat",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Memory,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (state.lastUsedModel?.author == "imported") {
                        state.lastUsedFileName?.substringAfterLast('/')
                            ?.removeSuffix(".litertlm")
                            ?: state.lastUsedModel?.modelId?.substringAfterLast('/')
                            ?: "Your model"
                    } else {
                        state.lastUsedModel?.modelId?.substringAfterLast('/')
                            ?: "Your model"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            state.lastUsedFileName?.let { fileName ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = fileName.modelFileDisplayName()
                        .uppercase() + " • ${state.lastChat?.contextTokens ?: 2048} context",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (state.lastChat != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { onResumeChat(state.lastChat.id) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text("Resume chat")
                    }
                    OutlinedButton(
                        onClick = { onNewChat(state.lastUsedModel?.modelId) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("New chat")
                    }
                }
            } else {
                // No prior chat — show New chat prominently with the model name
                val modelDisplayName = state.lastUsedModel?.modelId?.substringAfterLast('/')
                    ?: state.lastUsedFileName?.modelFileDisplayName()
                    ?: "this model"
                Button(
                    onClick = { onNewChat(state.lastUsedModel?.modelId) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("Create a chat with $modelDisplayName")
                }
            }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------

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
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
