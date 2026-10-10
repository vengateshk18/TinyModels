package com.example.tinymodels.domain.model

/**
 * Per-session inference settings. Stored as columns on [Chat] in Room so each
 * chat session can have its own sampler config, context window, system
 * instruction, and backend — independent of global defaults.
 */
data class InferenceSettings(
    val backend: BackendPreference = BackendPreference.AUTO,
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.95,
    val systemInstruction: String = "You are a helpful assistant."
) {
    /**
     * Context window is NO LONGER user-tunable — it is managed automatically
     * (detected from the model file, with a safe default). This constant is
     * the fallback budget when detection fails.
     */
    companion object {
        const val DEFAULT_CONTEXT_TOKENS = 2048
    }
}
