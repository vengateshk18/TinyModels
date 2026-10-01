package com.example.tinymodels.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Represents a file or directory under the app filesDir/models folder.
 */
data class LocalFileEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val isDirectory: Boolean
)

suspend fun listLocalModelFiles(context: Context, modelsDirName: String = "models"): List<LocalFileEntry> =
    withContext(Dispatchers.IO) {
        val root = File(context.filesDir, modelsDirName)
        if (!root.exists()) return@withContext emptyList()

        root.walkTopDown()
            .filter { it != root }
            .map { file ->
                val size = if (file.isFile) {
                    file.length()
                } else {
                    // sum sizes of files inside directory
                    file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                }
                LocalFileEntry(
                    relativePath = file.relativeTo(context.filesDir).path,
                    sizeBytes = size,
                    isDirectory = file.isDirectory
                )
            }
            .toList()
    }

fun formatBytes(bytes: Long): String =
    if (bytes <= 0) "0 B" else {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var i = 0
        while (size >= 1024 && i < units.lastIndex) {
            size /= 1024
            i++
        }
        "%.2f %s".format(size, units[i])
    }
