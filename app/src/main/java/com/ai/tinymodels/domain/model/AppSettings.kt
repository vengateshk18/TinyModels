package com.ai.tinymodels.domain.model

import androidx.annotation.StringRes
import com.ai.tinymodels.R

/** Visual theme preference. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * Font family choices for the app UI. SYSTEM uses the device default;
 * the rest are downloaded on-demand via Google Fonts (requires network
 * on first use, then cached by GMS).
 */
enum class FontChoice(@StringRes val labelRes: Int, val googleFontName: String?) {
    SYSTEM(R.string.font_system, null),
    ABEL(R.string.font_abel, "Abel"),
    INTER(R.string.font_inter, "Inter"),
    ROBOTO(R.string.font_roboto, "Roboto"),
    PACIFICO(R.string.font_pacifico, "Pacifico"),
    ABRIL_FATFACE(R.string.font_abril_fatface, "Abril Fatface")
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
    val wifiOnlyDownloads: Boolean = false,
    /** Auto-load the last-used model when entering chat (else pick manually). */
    val autoLoadLastModel: Boolean = true,
    /** Keep the screen on while the model is generating (else allow sleep). */
    val keepScreenOn: Boolean = true,
    /** Show per-message token counts in chat footers (uses stored counts). */
    val showTokenCounts: Boolean = false
)
