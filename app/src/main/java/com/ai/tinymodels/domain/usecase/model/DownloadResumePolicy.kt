package com.ai.tinymodels.domain.usecase.model

/** What the worker should do with an HTTP response when a partial file exists. */
enum class ResumeAction {
    /** Server honoured the Range header — append the body to the partial file. */
    APPEND,

    /** This body is the whole file — write it from zero. */
    RESTART,

    /**
     * The range could not be satisfied (416). The body is an error page, so it
     * must be thrown away and the request repeated without a `Range` header.
     */
    RESTART_WITHOUT_RANGE,

    /** Server rejected the request for a reason a retry cannot fix. */
    FAIL
}

/**
 * Decides whether a partially downloaded file can be continued.
 *
 * Paused downloads keep a `.part` file. The shortest correct rule set is:
 * never append when the partial already covers the whole remote file (the
 * worker would happily append zero bytes and never finish), and never append
 * when the server ignored the Range header (that would duplicate bytes and
 * corrupt the file).
 */
object DownloadResumePolicy {

    /** Bytes of the partial file that can be reused, or 0 to start over. */
    fun resumeOffset(partialBytes: Long, totalBytes: Long): Long {
        if (partialBytes <= 0L) return 0L
        if (totalBytes > 0L && partialBytes >= totalBytes) return 0L
        return partialBytes
    }

    /** What to do with a response received after asking to resume at [offset]. */
    fun actionFor(offset: Long, responseCode: Int): ResumeAction = when {
        responseCode == 206 && offset > 0L -> ResumeAction.APPEND
        responseCode == 200 -> ResumeAction.RESTART
        responseCode == 416 -> ResumeAction.RESTART_WITHOUT_RANGE
        responseCode == 206 -> ResumeAction.RESTART
        else -> ResumeAction.FAIL
    }

    /** `Range` header value for [offset], or null when no range is needed. */
    fun rangeHeader(offset: Long): String? =
        if (offset > 0L) "bytes=$offset-" else null
}
