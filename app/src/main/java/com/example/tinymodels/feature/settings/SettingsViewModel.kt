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
import kotlinx.coroutines.Dispatchers
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

    /** Stored Hugging Face access token, or null when unset. */
    val huggingFaceToken: StateFlow<String?> = settingsRepository.huggingFaceToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _clearResult = MutableStateFlow<String?>(null)
    val clearResult: StateFlow<String?> = _clearResult.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setDynamicColor(enabled) }
    fun setFontChoice(choice: FontChoice) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setFontChoice(choice) }
    fun setFontScale(scale: Float) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setFontScale(scale) }
    fun setDefaultBackend(backend: BackendPreference) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setDefaultBackend(backend) }

    /** Saves (or removes, when null/blank) the Hugging Face access token. */
    fun setHuggingFaceToken(token: String?) = viewModelScope.launch(Dispatchers.Default) {
        settingsRepository.setHuggingFaceToken(token)
        _clearResult.value = if (token.isNullOrBlank()) "Access token removed" else "Access token saved"
    }

    fun clearChatHistory() = viewModelScope.launch(Dispatchers.Default) {
        chatRepository.clearAllChats()
        _clearResult.value = "Chat history cleared"
    }

    fun consumeClearResult() { _clearResult.value = null }
}
