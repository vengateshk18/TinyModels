package com.ai.tinymodels.feature.chat.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Photo helpers shared by the input-bar preview and message bubbles.
 * Bitmaps are downsampled at decode time (never full camera resolution in
 * memory) so a 12 MP shot can't OOM the chat list.
 */
object ChatImageFiles {

    /** Decodes [path] capped at [maxSize] px on the long edge. Null when unreadable. */
    fun decodeFile(path: String, maxSize: Int): Bitmap? {
        return try {
            if (!File(path).exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Throwable) {
            null
        }
    }
}

/** Rounded photo shown at the top of a user bubble. Silent when unreadable. */
@Composable
fun AttachedPhoto(
    path: String,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(path) { ChatImageFiles.decodeFile(path, maxSize = 1024) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Attached photo",
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
    }
}
