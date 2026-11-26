package com.ai.tinymodels.feature.chat.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * Image prep for vision models: read EXIF rotation, downscale to a sane
 * max edge, and return JPEG bytes. Pure math is exposed for unit tests;
 * Bitmap operations require Android runtime.
 */
object ImageAttachmentProcessor {

    private const val DEFAULT_MAX_EDGE = 1568
    private const val JPEG_QUALITY = 90

    fun exifRotationDegrees(orientation: Int): Int = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }

    /** Target size after rotation and downscaling, preserving aspect. */
    fun targetSize(width: Int, height: Int, rotationDegrees: Int, maxEdge: Int): Pair<Int, Int> {
        val rotatesSides = rotationDegrees % 180 != 0
        val w = if (rotatesSides) height else width
        val h = if (rotatesSides) width else height
        val longest = maxOf(w, h)
        if (longest <= maxEdge || maxEdge <= 0) return w to h
        val scale = maxEdge.toFloat() / longest.toFloat()
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        return outW to outH
    }

    /** Power-of-two sample size for the decode pass. */
    fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > maxEdge) sample *= 2
        return sample.coerceAtLeast(1)
    }

    /**
     * Decode, rotate by EXIF, downscale, and compress as JPEG.
     * Returns null if the bytes cannot be decoded.
     */
    fun decodeAndProcess(bytes: ByteArray, maxEdge: Int = DEFAULT_MAX_EDGE): ByteArray? {
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val rotation = exifRotationDegrees(orientation)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        val processed = rotateAndScale(decoded, rotation, maxEdge)
        if (processed !== decoded && !decoded.isRecycled) decoded.recycle()

        return try {
            val out = java.io.ByteArrayOutputStream()
            val ok = processed.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (!ok) return null
            out.toByteArray()
        } finally {
            if (!processed.isRecycled) processed.recycle()
        }
    }

    internal fun rotateAndScale(src: Bitmap, rotationDegrees: Int, maxEdge: Int): Bitmap {
        val (targetW, targetH) = targetSize(src.width, src.height, rotationDegrees, maxEdge)
        val matrix = Matrix()
        if (rotationDegrees != 0) matrix.postRotate(rotationDegrees.toFloat())
        val scaledW = if (rotationDegrees % 180 != 0) targetH else targetW
        val scaledH = if (rotationDegrees % 180 != 0) targetW else targetH
        if (scaledW != src.width || scaledH != src.height) {
            val sx = scaledW.toFloat() / src.width.toFloat()
            val sy = scaledH.toFloat() / src.height.toFloat()
            matrix.postScale(sx, sy)
        }
        if (matrix.isIdentity) return src
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
