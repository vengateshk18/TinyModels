package com.ai.tinymodels.feature.models.components

import android.content.res.Resources
import com.ai.tinymodels.R
import com.ai.tinymodels.core.ui.Formatters
import com.ai.tinymodels.domain.usecase.model.ActiveDownload
import com.ai.tinymodels.domain.usecase.model.DownloadStatus

/**
 * The one-line status shown under an active download row: `10.00 MB /
 * 100.00 MB(10%) • 1.00 MB/s`. Kept outside the composable so the exact
 * format is pinned by unit tests ([ActiveDownloadsStatusTest]).
 */
internal object ActiveDownloadsStatus {

    fun line(download: ActiveDownload, res: Resources): String {
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
            res.getString(
                R.string.downloads_progress_speed,
                Formatters.formatBytes(download.bytesPerSecond, res)
            )
        } else ""
        return "$bytes$pct$speed"
    }
}
