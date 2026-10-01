package com.example.tinymodels.domain.model

/** Visual theme preference. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * User-tunable app + inference settings. Persisted via DataStore.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val backend: BackendPreference = BackendPreference.AUTO,
    val sampler: SamplerSettings = SamplerSettings(),
    val maxContextTokens: Int = 2048,
    val systemInstruction: String = "You are a helpful assistant.",
    val autoUnloadMinutes: Int = 0 // 0 = never auto-unload
)
