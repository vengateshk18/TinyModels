package com.example.titymodels.core.common

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
}