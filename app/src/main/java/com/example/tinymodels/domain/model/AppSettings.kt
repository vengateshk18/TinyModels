package com.example.tinymodels.domain.model

/** Visual theme preference. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Font family choices for the app UI. SYSTEM uses the device default;
 * the rest are downloaded on-demand via Google Fonts (requires network
 * on first use, then cached by GMS).
 */
enum class FontChoice(val displayName: String, val googleFontName: String?) {
    SYSTEM("System", null),
    ABEL("Abel", "Abel"),
    INTER("Inter", "Inter"),
    ROBOTO("Roboto", "Roboto"),
    PACIFICO("Pacifico", "Pacifico"),
    ABRIL_FATFACE("Abril Fatface", "Abril Fatface")
}

/**
 * User-tunable app + inference settings. Persisted via DataStore.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val fontChoice: FontChoice = FontChoice.SYSTEM,
    val backend: BackendPreference = BackendPreference.AUTO,
    val sampler: SamplerSettings = SamplerSettings(),
    val maxContextTokens: Int = 2048,
    val systemInstruction: String = "You are a helpful assistant.",
    val autoUnloadMinutes: Int = 0 // 0 = never auto-unload
)
