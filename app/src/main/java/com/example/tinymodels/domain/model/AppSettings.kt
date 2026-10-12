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
 * User-tunable app-level settings. Persisted via DataStore.
 * Inference settings (temperature, topK, topP, maxContextTokens,
 * systemInstruction) are now per-session (stored in Room on each Chat).
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val useDynamicColor: Boolean = true,
    val fontChoice: FontChoice = FontChoice.SYSTEM,
    val fontScale: Float = 1.0f,
    val defaultBackend: BackendPreference = BackendPreference.AUTO,
    /**
     * Global availability gate for the model's web_search tool. Each chat
     * can then opt in/out individually (per-chat column in Room).
     */
    val webSearchEnabled: Boolean = true,
    /**
     * When true, model downloads require an unmetered connection (Wi-Fi).
     * A 4 GB model on mobile data is how users burn a month's quota in an hour.
     */
    val wifiOnlyDownloads: Boolean = false
)
