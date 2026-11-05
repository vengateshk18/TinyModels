package com.ai.tinymodels.feature.models.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.core.ui.components.ErrorState
import com.ai.tinymodels.core.ui.components.OfflineState
import com.ai.tinymodels.domain.model.FileDownloadStatus
import com.ai.tinymodels.domain.usecase.model.FileRowPhase
import com.ai.tinymodels.domain.usecase.model.FileRowState
import com.ai.tinymodels.domain.model.ModelDetails
import com.ai.tinymodels.domain.usecase.model.DownloadState
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import com.ai.tinymodels.feature.models.ModelDetailsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDetailsScreen(
    onBack: () -> Unit,
    onDownloadedFileClick: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: ModelDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var oversizedFile by remember { mutableStateOf<String?>(null) }

        val safeDrawing = WindowInsets.systemBars.union(WindowInsets.displayCutout)

    Scaffold(
        contentWindowInsets = safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Model details", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        },
        bottomBar = {
            // Whole-model downloads keep their own bar. Per-file downloads are
            // driven entirely from the file rows themselves.
            val model = uiState.model
            if (model != null && uiState.download.isDownloading) {
                DownloadBar(
                    model = model,
                    isDownloaded = uiState.isDownloaded,
                    download = uiState.download,
                    totalSizeBytes = uiState.totalSizeBytes,
                    onAction = viewModel::onDownloadClick
                )
            }
        }
    ) { padding ->
        // Oversized-file confirmation: the user explicitly wants a file this
        // device cannot load. Let them, but make the cost clear up front.
        oversizedFile?.let { pending ->
            val pendingSize = uiState.getFileSize(pending)
            AlertDialog(
                onDismissRequest = { oversizedFile = null },
                icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                title = { Text("File too large for this device") },
                text = {
                    Text(
                        (if (pendingSize > 0) "This file (${Formatters.formatBytes(pendingSize)}) " else "This file ")
                            + "needs more RAM than your device has "
                            + "(${Formatters.formatBytes(uiState.deviceRamBytes)}). "
                            + "You can download it, but it cannot be used for chat or "
                            + "benchmark on this device."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        oversizedFile = null
                        viewModel.onDownloadFileClicked(pending)
                    }) { Text("Download anyway") }
                },
                dismissButton = {
                    TextButton(onClick = { oversizedFile = null }) { Text("Cancel") }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                uiState.isOfflineError ->
                    OfflineState(onRetry = viewModel::load)
                uiState.error != null -> ErrorState(
                    message = uiState.errorMessage ?: "Failed to load details",
                    onRetry = viewModel::load,
                    // Gated model — offer a direct path to add a token.
                    secondaryActionLabel = if (uiState.error is AppError.Auth)
                        "Add Hugging Face token" else null,
                    onSecondaryAction = if (uiState.error is AppError.Auth)
                        onOpenSettings else null
                )
                uiState.model != null ->
                    ModelDetailsContent(
                        model = uiState.model!!,
                        uiState = uiState,
                        onDownload = { name ->
                            if (uiState.isFileLoadable(name)) {
                                viewModel.onDownloadFileClicked(name)
                            } else {
                                oversizedFile = name
                            }
                        },
                        onPause = viewModel::onPauseFile,
                        onResume = viewModel::onResumeFile,
                        onDiscard = viewModel::onDiscardFile,
                        onDownloadedFileClick = onDownloadedFileClick
                    )
            }
        }
    }
}

@Composable
private fun ModelDetailsContent(
    model: ModelDetails,
    uiState: ModelDetailsViewModel.UiState,
    onDownload: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onDownloadedFileClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { HeaderCard(model) }
        item { StatsCard(model, uiState.modelFiles) }
        if (model.widgetPrompts.isNotEmpty()) item { TryItCard(model.widgetPrompts) }
        item { AboutCard(model) }
        if (model.tags.isNotEmpty()) item { TagsCard(model.tags) }
        if (model.runtimeFiles.ifEmpty { model.liteRtFiles }.isNotEmpty()) {
            item {
                InteractiveFilesCard(
                    files = model.runtimeFiles.ifEmpty { model.liteRtFiles },
                    uiState = uiState,
                    onDownload = onDownload,
                    onPause = onPause,
                    onResume = onResume,
                    onDiscard = onDiscard,
                    onDownloadedFileClick = onDownloadedFileClick
                )
            }
        }
        // Bottom spacer so content clears the sticky bar.
        item { Spacer(modifier = Modifier.height(4.dp)) }
    }
}

