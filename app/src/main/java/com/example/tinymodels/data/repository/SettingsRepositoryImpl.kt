package com.example.tinymodels.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.tinymodels.domain.model.AppSettings
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.SamplerSettings
import com.example.tinymodels.domain.model.ThemeMode
import com.example.tinymodels.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
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
        val BACKEND = stringPreferencesKey("backend")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val TOP_K = intPreferencesKey("top_k")
        val TOP_P = doublePreferencesKey("top_p")
        val MAX_CONTEXT_TOKENS = intPreferencesKey("max_context_tokens")
        val SYSTEM_INSTRUCTION = stringPreferencesKey("system_instruction")
        val AUTO_UNLOAD_MINUTES = intPreferencesKey("auto_unload_minutes")
    }

    override val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            useDynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            backend = prefs[Keys.BACKEND]?.let { runCatching { BackendPreference.valueOf(it) }.getOrNull() }
                ?: BackendPreference.AUTO,
            sampler = SamplerSettings(
                temperature = prefs[Keys.TEMPERATURE] ?: 0.7,
                topK = prefs[Keys.TOP_K] ?: 40,
                topP = prefs[Keys.TOP_P] ?: 0.95
            ),
            maxContextTokens = prefs[Keys.MAX_CONTEXT_TOKENS] ?: 2048,
            systemInstruction = prefs[Keys.SYSTEM_INSTRUCTION] ?: "You are a helpful assistant.",
            autoUnloadMinutes = prefs[Keys.AUTO_UNLOAD_MINUTES] ?: 0
        )
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setBackend(backend: BackendPreference) {
        context.dataStore.edit { it[Keys.BACKEND] = backend.name }
    }

    override suspend fun setSampler(sampler: SamplerSettings) {
        context.dataStore.edit {
            it[Keys.TEMPERATURE] = sampler.temperature
            it[Keys.TOP_K] = sampler.topK
            it[Keys.TOP_P] = sampler.topP
        }
    }

    override suspend fun setMaxContextTokens(tokens: Int) {
        context.dataStore.edit { it[Keys.MAX_CONTEXT_TOKENS] = tokens }
    }

    override suspend fun setSystemInstruction(instruction: String) {
        context.dataStore.edit { it[Keys.SYSTEM_INSTRUCTION] = instruction }
    }

    override suspend fun setAutoUnloadMinutes(minutes: Int) {
        context.dataStore.edit { it[Keys.AUTO_UNLOAD_MINUTES] = minutes }
    }
}
