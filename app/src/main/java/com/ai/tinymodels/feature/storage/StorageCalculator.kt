package com.ai.tinymodels.feature.storage

import java.io.File

object StorageCalculator {

    fun sumBytes(files: List<File>): Long = files.sumOf { if (it.isFile) it.length() else 0L }

    fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    /** All `*.part` files under [modelsRoot] (recursively). These represent paused/incomplete downloads. */
    fun findPartFiles(modelsRoot: File): List<File> {
        if (!modelsRoot.exists() || !modelsRoot.isDirectory) return emptyList()
        return modelsRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".part") }
            .toList()
    }

    /**
     * Regular files under [modelsRoot] that are not referenced by [trackedAbsolutePaths]
     * and are not partial downloads (`*.part`). Used to surface orphaned leftovers.
     */
    fun findOrphanModelFiles(modelsRoot: File, trackedAbsolutePaths: Set<String>): List<File> {
        if (!modelsRoot.exists() || !modelsRoot.isDirectory) return emptyList()
        return modelsRoot.walkTopDown()
            .filter { file ->
                file.isFile &&
                    !file.name.endsWith(".part") &&
                    file.absolutePath !in trackedAbsolutePaths
            }
            .toList()
    }
}
