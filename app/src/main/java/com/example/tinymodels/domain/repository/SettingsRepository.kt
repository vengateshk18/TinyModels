package com.example.tinymodels.domain.repository

import com.example.tinymodels.domain.model.AppSettings
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.FontChoice
import com.example.tinymodels.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setFontChoice(choice: FontChoice)
    suspend fun setFontScale(scale: Float)
    suspend fun setDefaultBackend(backend: BackendPreference)

    /** Returns the modelId of the last successfully loaded model, or null. */
    suspend fun getLastUsedModelId(): String?

    /** Persists [modelId] as the last-used model. */
    suspend fun setLastUsedModelId(modelId: String)
}
