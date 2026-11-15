package com.ai.tinymodels.core.inference.gguf

import com.ai.tinymodels.core.inference.runtime.ChatTurn
import com.ai.tinymodels.core.inference.runtime.ConversationSession
import com.ai.tinymodels.core.inference.runtime.SessionBudget

/** One summarization attempt, as reported by the vendored GGUF engine. */
internal data class GgufDigestAttempt(val summary: String?, val success: Boolean)

/**
 * GGUF's side of context compaction: the transcript sent for summarization,
 * the token ceiling handed to the engine, and the acceptance rules applied to
 * whatever comes back.
 *
 * `GGMLEngine.compact` is native and blocking, so it arrives as a lambda here —
 * that keeps every decision in this object unit-tested (see
 * `GgufCompactionTest`) and leaves the session with a one-line adapter.
 */
internal object GgufCompaction {

    /**
     * Summarize [older] into a digest, or null when there is nothing to
     * summarize or the engine reports failure (caller then trims instead).
     *
     * @param compact engine call: (transcript, maxSummaryTokens) → attempt.
     */
    suspend fun digest(
        older: List<ChatTurn>,
        maxContextTokens: Int,
        compact: suspend (transcript: String, maxTokens: Int) -> GgufDigestAttempt,
    ): String? {
        if (older.isEmpty()) return null
        val budget = SessionBudget.digestTokenBudget(maxContextTokens)
        val attempt = compact(ConversationSession.summarizeTranscript(older), budget)
        if (!attempt.success) return null
        return SessionBudget.acceptDigest(attempt.summary, budget)
    }
}