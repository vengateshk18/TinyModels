package com.example.tinymodels.domain.model

/**
 * User-facing preferred backend. AUTO resolves to GPU with CPU fallback at load time.
 */
enum class BackendPreference { AUTO, GPU, CPU }

/**
 * Sampling parameters for generation. Values are Double to match the LiteRT-LM
 * SamplerConfig API (a common integration gotcha is passing Float).
 */
data class SamplerSettings(
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.95
)

/**
 * A resolved, load-time configuration for the engine. Derived from user settings
 * plus the concrete model file being loaded.
 */
data class EngineLoadConfig(
    val modelPath: String,
    val backend: BackendPreference,
    val maxNumTokens: Int,
    val cacheDir: String
)