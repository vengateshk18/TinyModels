package com.ai.tinymodels.core.network.cloud

import org.json.JSONObject

/** One decoded piece of an OpenAI-compatible streaming response. */
sealed interface CloudChunk {
    /** A text piece. Callers accumulate these; the chat layer shows cumulative text. */
    data class Delta(val text: String) : CloudChunk

    /** The provider refused or failed mid-stream. */
    data class Failure(val message: String) : CloudChunk

    /** The `data: [DONE]` sentinel. */
    data object Done : CloudChunk
}

/**
 * Decodes Server-Sent Events lines from the Hugging Face router.
 *
 * Kept pure so the wire format is unit-tested without a network call. The
 * router follows the OpenAI chat-completions shape:
 * `data: {"choices":[{"delta":{"content":"…"}}]}` and `data: [DONE]`.
 */
object CloudStreamParser {

    /** Returns null for lines that carry no text (blank, comments, metadata). */
    fun parse(line: String): CloudChunk? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(":")) return null
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        if (payload == "[DONE]") return CloudChunk.Done

        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        obj.optJSONObject("error")?.let { error ->
            return CloudChunk.Failure(error.optString("message").ifBlank { payload })
        }

        val choices = obj.optJSONArray("choices") ?: return null
        val first = choices.optJSONObject(0) ?: return null
        val content = first.optJSONObject("delta")?.optString("content").orEmpty()
            .ifBlank { first.optJSONObject("message")?.optString("content").orEmpty() }
        return content.takeIf { it.isNotEmpty() }?.let(CloudChunk::Delta)
    }
}
