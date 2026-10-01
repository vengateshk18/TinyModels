package com.example.tinymodels.core.common

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Free-space checks for downloads. */
@Singleton
class StorageUtils @Inject constructor(@ApplicationContext private val context: Context) {

    /** Available bytes on the volume holding the app's internal files dir. */
    fun freeBytes(): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(0L)

    /** True if [neededBytes] fits with a small safety margin. */
    fun hasSpaceFor(neededBytes: Long): Boolean {
        if (neededBytes <= 0) return true // unknown size -> don't block
        val margin = (neededBytes * 0.05).toLong() + 50L * 1024 * 1024 // 5% + 50MB headroom
        return freeBytes() >= neededBytes + margin
    }
}
