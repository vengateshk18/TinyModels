package com.ai.tinymodels.core.network

/** Page-reading budget: give the model enough context, not the whole web. */
object SearchBudget {
    fun pageChars(contextTokens: Int?): Int {
        if (contextTokens == null) return 1_500
        return (contextTokens / 20).coerceIn(800, 2_500)
    }
}
