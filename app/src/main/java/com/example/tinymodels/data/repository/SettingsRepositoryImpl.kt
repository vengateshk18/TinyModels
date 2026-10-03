package com.example.tinymodels.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.tinymodels.domain.model.AppSettings
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.FontChoice
import com.example.tinymodels.domain.model.ThemeMode
import com.example.tinymodels.domain.repository.SettingsRepository
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
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val FONT_CHOICE = stringPreferencesKey("font_choice")
        val FONT_SCALE = floatPreferencesKey("font_scale")
        val DEFAULT_BACKEND = stringPreferencesKey("default_backend")
        val LAST_USED_MODEL_ID = stringPreferencesKey("last_used_model_id")
        val HUGGINGFACE_TOKEN = stringPreferencesKey("hugging_face_token")
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
                ?: BackendPreference.AUTO
        )
    }

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
