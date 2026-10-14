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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.domain.usecase.model.ActiveDownload
import com.ai.tinymodels.domain.usecase.model.DownloadStatus
import java.util.UUID

/**
 * "Active downloads" section: every live WorkManager job with progress and
 * its own Cancel button. Shared by the Models tab and the Downloaded-models
 * screen so both update lively from the same flow.
 *
 * All styling comes from [MaterialTheme] (no hardcoded colors) so device
 * light/dark/dynamic themes apply automatically.
 */
@Composable
fun ActiveDownloadsSection(
    active: List<ActiveDownload>,
    onCancel: (UUID) -> Unit,
    modifier: Modifier = Modifier
) {
    if (active.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Active downloads (${active.size})",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        active.forEach { download ->
            ActiveDownloadRow(download = download, onCancel = { onCancel(download.workId) })
        }
    }
}

@Composable
private fun ActiveDownloadRow(
    download: ActiveDownload,
    onCancel: () -> Unit
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
                    downloadStatusLine(download),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cancel download",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun downloadStatusLine(download: ActiveDownload): String {
    if (download.status == DownloadStatus.WAITING_FOR_NETWORK) {
        return "Waiting for network…"
    }
    val bytes = if (download.totalBytes > 0) {
        "${Formatters.formatBytes(download.downloadedBytes)} / ${Formatters.formatBytes(download.totalBytes)}"
    } else {
        Formatters.formatBytes(download.downloadedBytes)
    }
    val pct = "(${(download.progress * 100).toInt().coerceIn(0, 100)}%)"
    val speed = if (download.bytesPerSecond > 0) {
        " • ${Formatters.formatBytes(download.bytesPerSecond)}/s"
    } else ""
    return "$bytes $pct$speed"
}
