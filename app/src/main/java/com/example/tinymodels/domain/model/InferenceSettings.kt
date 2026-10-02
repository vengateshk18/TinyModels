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
    val maxContextTokens: Int = 2048,
    val systemInstruction: String = "You are a helpful assistant."
)
