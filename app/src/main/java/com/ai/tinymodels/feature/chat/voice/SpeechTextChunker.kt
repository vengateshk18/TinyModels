package com.ai.tinymodels.feature.chat.voice

/**
 * Incrementally extracts speakable chunks from cumulative streamed text.
 *
 * Feed the full current text on each update via [consume]; it returns only
 * newly completed sentence-like segments so TTS can speak replies "live"
 * without repeating already-spoken text. Call [flush] at the end to speak any
 * remaining trailing fragment.
 */
class SpeechTextChunker {
    private var consumedUpTo: Int = 0
    private var lastText: String = ""

    /**
     * Returns newly completed speakable chunks from [fullText] (cumulative).
     * A chunk is completed when it ends with sentence punctuation or a newline
     * boundary (including punctuation at end of the current text).
     */
    fun consume(fullText: String): List<String> {
        lastText = fullText
        if (consumedUpTo > fullText.length) consumedUpTo = 0
        if (consumedUpTo >= fullText.length) return emptyList()

        val pending = fullText.substring(consumedUpTo)
        val chunks = mutableListOf<String>()
        // Boundaries: sentence punctuation followed by whitespace, punctuation at end,
        // or one/more newlines.
        val boundary = Regex("""(?<=[.!?])(?:\s+|$)|\n+""")
        var searchFrom = 0
        var lastEnd = 0
        while (true) {
            val match = boundary.find(pending, searchFrom) ?: break
            // For end-of-string punctuation match, range can be at end with zero-width feel;
            // include the punctuation and any trailing spaces in the chunk, advance by match end.
            val end = match.range.last + 1
            val raw = pending.substring(lastEnd, end)
            val chunk = raw.trim()
            if (chunk.isNotEmpty()) {
                chunks.add(chunk)
            }
            lastEnd = end
            searchFrom = end
            if (end >= pending.length) break
        }
        if (lastEnd > 0) {
            consumedUpTo += lastEnd
        }
        return chunks
    }

    /** Returns any remaining unspoken text from the last consumed input and marks it as consumed. */
    fun flush(): String? = flush(lastText)

    /** Flushes remaining text from [fullText] that hasn't been consumed yet. */
    fun flush(fullText: String): String? {
        lastText = fullText
        if (consumedUpTo > fullText.length) consumedUpTo = 0
        if (consumedUpTo >= fullText.length) return null
        val remaining = fullText.substring(consumedUpTo).trim()
        consumedUpTo = fullText.length
        return remaining.takeIf { it.isNotEmpty() }
    }

    fun reset() {
        consumedUpTo = 0
        lastText = ""
    }
}