package com.example.tinymodels.domain.repository

import com.example.tinymodels.domain.model.AppSettings
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.FontChoice
import com.example.tinymodels.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    /**
     * One-shot blocking read of the persisted settings. Used at app start
     * (before first composition) so the theme is correct on the very first
     * frame — collecting [settings] with a default initial value instead
     * causes a visible theme flash (light -> dark) on launch.
     */
    suspend fun getSettings(): AppSettings

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setFontChoice(choice: FontChoice)
    suspend fun setFontScale(scale: Float)
    suspend fun setDefaultBackend(backend: BackendPreference)

    /** Global availability gate for the model's web_search tool. */
    suspend fun setWebSearchEnabled(enabled: Boolean)

    /** Returns the modelId of the last successfully loaded model, or null. */
    suspend fun getLastUsedModelId(): String?

    /** Persists [modelId] as the last-used model. */
    suspend fun setLastUsedModelId(modelId: String)

    /** The stored Hugging Face access token (trimmed), or null when unset. */
    val huggingFaceToken: Flow<String?>

    /** One-shot read of the stored Hugging Face token. */
    suspend fun getHuggingFaceToken(): String?

    /** Persists (or clears, when null/blank) the Hugging Face access token. */
    suspend fun setHuggingFaceToken(token: String?)
}
