package com.ai.tinymodels.feature.models.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.R
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.domain.model.DownloadedModelFile
import com.ai.tinymodels.domain.usecase.model.ActiveDownload
import com.ai.tinymodels.domain.usecase.model.DownloadPaths
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import java.util.UUID

/**
 * What the discard-confirm dialog is about. [workId] is set for an in-flight
 * job (so it can be cancelled and awaited); [downloadedBytes] lets the dialog
 * say exactly how much partial data will be thrown away.
 */
private data class PendingDiscard(
    val workId: UUID?,
    val modelId: String,
    val fileName: String?,
    val displayName: String,
    val downloadedBytes: Long
)

/**
 * "Active downloads" and "Paused downloads" sections: live jobs with
 * Pause/Cancel and paused rows with Resume/Discard. Shared by the Models tab
 * and the Downloaded-models screen so both stay in sync from the same flows.
 *
 * All styling comes from [MaterialTheme] (no hardcoded colors) so device
 * light/dark/dynamic themes apply automatically.
 */
@Composable
fun ActiveDownloadsSection(
    active: List<ActiveDownload>,
    paused: List<DownloadedModelFile> = emptyList(),
    onPause: (ActiveDownload) -> Unit = {},
    onResume: (modelId: String, fileName: String) -> Unit = { _, _ -> },
    onDiscard: (workId: UUID?, modelId: String, fileName: String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    if (active.isEmpty() && paused.isEmpty()) return

    var pendingDiscard by remember { mutableStateOf<PendingDiscard?>(null) }
    val context = LocalContext.current
    val res = context.resources

    pendingDiscard?.let { target ->
        val partialLine = if (target.downloadedBytes > 0) {
            res.getString(
                R.string.model_details_discard_bytes_deleted,
                Formatters.formatBytes(target.downloadedBytes, res)
            )
        } else {
            res.getString(R.string.model_details_discard_partial_deleted)
        }
        AlertDialog(
            onDismissRequest = { pendingDiscard = null },
            title = { Text(stringResource(R.string.model_details_discard_title)) },
            text = {
                Text(
                    target.displayName + "\n\n" +
                        partialLine + " " +
                        stringResource(R.string.model_details_discard_body_end)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDiscard(target.workId, target.modelId, target.fileName)
                    pendingDiscard = null
                }) {
                    Text(
                        stringResource(R.string.model_details_discard),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscard = null }) {
                    Text(stringResource(R.string.model_details_keep))
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (active.isNotEmpty()) {
            Text(
                stringResource(R.string.downloads_active_title, active.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            active.forEach { download ->
                ActiveDownloadRow(
                    download = download,
                    onPause = { onPause(download) },
                    onDiscard = {
                        pendingDiscard = PendingDiscard(
                            workId = download.workId,
                            modelId = download.modelId,
                            fileName = download.fileName,
                            displayName = download.fileName?.substringAfterLast("/")
                                ?: download.modelId.substringAfterLast("/"),
                            downloadedBytes = download.downloadedBytes
                        )
                    }
                )
            }
        }

        if (paused.isNotEmpty()) {
            Text(
                stringResource(R.string.downloads_paused_title, paused.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            paused.forEach { file ->
                val partialBytes = remember(file.modelId, file.fileName) {
                    runCatching {
                        DownloadPaths.partFile(context.filesDir, file.modelId, file.fileName).length()
                    }.getOrDefault(0L)
                }
                PausedDownloadRow(
                    file = file,
                    partialBytes = partialBytes,
                    onResume = { onResume(file.modelId, file.fileName) },
                    onDiscard = {
                        val bytes = runCatching {
                            DownloadPaths.partFile(context.filesDir, file.modelId, file.fileName).length()
                        }.getOrDefault(0L)
                        pendingDiscard = PendingDiscard(
                            workId = null,
                            modelId = file.modelId,
                            fileName = file.fileName,
                            displayName = file.fileName.substringAfterLast("/"),
                            downloadedBytes = bytes
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ActiveDownloadRow(
    download: ActiveDownload,
    onPause: () -> Unit,
    onDiscard: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Downloading,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    download.fileName?.substringAfterLast("/") ?: download.modelId.substringAfterLast("/"),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (download.fileName != null) {
                    Text(
                        download.modelId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (download.status == DownloadStatus.DOWNLOADING && download.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { download.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    downloadStatusLine(download, LocalContext.current.resources),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPause) {
                Icon(
                    Icons.Filled.Pause,
                    contentDescription = stringResource(R.string.model_details_pause_desc),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDiscard) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.downloads_cancel_content_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PausedDownloadRow(
    file: DownloadedModelFile,
    partialBytes: Long,
    onResume: () -> Unit,
    onDiscard: () -> Unit
) {
    val res = LocalContext.current.resources
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Downloading,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    file.fileName.substringAfterLast("/"),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    file.modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (partialBytes > 0) {
                    Text(
                        stringResource(
                            R.string.model_details_downloaded_bytes,
                            Formatters.formatBytes(partialBytes, res)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    stringResource(R.string.model_details_paused_resumable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onResume) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.model_details_resume_desc),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDiscard) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.downloads_cancel_content_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun downloadStatusLine(
    download: ActiveDownload,
    res: android.content.res.Resources
): String {
    if (download.status == DownloadStatus.WAITING_FOR_NETWORK) {
        return res.getString(R.string.downloads_waiting_for_network)
    }
    val bytes = if (download.totalBytes > 0) {
        res.getString(
            R.string.downloads_progress_bytes,
            Formatters.formatBytes(download.downloadedBytes, res),
            Formatters.formatBytes(download.totalBytes, res)
        )
    } else {
        Formatters.formatBytes(download.downloadedBytes, res)
    }
    val pct = res.getString(
        R.string.downloads_progress_percent,
        (download.progress * 100).toInt().coerceIn(0, 100)
    )
    val speed = if (download.bytesPerSecond > 0) {
        res.getString(R.string.downloads_progress_speed, Formatters.formatBytes(download.bytesPerSecond, res))
    } else ""
    return "$bytes $pct$speed"
}
