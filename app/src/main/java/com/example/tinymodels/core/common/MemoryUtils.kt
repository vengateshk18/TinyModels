package com.example.tinymodels.core.common

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Helpers for reasoning about device memory before/while loading large LLM weights.
 */
object MemoryUtils {

    data class MemorySnapshot(
        val availableBytes: Long,
        val totalBytes: Long,
        val lowMemory: Boolean,
        val thresholdBytes: Long
    )

    fun snapshot(context: Context): MemorySnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return MemorySnapshot(
            availableBytes = info.availMem,
            totalBytes = info.totalMem,
            lowMemory = info.lowMemory,
            thresholdBytes = info.threshold
        )
    }

    fun availableBytes(context: Context): Long = snapshot(context).availableBytes

    fun totalRamBytes(context: Context): Long = snapshot(context).totalBytes

    /**
     * Estimate how much free RAM loading [modelFile] needs. The runtime must page
     * weights into memory plus allocate a KV cache, so we use a safety multiplier.
     */
    fun requiredBytesForModel(modelFile: File, safetyMultiplier: Double = 1.25): Long {
        val sizeBytes = if (modelFile.exists()) modelFile.length() else 0L
        return (sizeBytes * safetyMultiplier).toLong()
    }

    /**
     * True when [snapshot] suggests there is enough headroom to load a model
     * that needs [requiredBytes]. Adds a floor of 400MB so the OS keeps room
     * for the rest of the system and avoids the low-memory killer.
     */
    fun hasHeadroom(snapshot: MemorySnapshot, requiredBytes: Long, floorBytes: Long = 400L * 1024 * 1024): Boolean {
        if (snapshot.lowMemory) return false
        return snapshot.availableBytes - requiredBytes >= floorBytes
    }

    /**
     * True when a model file of [fileSizeBytes] can realistically be loaded and
     * used on a device with [totalRamBytes] of RAM. Applies the same math as
     * [requiredBytesForModel] (size × 1.25 safety multiplier) plus the system
     * floor from [hasHeadroom] (400MB), but against TOTAL RAM rather than a
     * live snapshot — this is a pre-download capacity judgment, and available
     * memory fluctuates by the time the user actually loads the model.
     */
    fun canLoadModelOfSize(fileSizeBytes: Long, totalRamBytes: Long): Boolean {
        if (fileSizeBytes <= 0) return true // Unknown size — can't judge; assume loadable.
        val required = (fileSizeBytes * 1.25).toLong()
        return totalRamBytes - required >= 400L * 1024 * 1024
    }
}
