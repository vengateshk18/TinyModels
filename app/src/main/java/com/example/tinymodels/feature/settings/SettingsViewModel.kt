package com.example.tinymodels.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tinymodels.domain.model.AppSettings
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.FontChoice
import com.example.tinymodels.domain.model.ThemeMode
import com.example.tinymodels.domain.repository.ChatRepository
import com.example.tinymodels.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _clearResult = MutableStateFlow<String?>(null)
    val clearResult: StateFlow<String?> = _clearResult.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { settingsRepository.setDynamicColor(enabled) }
    fun setFontChoice(choice: FontChoice) = viewModelScope.launch { settingsRepository.setFontChoice(choice) }
    fun setFontScale(scale: Float) = viewModelScope.launch { settingsRepository.setFontScale(scale) }
    fun setDefaultBackend(backend: BackendPreference) = viewModelScope.launch { settingsRepository.setDefaultBackend(backend) }

    fun clearChatHistory() = viewModelScope.launch {
        chatRepository.clearAllChats()
        _clearResult.value = "Chat history cleared"
    }

    fun consumeClearResult() { _clearResult.value = null }
}
