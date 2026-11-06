package com.ai.tinymodels.feature.models.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.ai.tinymodels.R
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
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
import com.ai.tinymodels.domain.usecase.model.DownloadEta
import com.ai.tinymodels.domain.usecase.model.FileSizeStatus
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
    var fileToDiscard by remember { mutableStateOf<String?>(null) }

        val safeDrawing = WindowInsets.systemBars.union(WindowInsets.displayCutout)

    Scaffold(
        contentWindowInsets = safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_details_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                title = { Text(stringResource(R.string.model_details_file_too_large_title)) },
                text = {
                    Text(
                        if (pendingSize > 0) {
                            stringResource(
                                R.string.model_details_file_too_large_body,
                                Formatters.formatBytes(pendingSize, LocalContext.current.resources),
                                Formatters.formatBytes(uiState.deviceRamBytes, LocalContext.current.resources)
                            )
                        } else {
                            stringResource(
                                R.string.model_details_file_too_large_body_unknown_size,
                                Formatters.formatBytes(uiState.deviceRamBytes, LocalContext.current.resources)
                            )
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        oversizedFile = null
                        viewModel.onDownloadFileClicked(pending)
                    }) { Text(stringResource(R.string.model_details_download_anyway)) }
                },
                dismissButton = {
                    TextButton(onClick = { oversizedFile = null }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }

        // Discarding is irreversible — the partial bytes are gone and the
        // download has to start over. Ask before doing it.
        fileToDiscard?.let { pending ->
            val state = uiState.fileState(pending)
            val partial = state.downloadedBytes
            AlertDialog(
                onDismissRequest = { fileToDiscard = null },
                icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
                title = { Text(stringResource(R.string.model_details_discard_title)) },
                text = {
                    Text(
                        buildString {
                            append(pending.substringAfterLast("/"))
                            append("\n\n")
                            if (partial > 0) {
                                append(
                                    stringResource(
                                        R.string.model_details_discard_bytes_deleted,
                                        Formatters.formatBytes(partial, LocalContext.current.resources)
                                    )
                                )
                                append(" ")
                            } else {
                                append(
                                    stringResource(R.string.model_details_discard_partial_deleted)
                                )
                                append(" ")
                            }
                            append(
                                stringResource(R.string.model_details_discard_body_end)
                            )
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        fileToDiscard = null
                        viewModel.onDiscardFile(pending)
                    }) {
                        Text(
                            stringResource(R.string.model_details_discard),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fileToDiscard = null }) { Text(stringResource(R.string.model_details_keep)) }
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
                    message = uiState.errorMessageRes?.let { stringResource(it) }
                        ?: stringResource(R.string.model_details_error_failed_to_load),
                    onRetry = viewModel::load,
                    // Gated model — offer a direct path to add a token.
                    secondaryActionLabel = if (uiState.error is AppError.Auth)
                        stringResource(R.string.model_details_add_hf_token) else null,
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
                        onDiscard = { name -> fileToDiscard = name },
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
            StatItem(Icons.Filled.TrendingUp, stringResource(R.string.model_details_stat_downloads), Formatters.formatCount(model.downloads ?: 0))
            StatItem(Icons.Filled.Favorite, stringResource(R.string.model_details_stat_likes), Formatters.formatCount((model.likes ?: 0).toLong()))
            StatItem(Icons.Filled.SdStorage, stringResource(R.string.model_details_stat_repo_size), Formatters.formatBytes(model.usedStorage ?: 0, LocalContext.current.resources))
            StatItem(Icons.Filled.CheckCircle, stringResource(R.string.model_details_stat_downloaded), "$downloadedCount/${model.runtimeFiles.ifEmpty { model.liteRtFiles }.size}")
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
            Text(stringResource(R.string.model_details_try_it), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.model_details_sample_prompts),
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
            Text(stringResource(R.string.model_details_about), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(10.dp))
            InfoRow(
                stringResource(R.string.model_details_updated),
                Formatters.formatDate(model.lastModified, LocalContext.current.resources)
            )
            InfoRow(
                stringResource(R.string.model_details_created),
                Formatters.formatDate(model.createdAt, LocalContext.current.resources)
            )
            model.baseModel?.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.model_details_base_model), it) }
            model.sha?.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.model_details_revision), it.take(10)) }
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
            Text(stringResource(R.string.model_details_tags), style = MaterialTheme.typography.titleMedium)
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.model_details_files), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.model_details_files_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                files.forEach { fileName ->
                    FileRow(
                        fileName = fileName,
                        state = uiState.fileState(fileName),
                        fileSize = uiState.getFileSize(fileName),
                        sizeStatus = uiState.fileSizeStatus(fileName),
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
}

/**
 * One file, one self-contained card: name, size, a single primary action on the
 * right, and — only while it is relevant — the transfer itself underneath.
 * No leading selection affordance: nothing here is a radio choice.
 */
@Composable
private fun FileRow(
    fileName: String,
    state: FileRowState,
    fileSize: Long,
    sizeStatus: FileSizeStatus,
    isLoadable: Boolean,
    error: String?,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onDownloadedClick: () -> Unit
) {
    val phase = state.phase
    val active = phase == FileRowPhase.DOWNLOADING || phase == FileRowPhase.QUEUED
    val borderColor by animateColorAsState(
        targetValue = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        } else {
            Color.Transparent
        },
        label = "fileRowBorder"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = phase == FileRowPhase.DOWNLOADED, onClick = onDownloadedClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        fileName.substringAfterLast("/"),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    FileMetaLine(
                        state = state,
                        fileSize = fileSize,
                        sizeStatus = sizeStatus,
                        isLoadable = isLoadable
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                FileActions(
                    state = state,
                    onDownload = onDownload,
                    onPause = onPause,
                    onResume = onResume,
                    onDiscard = onDiscard
                )
            }

            AnimatedVisibility(visible = phase == FileRowPhase.DOWNLOADING || phase == FileRowPhase.QUEUED) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    ActiveProgress(state)
                }
            }

            AnimatedVisibility(visible = phase == FileRowPhase.PAUSED) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    PausedProgress(state)
                }
            }

            if (phase == FileRowPhase.FAILED && !error.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * When the size cannot be read we say so — a spinner forever would be a lie,
 * and a bare "unknown" tells the user neither what is happening nor that we
 * stopped asking.
 */
@Composable
private fun FileMetaLine(
    state: FileRowState,
    fileSize: Long,
    sizeStatus: FileSizeStatus,
    isLoadable: Boolean
) {
    val sizeKnown = fileSize > 0
    val sizeText = when {
        sizeKnown -> Formatters.formatBytes(fileSize, LocalContext.current.resources)
        state.phase == FileRowPhase.DOWNLOADED -> null
        sizeStatus == FileSizeStatus.LOADING -> stringResource(R.string.model_details_size_loading)
        else -> stringResource(R.string.model_details_size_unknown)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        sizeText?.let { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val (label, tint) = when {
            state.phase == FileRowPhase.DOWNLOADED ->
                stringResource(R.string.model_details_status_downloaded) to MaterialTheme.colorScheme.primary
            sizeKnown && !isLoadable ->
                stringResource(R.string.model_details_too_large_short) to MaterialTheme.colorScheme.error
            sizeKnown ->
                stringResource(R.string.model_details_fits_device) to MaterialTheme.colorScheme.onSurfaceVariant
            else -> return@Row
        }
        Text(
            if (sizeText != null) "  ·  $label" else label,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A paused transfer tells the user exactly how much is already on the device.
 * Resume continues from this byte offset, so the number is not trivia.
 */
@Composable
private fun PausedProgress(state: FileRowState) {
    if (state.totalBytes > 0) {
        val progress by animateFloatAsState(
            targetValue = state.progress.coerceIn(0f, 1f),
            label = "pausedProgress"
        )
        ProgressTrack(progress, Brush.horizontalGradient(listOf(
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.tertiaryContainer
        )))
    } else {
        IndeterminateTrack()
    }

    Spacer(modifier = Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val detail = if (state.totalBytes > 0) {
            buildString {
                append(Formatters.formatBytes(state.downloadedBytes, LocalContext.current.resources))
                append(" / ")
                append(Formatters.formatBytes(state.totalBytes, LocalContext.current.resources))
                append(" · ")
                append((state.progress * 100).toInt())
                append("%")
            }
        } else {
            stringResource(
                R.string.model_details_downloaded_bytes,
                Formatters.formatBytes(state.downloadedBytes, LocalContext.current.resources)
            )
        }
        Text(
            detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.model_details_paused_resumable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1
        )
    }
}

/** Progress, counters, speed and time left — the full story of the transfer. */
@Composable
private fun ActiveProgress(state: FileRowState) {
    if (state.phase == FileRowPhase.QUEUED) {
        IndeterminateTrack()
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            stringResource(R.string.downloads_waiting_for_network),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val progress by animateFloatAsState(
        targetValue = state.progress.coerceIn(0f, 1f),
        label = "fileProgress"
    )
    if (state.totalBytes > 0) {
        ProgressTrack(progress)
    } else {
        IndeterminateTrack()
    }

    Spacer(modifier = Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildString {
                append(Formatters.formatBytes(state.downloadedBytes, LocalContext.current.resources))
                if (state.totalBytes > 0) {
                    append(" / ")
                    append(Formatters.formatBytes(state.totalBytes, LocalContext.current.resources))
                    append(" · ")
                    append((progress * 100).toInt())
                    append("%")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Spacer(modifier = Modifier.weight(1f))
        val trailing = buildList {
            if (state.bytesPerSecond > 0) {
                add(
                    stringResource(
                        R.string.downloads_speed_only,
                        Formatters.formatBytes(state.bytesPerSecond, LocalContext.current.resources)
                    )
                )
            }
            DownloadEta
                .label(state.downloadedBytes, state.totalBytes, state.bytesPerSecond)
                ?.let(::add)
        }.joinToString(" · ")
        if (trailing.isNotEmpty()) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** Rounded track with a gradient fill — reads as progress, not as a slider. */
@Composable
private fun ProgressTrack(
    progress: Float,
    fill: Brush = Brush.horizontalGradient(
        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    )
) {
    val shape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .clip(shape)
                .background(fill)
        )
    }
}

@Composable
private fun IndeterminateTrack() {
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
    )
}

@Composable
private fun FileActions(
    state: FileRowState,
    onDownload: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit
) {
    when (state.phase) {
        FileRowPhase.IDLE -> CircleAction(
            icon = Icons.Filled.Download,
            contentDescription = stringResource(R.string.model_details_download_desc),
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            onClick = onDownload
        )
        FileRowPhase.DOWNLOADING -> ProgressRingAction(progress = state.progress, onClick = onPause)
        FileRowPhase.QUEUED -> CircleAction(
            icon = Icons.Filled.Close,
            contentDescription = stringResource(R.string.model_details_cancel_queued_desc),
            container = MaterialTheme.colorScheme.secondaryContainer,
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            onClick = onPause,
            size = 40.dp
        )
        FileRowPhase.PAUSED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleAction(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.model_details_discard_desc),
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onDiscard,
                size = 40.dp
            )
            CircleAction(
                icon = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.model_details_resume_desc),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = onResume
            )
        }
        FileRowPhase.FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircleAction(
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.model_details_discard_desc),
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onDiscard,
                size = 40.dp
            )
            CircleAction(
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.model_details_retry_desc),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = onDownload
            )
        }
        FileRowPhase.DOWNLOADED -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = stringResource(R.string.model_details_status_downloaded),
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CircleAction(
    icon: ImageVector,
    contentDescription: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    size: Dp = 44.dp
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = container,
        contentColor = content
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
        }
    }
}

/** Pause inside a ring that doubles as the percentage indicator. */
@Composable
private fun ProgressRingAction(progress: Float, onClick: () -> Unit) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        label = "fileProgressRing"
    )
    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { animated },
            modifier = Modifier.size(48.dp),
            strokeWidth = 3.dp,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round
        )
        Surface(
            onClick = onClick,
            modifier = Modifier.size(36.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.Pause,
                    contentDescription = stringResource(R.string.model_details_pause_desc),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
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
                    Text(stringResource(R.string.model_details_status_downloaded), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
            } else {
                val sizeLabel = if (totalSizeBytes > 0) Formatters.formatBytes(totalSizeBytes, LocalContext.current.resources) else null
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (sizeLabel != null) {
                        stringResource(R.string.downloads_button_with_size, sizeLabel)
                    } else {
                        stringResource(R.string.downloads_button)
                    })
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


