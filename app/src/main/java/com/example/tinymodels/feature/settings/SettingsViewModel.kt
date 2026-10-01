package com.example.tinymodels.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.domain.model.AppSettings
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.SamplerSettings
import com.example.tinymodels.domain.model.ThemeMode
import com.example.tinymodels.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    fun setBackend(backend: BackendPreference) = viewModelScope.launch { settingsRepository.setBackend(backend) }
    fun setTemperature(value: Double) = viewModelScope.launch {
        settingsRepository.setSampler(settings.value.sampler.copy(temperature = value))
    }
    fun setTopK(value: Int) = viewModelScope.launch {
        settingsRepository.setSampler(settings.value.sampler.copy(topK = value))
    }
    fun setTopP(value: Double) = viewModelScope.launch {
        settingsRepository.setSampler(settings.value.sampler.copy(topP = value))
    }
    fun setMaxContextTokens(tokens: Int) = viewModelScope.launch { settingsRepository.setMaxContextTokens(tokens) }
    fun setAutoUnloadMinutes(minutes: Int) = viewModelScope.launch { settingsRepository.setAutoUnloadMinutes(minutes) }
}
