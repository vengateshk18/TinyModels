package com.ai.tinymodels.feature.storage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.tinymodels.R
import com.ai.tinymodels.core.ui.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    onBack: () -> Unit,
    viewModel: StorageViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val safeDrawing = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val snackbarHostState = remember { SnackbarHostState() }

    val success = state as? StorageUiState.Success
    LaunchedEffect(success?.message) {
        success?.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        contentWindowInsets = safeDrawing,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = success?.actionInProgress == null) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.storage_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            )
        }
    ) { padding ->
        when (val s = state) {
            StorageUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is StorageUiState.Error -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = viewModel::refresh) { Text(stringResource(R.string.common_retry)) }
                }
            }
            is StorageUiState.Success -> StorageContent(
                breakdown = s.breakdown,
                actionInProgress = s.actionInProgress,
                onDeleteDownloaded = viewModel::deleteDownloadedModelFile,
                onDeletePartial = viewModel::deletePartialDownload,
                onDeleteOrphan = viewModel::deleteOrphanFile,
                onDeleteChat = viewModel::deleteChat,
                onResetApp = viewModel::resetAppData,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun StorageContent(
    breakdown: StorageBreakdown,
    actionInProgress: String?,
    onDeleteDownloaded: (String, String) -> Unit,
    onDeletePartial: (PartialDownloadStorageItem) -> Unit,
    onDeleteOrphan: (String) -> Unit,
    onDeleteChat: (String) -> Unit,
    onResetApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val res = LocalContext.current.resources
    var pendingDownloaded by remember { mutableStateOf<DownloadedModelStorageItem?>(null) }
    var pendingPartial by remember { mutableStateOf<PartialDownloadStorageItem?>(null) }
    var pendingOrphan by remember { mutableStateOf<OrphanFileStorageItem?>(null) }
    var pendingChat by remember { mutableStateOf<ChatStorageItem?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    val busy = actionInProgress != null

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { HeroStorageCard(breakdown = breakdown) }
        if (actionInProgress != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        actionInProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionCard(
                icon = Icons.Filled.DownloadDone,
                title = stringResource(R.string.storage_models_section),
                headline = Formatters.formatBytes(breakdown.downloadedModelsBytes, res),
                subtitle = stringResource(R.string.storage_models_count, breakdown.downloadedModelItems.size)
            ) {
                if (breakdown.downloadedModelItems.isEmpty()) {
                    MutedText(stringResource(R.string.storage_no_downloaded_models))
                } else {
                    breakdown.downloadedModelItems.forEach { item ->
                        StorageActionRow(
                            title = item.displayName,
                            subtitle = item.modelId,
                            value = Formatters.formatBytes(item.sizeBytes, res),
                            enabled = !busy,
                            onDelete = { pendingDownloaded = item }
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                icon = Icons.Filled.PauseCircleOutline,
                title = stringResource(R.string.storage_partial_downloads),
                headline = Formatters.formatBytes(breakdown.partialDownloadBytes, res),
                subtitle = stringResource(R.string.storage_partial_downloads_subtitle, breakdown.pausedDownloadCount)
            ) {
                if (breakdown.partialDownloadItems.isEmpty()) {
                    MutedText(stringResource(R.string.storage_no_partial_downloads))
                } else {
                    breakdown.partialDownloadItems.forEach { item ->
                        StorageActionRow(
                            title = item.displayName,
                            subtitle = item.detail,
                            value = Formatters.formatBytes(item.sizeBytes, res),
                            enabled = !busy,
                            onDelete = { pendingPartial = item }
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                icon = Icons.Filled.FolderOpen,
                title = stringResource(R.string.storage_orphan_files),
                headline = Formatters.formatBytes(breakdown.orphanFileBytes, res),
                subtitle = stringResource(R.string.storage_orphan_files_subtitle)
            ) {
                if (breakdown.orphanFileItems.isEmpty()) {
                    MutedText(stringResource(R.string.storage_no_orphan_files))
                } else {
                    breakdown.orphanFileItems.forEach { item ->
                        StorageActionRow(
                            title = item.displayName,
                            subtitle = item.detail,
                            value = Formatters.formatBytes(item.sizeBytes, res),
                            enabled = !busy,
                            onDelete = { pendingOrphan = item }
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                icon = Icons.Filled.ChatBubbleOutline,
                title = stringResource(R.string.storage_chats_section),
                headline = Formatters.formatBytes(breakdown.chatStorageBytes, res),
                subtitle = stringResource(R.string.storage_chats_summary, breakdown.chatCount, breakdown.messageCount)
            ) {
                DetailRow(stringResource(R.string.storage_database), Formatters.formatBytes(breakdown.databaseBytes, res))
                if (breakdown.databaseWalBytes > 0) {
                    DetailRow(stringResource(R.string.storage_database_wal), Formatters.formatBytes(breakdown.databaseWalBytes, res))
                }
                DetailRow(
                    stringResource(R.string.storage_chat_images),
                    "${Formatters.formatBytes(breakdown.chatImageBytes, res)} · ${stringResource(R.string.storage_images_count, breakdown.chatImageCount)}"
                )
                DetailRow(stringResource(R.string.storage_total_tokens), breakdown.totalTokensGenerated.toString())
                Spacer(Modifier.height(6.dp))
                if (breakdown.chatItems.isEmpty()) {
                    MutedText(stringResource(R.string.storage_no_chats))
                } else {
                    breakdown.chatItems.forEach { chat ->
                        StorageActionRow(
                            title = chat.title,
                            subtitle = stringResource(R.string.storage_chat_messages_count, chat.messageCount),
                            value = null,
                            enabled = !busy,
                            onDelete = { pendingChat = chat }
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.storage_reset_app),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Text(
                        stringResource(R.string.storage_reset_app_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    FilledTonalButton(
                        onClick = { showResetConfirm = true },
                        enabled = !busy,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.storage_reset_confirm_action))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    pendingDownloaded?.let { item ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_delete_confirm_title),
            body = stringResource(R.string.storage_delete_model_file_body, item.displayName),
            onConfirm = {
                pendingDownloaded = null
                onDeleteDownloaded(item.modelId, item.fileName)
            },
            onDismiss = { pendingDownloaded = null }
        )
    }
    pendingPartial?.let { item ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_delete_confirm_title),
            body = stringResource(R.string.storage_delete_partial_body, item.displayName),
            onConfirm = {
                pendingPartial = null
                onDeletePartial(item)
            },
            onDismiss = { pendingPartial = null }
        )
    }
    pendingOrphan?.let { item ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_delete_confirm_title),
            body = stringResource(R.string.storage_delete_orphan_body, item.displayName),
            onConfirm = {
                val path = item.path
                pendingOrphan = null
                onDeleteOrphan(path)
            },
            onDismiss = { pendingOrphan = null }
        )
    }
    pendingChat?.let { chat ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_delete_chat_title),
            body = stringResource(R.string.storage_delete_chat_body, chat.title),
            onConfirm = {
                val id = chat.chatId
                pendingChat = null
                onDeleteChat(id)
            },
            onDismiss = { pendingChat = null }
        )
    }
    if (showResetConfirm) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.storage_reset_confirm_title),
            body = stringResource(R.string.storage_reset_confirm_body),
            confirmLabel = stringResource(R.string.storage_reset_confirm_action),
            destructive = true,
            onConfirm = {
                showResetConfirm = false
                onResetApp()
            },
            onDismiss = { showResetConfirm = false }
        )
    }
}

@Composable
private fun HeroStorageCard(breakdown: StorageBreakdown) {
    val res = LocalContext.current.resources
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f)
        )
    )
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.background(gradient).fillMaxWidth().padding(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.storage_total_tracked),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            Formatters.formatBytes(breakdown.totalTrackedBytes, res),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Text(
                    stringResource(R.string.storage_total_tracked_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    StatPill(
                        label = stringResource(R.string.storage_models_section),
                        value = Formatters.formatBytes(breakdown.downloadedModelsBytes, res),
                        modifier = Modifier.weight(1f)
                    )
                    StatPill(
                        label = stringResource(R.string.storage_chats_section),
                        value = Formatters.formatBytes(breakdown.chatStorageBytes, res),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun SectionCard(
    icon: ImageVector,
    title: String,
    headline: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(headline, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable
private fun StorageActionRow(
    title: String,
    subtitle: String,
    value: String?,
    enabled: Boolean,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.storage_delete), tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MutedText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConfirmDeleteDialog(
    title: String,
    body: String,
    confirmLabel: String = stringResource(R.string.common_delete),
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
