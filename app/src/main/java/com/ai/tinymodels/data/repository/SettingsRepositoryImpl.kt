package com.ai.tinymodels.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ai.tinymodels.core.common.TinyConstants
import com.ai.tinymodels.domain.model.AppSettings
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.FontChoice
import com.ai.tinymodels.domain.model.ThemeMode
import com.ai.tinymodels.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "tiny_models_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private object Keys {
        val THEME_MODE = stringPreferencesKey(TinyConstants.PREFS_THEME_MODE)
        val DYNAMIC_COLOR = booleanPreferencesKey(TinyConstants.PREFS_DYNAMIC_COLOR)
        val FONT_CHOICE = stringPreferencesKey(TinyConstants.PREFS_FONT_CHOICE)
        val FONT_SCALE = floatPreferencesKey(TinyConstants.PREFS_FONT_SCALE)
        val DEFAULT_BACKEND = stringPreferencesKey(TinyConstants.PREFS_DEFAULT_BACKEND)
        val WEB_SEARCH_ENABLED = booleanPreferencesKey(TinyConstants.PREFS_WEB_SEARCH_ENABLED)
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey(TinyConstants.PREFS_WIFI_ONLY_DOWNLOADS)
        val AUTO_LOAD_LAST_MODEL = booleanPreferencesKey(TinyConstants.PREFS_AUTO_LOAD_LAST_MODEL)
        val KEEP_SCREEN_ON = booleanPreferencesKey(TinyConstants.PREFS_KEEP_SCREEN_ON)
        val SHOW_TOKEN_COUNTS = booleanPreferencesKey(TinyConstants.PREFS_SHOW_TOKEN_COUNTS)
        val LAST_USED_MODEL_ID = stringPreferencesKey(TinyConstants.PREFS_LAST_USED_MODEL_ID)
        val HUGGINGFACE_TOKEN = stringPreferencesKey(TinyConstants.PREFS_HUGGING_FACE_TOKEN)
    }

    override val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.DARK,
            useDynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            fontChoice = prefs[Keys.FONT_CHOICE]?.let { runCatching { FontChoice.valueOf(it) }.getOrNull() }
                ?: FontChoice.SYSTEM,
            fontScale = prefs[Keys.FONT_SCALE] ?: 1.0f,
            defaultBackend = prefs[Keys.DEFAULT_BACKEND]?.let { runCatching { BackendPreference.valueOf(it) }.getOrNull() }
                ?: BackendPreference.AUTO,
            webSearchEnabled = prefs[Keys.WEB_SEARCH_ENABLED] ?: true,
            wifiOnlyDownloads = prefs[Keys.WIFI_ONLY_DOWNLOADS] ?: false,
            autoLoadLastModel = prefs[Keys.AUTO_LOAD_LAST_MODEL] ?: true,
            keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
            showTokenCounts = prefs[Keys.SHOW_TOKEN_COUNTS] ?: false
        )
    }

    override suspend fun getSettings(): AppSettings = settings.first()

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setFontChoice(choice: FontChoice) {
        context.dataStore.edit { it[Keys.FONT_CHOICE] = choice.name }
    }

    override suspend fun setFontScale(scale: Float) {
        context.dataStore.edit { it[Keys.FONT_SCALE] = scale }
    }

    override suspend fun setDefaultBackend(backend: BackendPreference) {
        context.dataStore.edit { it[Keys.DEFAULT_BACKEND] = backend.name }
    }

    override suspend fun setWebSearchEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WEB_SEARCH_ENABLED] = enabled }
    }

    override suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WIFI_ONLY_DOWNLOADS] = enabled }
    }

    override suspend fun setAutoLoadLastModel(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_LOAD_LAST_MODEL] = enabled }
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    override suspend fun setShowTokenCounts(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_TOKEN_COUNTS] = enabled }
    }

    override suspend fun getLastUsedModelId(): String? =
        context.dataStore.data.map { it[Keys.LAST_USED_MODEL_ID] }.first()

    override suspend fun setLastUsedModelId(modelId: String) {
        context.dataStore.edit { it[Keys.LAST_USED_MODEL_ID] = modelId }
    }

    override val huggingFaceToken: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.HUGGINGFACE_TOKEN]?.trim()?.takeIf { it.isNotBlank() }
    }

    override suspend fun getHuggingFaceToken(): String? = huggingFaceToken.first()

    override suspend fun setHuggingFaceToken(token: String?) {
        val trimmed = token?.trim()?.takeIf { it.isNotBlank() }
        context.dataStore.edit { prefs ->
            if (trimmed == null) prefs.remove(Keys.HUGGINGFACE_TOKEN)
            else prefs[Keys.HUGGINGFACE_TOKEN] = trimmed
        }
    }
}
