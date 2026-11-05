package com.ai.tinymodels.domain.usecase.model

import java.util.Locale

/**
 * "How much longer?" for an in-flight file download.
 *
 * Kept pure so the row does no arithmetic: a wrong estimate is a bug the user
 * can see, and it is cheaper to pin it here than to eyeball it on a device.
 */
object DownloadEta {

    private const val SECOND_MS = 1_000L

    /** Milliseconds still to go, or null when no honest estimate exists. */
    fun remainingMillis(downloaded: Long, total: Long, bytesPerSecond: Long): Long? {
        if (total <= 0L || bytesPerSecond <= 0L) return null
        val remaining = total - downloaded
        if (remaining <= 0L) return null
        return remaining * 1000L / bytesPerSecond
    }

    /** `42s left`, `2m 14s left`, `1h 05m left`. */
    fun formatRemaining(millis: Long): String {
        // Round up: showing "0s left" while bytes are still arriving reads as stuck.
        val totalSeconds = ((millis + SECOND_MS - 1) / SECOND_MS).coerceAtLeast(1L)
        return when {
            totalSeconds < 60L -> "${totalSeconds}s left"
            totalSeconds < 3_600L -> {
                val minutes = totalSeconds / 60L
                val seconds = totalSeconds % 60L
                String.format(Locale.US, "%dm %02ds left", minutes, seconds)
            }
            else -> {
                val hours = totalSeconds / 3_600L
                val minutes = (totalSeconds % 3_600L) / 60L
                String.format(Locale.US, "%dh %02dm left", hours, minutes)
            }
        }
    }

    /** Convenience for the row: null when either half is unknown. */
    fun label(downloaded: Long, total: Long, bytesPerSecond: Long): String? =
        remainingMillis(downloaded, total, bytesPerSecond)?.let(::formatRemaining)
}
