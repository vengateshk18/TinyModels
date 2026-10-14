package com.example.tinymodels.core.inference

import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * Vision-capability helpers for `.litertlm` models.
 *
 * Root cause of the `NOT_FOUND: TF_LITE_VISION_ENCODER not found in the model`
 * crash: [ModelManager] unconditionally configured every engine with a vision
 * backend (`visionBackend` + `maxNumImages = 4`). For text-only models — which
 * ship no vision encoder section — the native runtime then fails inside
 * `engine.createConversation()`, and [ChatViewModel.rebuildSession] only
 * caught [InferenceException], so the native [RuntimeException] escaped and
 * killed the app (FATAL EXCEPTION on DefaultDispatcher-worker).
 *
 * Contract:
 * - Engines for text-only models must be built WITHOUT a vision backend.
 * - Histories restored onto a text-only engine must have image parts stripped
 *   (photo turns degrade to their text); photo sends are blocked up-front
 *   with a friendly message instead of a native failure.
 * - Conversation creation must never crash the app: native failures are
 *   mapped to user-facing errors, with one self-healing path (reload the
 *   live engine without vision and retry) for engines that were built with
 *   vision against a model that has no encoder.
 */
object ModelVisionSupport {

    private const val TAG = "ModelVisionSupport"

    /**
     * How many leading bytes of the container are scanned for the vision
     * encoder marker. Section headers / metadata live at the start of the
     * `.litertlm` container, so a small prefix scan finds the marker without
     * reading gigabytes of weights. Bias is deliberately toward FALSE
     * negatives (a missed vision model just loses photo support) over false
     * positives (claiming vision for a text-only model crashes conversation
     * creation).
     */
    internal const val HEADER_SCAN_BYTES: Long = 8L * 1024L * 1024L

    /** Markers that only appear when the container ships a vision encoder. */
    private val VISION_MARKERS = listOf(
        "tf_lite_vision_encoder",
        "vision_encoder",
        "vision-encoder"
    )

    /**
     * True when [file] looks like a vision-capable (multimodal) model.
     * Never throws — returns false when the file is missing or unreadable.
     */
    fun supportsVision(file: File): Boolean {
        try {
            if (!file.isFile || !file.exists() || !file.canRead()) return false
            if (scanHeaderForVision(file)) return true
            // Narrow filename fallback: only an explicit "vision" token counts.
            // Generic family names (e.g. gemma-3) cover text-only variants too,
            // so they must NOT imply vision here.
            return file.name.contains("vision", ignoreCase = true)
        } catch (throwable: Throwable) {
            Log.w(TAG, "Vision detection failed for ${file.name}: ${throwable.message}")
            return false
        }
    }

    /**
     * Scans the container header for vision-encoder markers. Reads at most
     * [HEADER_SCAN_BYTES] in streaming chunks with an overlap window so a
     * marker split across two reads is still found.
     */
    internal fun scanHeaderForVision(file: File): Boolean {
        FileInputStream(file).use { input ->
            val chunk = ByteArray(8192)
            // Longest marker is ~22 chars; keep a 64-char overlap between reads.
            val overlap = 64
            var carry = ""
            var remaining = HEADER_SCAN_BYTES
            while (remaining > 0) {
                val read = input.read(chunk)
                if (read <= 0) break
                remaining -= read
                // Decode as ISO-8859-1 so every byte maps 1:1 to a char and
                // multi-byte sequences can't corrupt the ASCII marker text.
                val text = carry + String(chunk, 0, read, Charsets.ISO_8859_1)
                val lower = text.lowercase()
                if (VISION_MARKERS.any { lower.contains(it) }) return true
                carry = if (text.length > overlap) text.takeLast(overlap) else text
            }
            return false
        }
    }

    /**
     * True when [throwable] is the native "model has no vision encoder"
     * failure. Matches the exact runtime marker plus the two most common
     * paraphrases so future runtime wordings are still caught.
     */
    fun isVisionEncoderError(throwable: Throwable): Boolean {
        val message = buildString {
            var current: Throwable? = throwable
            while (current != null) {
                append(current.message ?: "")
                append(' ')
                current = current.cause
            }
        }
        return message.contains("TF_LITE_VISION_ENCODER") ||
            message.contains("vision encoder", ignoreCase = true) ||
            message.contains("vision-encoder", ignoreCase = true)
    }

    /** True when any turn in [history] carries attached photos. */
    fun historyHasImages(history: List<ChatTurn>): Boolean =
        history.any { it.imagePaths.isNotEmpty() }

    /**
     * Returns [history] with photo attachments removed (text preserved).
     * Used when restoring a photo-carrying chat onto a text-only engine.
     */
    fun stripImages(history: List<ChatTurn>): List<ChatTurn> =
        history.map { turn ->
            if (turn.imagePaths.isEmpty()) turn else turn.copy(imagePaths = emptyList())
        }

    /** User-facing message for a model that cannot read photos. */
    fun visionUnsupportedMessage(): String =
        "This model doesn't include a vision encoder, so it can't read photos. " +
            "Load a vision-capable model (e.g. Gemma 3) to analyze images — " +
            "your text was kept and photo attachments were skipped."
}