// ---------- Info cards ----------

@Composable
private fun HeaderCard(model: ModelDetails) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(model.id, style = MaterialTheme.typography.titleLarge, overflow = TextOverflow.Ellipsis)
            if (!model.author.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(model.author, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                model.pipelineTag?.takeIf { it.isNotBlank() }?.let { Chip(it) }
                model.libraryName?.takeIf { it.isNotBlank() }?.let { Chip(it) }
                if (model.isGated) {
                    Chip("gated", leading = {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(12.dp))
                    })
                }
            }
        }
    }
}

@Composable
private fun StatsCard(model: ModelDetails, modelFiles: List<com.ai.tinymodels.domain.model.DownloadedModelFile>) {
    val downloadedCount = modelFiles.count { it.status == FileDownloadStatus.DOWNLOADED }
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            StatItem(Icons.Filled.TrendingUp, "Downloads", Formatters.formatCount(model.downloads ?: 0))
            StatItem(Icons.Filled.Favorite, "Likes", Formatters.formatCount((model.likes ?: 0).toLong()))
            StatItem(Icons.Filled.SdStorage, "Repo size", Formatters.formatBytes(model.usedStorage ?: 0))
            StatItem(Icons.Filled.CheckCircle, "Downloaded", "$downloadedCount/${model.runtimeFiles.ifEmpty { model.liteRtFiles }.size}")
        }
    }
}

@Composable
private fun StatItem(icon: ImageVector, label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TryItCard(prompts: List<String>) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Try it", style = MaterialTheme.typography.titleMedium)
            Text("Sample prompts for this model",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))
            prompts.forEachIndexed { index, prompt ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Text("\u2022 $prompt", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AboutCard(model: ModelDetails) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(10.dp))
            InfoRow("Updated", Formatters.formatDate(model.lastModified))
            InfoRow("Created", Formatters.formatDate(model.createdAt))
            model.baseModel?.takeIf { it.isNotBlank() }?.let { InfoRow("Base model", it) }
            model.sha?.takeIf { it.isNotBlank() }?.let { InfoRow("Revision", it.take(10)) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsCard(tags: List<String>) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Tags", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { Chip(it) }
            }
        }
    }
}

