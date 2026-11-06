package com.ai.tinymodels.domain.usecase.model

/**
 * Whether a single file's total size is known yet.
 *
 * Sizes come from a HEAD request per file, so a row goes through three states:
 * still asking, got the number, or gave up. The UI wordings ("Loading…",
 * "Can't be determined") hang off this enum rather than being guessed from a
 * zero byte count, which cannot tell the two apart.
 */
enum class FileSizeStatus {
    /** The HEAD request has not finished. */
    LOADING,

    /** The HEAD request returned a size. */
    KNOWN,

    /** The HEAD request failed or never ran; the size cannot be determined. */
    UNKNOWN
}
