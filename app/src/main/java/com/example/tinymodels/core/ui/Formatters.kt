package com.example.tinymodels.core.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shared display formatters. Single source of truth (replaces per-screen duplicates). */
object Formatters {

    /** Human-readable byte size. `0`/negative -> "Unknown". e.g. 1.43 GB, 512.0 MB. */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "Unknown"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0) "$bytes B" else "%.2f %s".format(Locale.US, value, units[index])
    }

    /** Compact count for social stats. e.g. 247, 1.2K, 3.4M. */
    fun formatCount(count: Long): String = when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> compact(count, 1_000.0, "K")
        else -> compact(count, 1_000_000.0, "M")
    }

    private fun compact(count: Long, divisor: Double, suffix: String): String {
        val value = count / divisor
        return if (value % 1.0 == 0.0) "${value.toInt()}$suffix"
        else "%.1f%s".format(Locale.US, value, suffix)
    }

    /** ISO-8601 (e.g. 2026-08-31T13:49:04.000Z) -> "Aug 31, 2026". Falls back to raw on parse failure. */
    fun formatDate(iso: String?): String {
        if (iso.isNullOrBlank()) return "Unknown"
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date: Date = parser.parse(iso) ?: return iso
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            iso
        }
    }

    /** Epoch millis -> "Aug 31, 2026". */
    fun formatEpoch(millis: Long): String =
        if (millis <= 0) "Unknown"
        else SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
}