@Composable
private fun InteractiveFilesCard(
    files: List<String>,
    uiState: ModelDetailsViewModel.UiState,
    onDownload: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onDiscard: (String) -> Unit,
    onDownloadedFileClick: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Files", style = MaterialTheme.typography.titleMedium)
            Text("Download files individually — several can run at once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))
            files.forEachIndexed { index, fileName ->
                if (index > 0) HorizontalDivider()
                FileRow(
                    fileName = fileName,
                    state = uiState.fileState(fileName),
                    fileSize = uiState.getFileSize(fileName),
                    isLoadable = uiState.isFileLoadable(fileName),
                    error = uiState.fileError(fileName),
                    onDownload = { onDownload(fileName) },
                    onPause = { onPause(fileName) },
                    onResume = { onResume(fileName) },
                    onDiscard = { onDiscard(fileName) },
                    onDownloadedClick = { onDownloadedFileClick(fileName) }
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    fileName: String,
    state: FileRowState,
    fileSize: Long,
    isLoadable: Boolean,
    error: String?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onDownloadedClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        onClick = { if (state.phase == FileRowPhase.DOWNLOADED) onDownloadedClick() }
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilePhaseIcon(state)
                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        fileName.substringAfterLast("/"),
                        style = MaterialTheme.typography.bodyMedium,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1
                    )
                    Text(
                        fileSubtitle(state, fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.phase != FileRowPhase.DOWNLOADED && fileSize > 0) {
                        Text(
                            if (isLoadable) "✓ Fits your device"
                            else "⚠ Too large for this device",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isLoadable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                    if (state.phase == FileRowPhase.FAILED && !error.isNullOrBlank()) {
                        Text(
                            error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (state.phase == FileRowPhase.DOWNLOADING) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (state.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { state.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp)
                            )
                        } else {
                            // Unknown size — a determinate bar would sit at 0%.
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(4.dp)
                            )
                        }
                    }
                }

                // One control per row: the same surface that shows progress
                // also pauses, resumes and discards it.
                when (state.phase) {
                    FileRowPhase.IDLE -> {
                        IconButton(onClick = onDownload) {
                            Icon(
                                Icons.Filled.Download,
                                contentDescription = "Download ${fileName.substringAfterLast("/")}",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    FileRowPhase.QUEUED -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel queued download")
                        }
                    }
                    FileRowPhase.DOWNLOADING -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = "Pause download")
                        }
                    }
                    FileRowPhase.PAUSED -> {
                        IconButton(onClick = onResume) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Resume download",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onDiscard) {
                            Icon(Icons.Filled.Close, contentDescription = "Discard download")
                        }
                    }
                    FileRowPhase.FAILED -> {
                        IconButton(onClick = onDownload) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Retry download",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = onDiscard) {
                            Icon(Icons.Filled.Close, contentDescription = "Discard download")
                        }
                    }
                    FileRowPhase.DOWNLOADED -> {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/** Size, live byte counters, speed and percentage — whatever is known. */
private fun fileSubtitle(state: FileRowState, fileSize: Long): String = when (state.phase) {
    FileRowPhase.DOWNLOADING -> buildString {
        append(Formatters.formatBytes(state.downloadedBytes))
        if (state.totalBytes > 0) append(" / ${Formatters.formatBytes(state.totalBytes)}")
        append(" · ${(state.progress * 100).toInt()}%")
        if (state.bytesPerSecond > 0) append(" · ${Formatters.formatBytes(state.bytesPerSecond)}/s")
    }
    FileRowPhase.QUEUED -> "Waiting for network"
    FileRowPhase.PAUSED -> "Paused"
    FileRowPhase.DOWNLOADED -> if (fileSize > 0) Formatters.formatBytes(fileSize) + " · Downloaded" else "Downloaded"
    FileRowPhase.FAILED -> "Download failed"
    FileRowPhase.IDLE -> if (fileSize > 0) Formatters.formatBytes(fileSize) else "—"
}

@Composable
private fun FilePhaseIcon(state: FileRowState) {
    val iconSize = 20.dp
    when (state.phase) {
        FileRowPhase.IDLE -> Icon(
            Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FileRowPhase.QUEUED -> Icon(
            Icons.Filled.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FileRowPhase.DOWNLOADING -> if (state.totalBytes > 0) {
            CircularProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.size(iconSize),
                strokeWidth = 2.dp
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(iconSize), strokeWidth = 2.dp)
        }
        FileRowPhase.PAUSED -> Icon(
            Icons.Filled.Pause,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FileRowPhase.DOWNLOADED -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.primary
        )
        FileRowPhase.FAILED -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun Chip(label: String, leading: (@Composable () -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            leading?.let { it(); Spacer(modifier = Modifier.width(4.dp)) }
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun DownloadBar(
    model: ModelDetails,
    isDownloaded: Boolean,
    download: DownloadState,
    totalSizeBytes: Long,
    onAction: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        modifier = Modifier.imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // windowInsetsPadding covers both horizontal cutout and bottom nav bar
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
                )
                .padding(16.dp)
        ) {
            if (isDownloaded) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Downloaded", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
            } else {
                val sizeLabel = if (totalSizeBytes > 0) Formatters.formatBytes(totalSizeBytes) else null
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (sizeLabel != null) "Download \u00b7 $sizeLabel" else "Download")
                }

                // Error (e.g. failed / not enough storage) with a subtle retry hint.
                download.error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}


