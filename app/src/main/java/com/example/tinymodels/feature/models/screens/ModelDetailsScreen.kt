package com.example.tinymodels.feature.models.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.TrendingUp
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tinymodels.core.ui.Formatters
import com.example.tinymodels.domain.model.ModelDetails
import com.example.tinymodels.domain.usecase.model.DownloadState
import com.example.tinymodels.domain.usecase.model.DownloadStatus
import com.example.tinymodels.feature.models.ModelDetailsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDetailsScreen(
    onBack: () -> Unit,
    viewModel: ModelDetailsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
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
            // Sticky download action bar, visible only when we have a model to act on.
            uiState.model?.let { model ->
                if (model.runtimeFiles.ifEmpty { model.liteRtFiles }.isNotEmpty()) {
                    DownloadBar(
                        model = model,
                        isDownloaded = uiState.isDownloaded,
                        download = uiState.download,
                        totalSizeBytes = uiState.totalSizeBytes,
                        onAction = viewModel::onDownloadClick
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                uiState.error != null ->
                    ErrorState(message = uiState.error!!, onRetry = viewModel::load)
                uiState.model != null ->
                    ModelDetailsContent(model = uiState.model!!)
            }
        }
    }
}

@Composable
private fun ModelDetailsContent(model: ModelDetails) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { HeaderCard(model) }
        item { StatsCard(model) }
        if (model.widgetPrompts.isNotEmpty()) item { TryItCard(model.widgetPrompts) }
        item { AboutCard(model) }
        if (model.tags.isNotEmpty()) item { TagsCard(model.tags) }
        if (model.runtimeFiles.ifEmpty { model.liteRtFiles }.isNotEmpty()) item { FilesCard(model.runtimeFiles.ifEmpty { model.liteRtFiles }) }
        // Bottom spacer so content clears the sticky bar.
        item { Spacer(modifier = Modifier.height(4.dp)) }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null,
            tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(44.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = onRetry) { Text("Retry") }
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
private fun StatsCard(model: ModelDetails) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            StatItem(Icons.Filled.TrendingUp, "Downloads", Formatters.formatCount(model.downloads ?: 0))
            StatItem(Icons.Filled.Favorite, "Likes", Formatters.formatCount((model.likes ?: 0).toLong()))
            StatItem(Icons.Filled.SdStorage, "Size", Formatters.formatBytes(model.usedStorage ?: 0))
            StatItem(Icons.Filled.Download, "Files", "${model.runtimeFiles.ifEmpty { model.liteRtFiles }.size}")
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
private fun FilesCard(files: List<String>) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Files", style = MaterialTheme.typography.titleMedium)
            Text("Downloaded together when you tap Download.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))
            files.forEachIndexed { index, fileName ->
                if (index > 0) HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null,
                        modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(fileName.substringAfterLast("/"), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis, maxLines = 1)
                }
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

// ---------- Sticky download action bar ----------

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
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            when (download.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.CHECKING_SIZE -> {
                    DownloadInProgress(download, totalSizeBytes, onAction)
                }
                else -> {
                    DownloadIdle(model, isDownloaded, download, totalSizeBytes, onAction)
                }
            }
        }
    }
}

@Composable
private fun DownloadIdle(
    model: ModelDetails,
    isDownloaded: Boolean,
    download: DownloadState,
    totalSizeBytes: Long,
    onAction: () -> Unit
) {
    if (isDownloaded) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Downloaded", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        }
        return
    }

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

@Composable
private fun DownloadInProgress(
    download: DownloadState,
    totalSizeBytes: Long,
    onAction: () -> Unit
) {
    val total = if (download.totalBytes > 0) download.totalBytes else totalSizeBytes
    val isPreparing = download.status == DownloadStatus.CHECKING_SIZE

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (isPreparing) "Preparing download\u2026" else "Downloading\u2026",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (isPreparing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
            } else {
                LinearProgressIndicator(
                    progress = { download.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                val pct = (download.progress * 100).toInt()
                Text(
                    if (total > 0)
                        "${Formatters.formatBytes(download.downloadedBytes)} / ${Formatters.formatBytes(total)} \u00b7 $pct%"
                    else "$pct%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (download.bytesPerSecond > 0) {
                    Text(
                        "${Formatters.formatBytes(download.bytesPerSecond)}/s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        OutlinedButton(onClick = onAction) {
            Icon(Icons.Filled.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Cancel")
        }
    }
}
