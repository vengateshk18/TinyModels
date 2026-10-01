package com.example.tinymodels.domain.repository

import com.example.tinymodels.domain.model.AppSettings
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.SamplerSettings
import com.example.tinymodels.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setBackend(backend: BackendPreference)
    suspend fun setSampler(sampler: SamplerSettings)
    suspend fun setMaxContextTokens(tokens: Int)
    suspend fun setSystemInstruction(instruction: String)
    suspend fun setAutoUnloadMinutes(minutes: Int)
}
