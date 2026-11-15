package com.ai.tinymodels.core.inference.runtime

/**
 * Pure session-budget rules shared by all runtimes: token estimation, the
 * sliding-window trim, the compaction-digest contract, and the system
 * instruction fold (user system prompt + digest of summarized-away turns).
 *
 * Everything here is runtime-agnostic and unit-tested — the runtime sessions
 * (LiteRT-LM, MediaPipe) only consume it.
 */
object SessionBudget {

    /** Characters per estimated token for the heuristic token estimate. */
    const val CHARS_PER_TOKEN = 4

    /**
     * Conservative per-photo cost. Vision encoders turn one image into
     * hundreds of tokens; over-counting slightly is safer than a
     * Status-13 overflow because the estimate was short.
     */
    const val IMAGE_TOKEN_COST = 1024

    /** Rough per-tool cost (schema JSON the runtime injects into context). */
    internal const val TOOL_TOKEN_OVERHEAD = 200

    /** Fraction of the context budget reserved for the assistant's reply. */
    const val GENERATION_HEADROOM_RATIO = 0.25

    /**
     * Ceiling for a compaction digest. The digest is folded into the system
     * instruction of every later prompt, so its cost is paid on every single
     * turn — a fat summary is a permanent tax on the context window.
     */
    const val MAX_DIGEST_TOKENS = 256

    /** Floor, so a tiny window still gets a digest worth keeping. */
    const val MIN_DIGEST_TOKENS = 64

    /** Prompt used when asking the model to digest older turns. */
    const val SUMMARIZE_PROMPT =
        "Summarize the following conversation so far in under 200 tokens. " +
            "Keep: user goals, decisions, constraints, key facts, and any " +
            "unresolved questions. Drop pleasantries and repetition. " +
            "Reply with the summary only.\n\n"

    /** Heuristic estimate: ~4 characters per token, never zero. */
    fun estimateTokens(text: String): Int =
        (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)

    /**
     * Sliding-window trim: keep the most recent turns whose combined estimated
     * tokens fit within [budget]. Always keeps at least the last turn if present.
     */
    fun trimToBudget(history: List<ChatTurn>, budget: Int): List<ChatTurn> {
        if (history.isEmpty()) return emptyList()
        var tokens = 0
        val kept = mutableListOf<ChatTurn>()
        for (turn in history.asReversed()) {
            val turnTokens = estimateTokens(turn.text) + turn.imagePaths.size * IMAGE_TOKEN_COST
            if (tokens + turnTokens > budget && kept.isNotEmpty()) break
            tokens += turnTokens
            kept.add(0, turn)
        }
        return kept
    }

    /**
     * Whether a message is covered by the compaction digest and must be
     * excluded from the live session history. Contract: messages created
     * at or before [cutoffCreatedAt] are covered; null cutoff covers nothing.
     */
    fun isCoveredByDigest(createdAt: Long, cutoffCreatedAt: Long?): Boolean =
        cutoffCreatedAt != null && createdAt <= cutoffCreatedAt

    /**
     * Fold the user's system instruction and the compaction digest into one
     * system text (digest labelled), or null when neither exists. Both
     * runtimes feed the result to the model the same way — LiteRT-LM as its
     * system instruction, MediaPipe folded into the first user turn.
     */
    fun foldSystemInstruction(systemInstruction: String?, priorSummary: String?): String? =
        buildString {
            systemInstruction?.takeIf { it.isNotBlank() }?.let { append(it) }
            priorSummary?.takeIf { it.isNotBlank() }?.let {
                if (isNotEmpty()) append("\n\n")
                append("Conversation so far (summary of earlier messages):\n")
                append(it)
            }
        }.takeIf { it.isNotBlank() }

    /**
     * How many tokens a digest may hold for a given context window: an eighth
     * of it, clamped. A digest that can never exceed this cannot grow back
     * into the problem compaction just solved (iterative summarization feeds
     * the previous digest back in, so an unbounded one creeps up every round).
     */
    fun digestTokenBudget(windowTokens: Int): Int =
        (windowTokens / 8).coerceIn(MIN_DIGEST_TOKENS, MAX_DIGEST_TOKENS)

    /**
     * Accept a model-produced digest, enforcing [maxTokens]. Blank or null
     * input means summarization failed and the caller must fall back to the
     * plain trim. Over-long input is cut at a sentence boundary where possible
     * so the stored digest reads like a note rather than a severed word.
     */
    fun acceptDigest(text: String?, maxTokens: Int = MAX_DIGEST_TOKENS): String? {
        val normalized = text?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        val charLimit = maxTokens.coerceAtLeast(1) * CHARS_PER_TOKEN
        if (normalized.length <= charLimit) return normalized
        val head = normalized.take(charLimit)
        // Prefer a clean end, but never give back most of the digest for it.
        val boundary = head.indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
        return if (boundary >= charLimit / 2) head.substring(0, boundary + 1).trim() else head.trim()
    }
}
