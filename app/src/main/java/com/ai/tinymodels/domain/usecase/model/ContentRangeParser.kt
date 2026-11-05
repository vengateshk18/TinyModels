package com.ai.tinymodels.domain.usecase.model

/** Parsed `Content-Range: bytes <start>-<end>/<total>` header. */
data class ContentRange(
    val start: Long,
    val end: Long,
    /** Total object size, or 0 when the server sent `*`. */
    val total: Long
)

/**
 * Strict parser for the `Content-Range` header.
 *
 * The worker only appends when the server confirms it is continuing from the
 * exact offset we asked for. Anything unexpected returns null, and the worker
 * then restarts the file from zero instead of splicing mismatched bytes
 * together.
 */
object ContentRangeParser {

    private val PATTERN = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""")

    fun parse(header: String?): ContentRange? {
        val match = PATTERN.find(header?.trim().orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = if (match.groupValues[3] == "*") 0L else match.groupValues[3].toLongOrNull() ?: return null
        if (end < start) return null
        if (total > 0L && end >= total) return null
        return ContentRange(start = start, end = end, total = total)
    }
}
