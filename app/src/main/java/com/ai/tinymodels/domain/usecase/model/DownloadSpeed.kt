package com.ai.tinymodels.domain.usecase.model

/**
 * Converts a transfer into the bytes-per-second figure the UI shows.
 *
 * Kept separate from the worker because a resumed download reports *absolute*
 * bytes downloaded (the file starts at the resume offset), while the user
 * expects a rate for the transfer happening now. Passing only the bytes
 * transferred this session keeps the number honest.
 */
object DownloadSpeed {

    private const val MIN_ELAPSED_MS = 500L

    fun bytesPerSecond(transferredBytes: Long, elapsedMs: Long): Long {
        if (elapsedMs < MIN_ELAPSED_MS || transferredBytes <= 0L) return 0L
        return transferredBytes * 1000L / elapsedMs
    }
}
