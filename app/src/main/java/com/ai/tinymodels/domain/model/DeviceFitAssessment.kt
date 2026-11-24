package com.ai.tinymodels.domain.model

import kotlin.math.roundToInt

enum class DeviceFitLevel { GOOD, BORDERLINE, TOO_LARGE, UNKNOWN }

data class FileDeviceFit(
    val fileName: String,
    val level: DeviceFitLevel,
    val estimatedBytes: Long? = null,
    val reason: String? = null
)

data class ModelDeviceFit(
    val recommendedFile: String?,
    val fitsOnDevice: Boolean,
    val summary: DeviceFitLevel,
    val files: List<FileDeviceFit>
)

object DeviceFitAdvisor {

    /** Rough bytes-per-parameter for common quantization labels found in file names. */
    private fun bytesPerParam(fileName: String): Double {
        val name = fileName.lowercase()
        return when {
            name.contains("iq1") || name.contains("tq1") -> 0.22
            name.contains("q2") || name.contains("iq2") || name.contains("tq2") -> 0.35
            name.contains("q3") || name.contains("iq3") -> 0.48
            name.contains("q4") || name.contains("iq4") || name.contains("int4") || name.contains("4bit") -> 0.62
            name.contains("q5") -> 0.74
            name.contains("q6") -> 0.86
            name.contains("q8") || name.contains("int8") || name.contains("8bit") -> 1.05
            name.contains("f16") || name.contains("bf16") -> 2.1
            name.contains("f32") -> 4.1
            name.endsWith(".task") || name.endsWith(".tflite") || name.endsWith(".litertlm") -> 1.0
            else -> 0.75
        }
    }

    private fun estimateBytesForFile(
        model: ModelDetails,
        fileName: String,
        knownFileSize: Long
    ): Long? {
        if (knownFileSize > 0L) return knownFileSize
        val params = model.parameterCount ?: return model.usedStorage?.takeIf { it > 0L && model.allRunnableFiles.size <= 1 }
        if (fileName.isVisionProjectorFile()) return null
        val base = (params * bytesPerParam(fileName)).toLong()
        // Add overhead for metadata/KV/cache headroom approximation is handled in fit check.
        return base.coerceAtLeast(1L)
    }

    fun assess(
        model: ModelDetails,
        deviceRamBytes: Long,
        fileSizes: Map<String, Long> = emptyMap(),
        availableStorageBytes: Long? = null
    ): ModelDeviceFit {
        val files = model.allRunnableFiles
        if (files.isEmpty()) {
            return ModelDeviceFit(null, false, DeviceFitLevel.UNKNOWN, emptyList())
        }
        val assessments = files.map { fileName ->
            val size = estimateBytesForFile(model, fileName, fileSizes[fileName] ?: 0L)
            assessFile(fileName, size, deviceRamBytes, availableStorageBytes)
        }
        val ranked = assessments.sortedWith(
            compareBy<FileDeviceFit> { levelRank(it.level) }
                .thenByDescending { scoreFileName(it.fileName) }
                .thenBy { it.estimatedBytes ?: Long.MAX_VALUE }
        )
        val recommended = ranked.firstOrNull { it.level == DeviceFitLevel.GOOD || it.level == DeviceFitLevel.BORDERLINE }?.fileName
            ?: ranked.lastOrNull()?.fileName
        val summary = when {
            assessments.any { it.level == DeviceFitLevel.GOOD } -> DeviceFitLevel.GOOD
            assessments.any { it.level == DeviceFitLevel.BORDERLINE } -> DeviceFitLevel.BORDERLINE
            assessments.all { it.level == DeviceFitLevel.UNKNOWN } -> DeviceFitLevel.UNKNOWN
            else -> DeviceFitLevel.TOO_LARGE
        }
        return ModelDeviceFit(
            recommendedFile = recommended,
            fitsOnDevice = summary == DeviceFitLevel.GOOD || summary == DeviceFitLevel.BORDERLINE,
            summary = summary,
            files = assessments
        )
    }

    private fun assessFile(
        fileName: String,
        estimatedBytes: Long?,
        deviceRamBytes: Long,
        availableStorageBytes: Long?
    ): FileDeviceFit {
        if (estimatedBytes == null || estimatedBytes <= 0L) {
            return FileDeviceFit(fileName, DeviceFitLevel.UNKNOWN)
        }
        if (availableStorageBytes != null && estimatedBytes > availableStorageBytes) {
            return FileDeviceFit(fileName, DeviceFitLevel.TOO_LARGE, estimatedBytes, "storage")
        }
        // MemoryUtils.canLoadModelOfSize uses 1.25x + 400MB floor against total RAM.
        val fitsComfortably = com.ai.tinymodels.core.common.MemoryUtils.canLoadModelOfSize(
            (estimatedBytes * 0.85).toLong().coerceAtLeast(estimatedBytes - 150L * 1024 * 1024),
            deviceRamBytes
        )
        val fitsAtAll = com.ai.tinymodels.core.common.MemoryUtils.canLoadModelOfSize(estimatedBytes, deviceRamBytes)
        val level = when {
            fitsComfortably -> DeviceFitLevel.GOOD
            fitsAtAll -> DeviceFitLevel.BORDERLINE
            else -> DeviceFitLevel.TOO_LARGE
        }
        return FileDeviceFit(fileName, level, estimatedBytes)
    }

    private fun levelRank(level: DeviceFitLevel): Int = when (level) {
        DeviceFitLevel.GOOD -> 0
        DeviceFitLevel.BORDERLINE -> 1
        DeviceFitLevel.UNKNOWN -> 2
        DeviceFitLevel.TOO_LARGE -> 3
    }

    /** Prefer common balanced mobile quants when multiple files fit. */
    private fun scoreFileName(fileName: String): Int {
        val n = fileName.lowercase()
        return when {
            n.contains("q4_k_m") || n.contains("iq4_xs") || n.contains("q4_0") -> 100
            n.contains("q5") || n.contains("q4") -> 90
            n.contains("q3") -> 80
            n.contains("q6") || n.contains("q8") -> 70
            n.contains("q2") || n.contains("iq2") -> 60
            n.endsWith(".litertlm") || n.endsWith(".task") -> 85
            else -> 50
        }
    }

    fun formatParams(count: Long?): String? {
        if (count == null || count <= 0) return null
        return when {
            count >= 1_000_000_000L -> {
                val b = count / 1_000_000_000.0
                if (b >= 10) "${b.roundToInt()}B" else String.format("%.1fB", b)
            }
            count >= 1_000_000L -> "${(count / 1_000_000.0).roundToInt()}M"
            else -> count.toString()
        }
    }
}
