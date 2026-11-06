package com.ai.tinymodels.feature.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.AppLocaleStore
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.domain.model.AppLanguage
import com.ai.tinymodels.domain.model.AppSettings
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.FontChoice
import com.ai.tinymodels.domain.model.ThemeMode
import com.ai.tinymodels.domain.repository.ChatRepository
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @ApplicationContext private val context: Context,
    private val appLocaleStore: AppLocaleStore,
    private val settingsRepository: SettingsRepository,
    private val chatRepository: ChatRepository,
    private val modelRepository: ModelRepository
) : ViewModel() {

    /** Currently selected app language (drives the Appearance → Language row). */
    val language: StateFlow<AppLanguage> = appLocaleStore.language

    /** Stores the chosen language; the caller applies it to the running app. */
    fun setLanguage(language: AppLanguage) {
        appLocaleStore.set(language)
    }

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Stored Hugging Face access token, or null when unset. */
    val huggingFaceToken: StateFlow<String?> = settingsRepository.huggingFaceToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _clearResult = MutableStateFlow<String?>(null)
    val clearResult: StateFlow<String?> = _clearResult.asStateFlow()

    /** Loading state for model import. */
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setThemeMode(mode) }
    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setDynamicColor(enabled) }
    fun setFontChoice(choice: FontChoice) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setFontChoice(choice) }
    fun setFontScale(scale: Float) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setFontScale(scale) }
    fun setDefaultBackend(backend: BackendPreference) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setDefaultBackend(backend) }
    fun setWebSearchEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setWebSearchEnabled(enabled) }
    fun setWifiOnlyDownloads(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setWifiOnlyDownloads(enabled) }
    fun setAutoLoadLastModel(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setAutoLoadLastModel(enabled) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setKeepScreenOn(enabled) }
    fun setShowTokenCounts(enabled: Boolean) = viewModelScope.launch(Dispatchers.Default) { settingsRepository.setShowTokenCounts(enabled) }

    /** Saves (or removes, when null/blank) the Hugging Face access token. */
    fun setHuggingFaceToken(token: String?) = viewModelScope.launch(Dispatchers.Default) {
        settingsRepository.setHuggingFaceToken(token)
        _clearResult.value = if (token.isNullOrBlank()) {
            context.getString(R.string.settings_token_removed)
        } else {
            context.getString(R.string.settings_token_saved)
        }
    }

    fun clearChatHistory() = viewModelScope.launch(Dispatchers.Default) {
        chatRepository.clearAllChats()
        _clearResult.value = context.getString(R.string.settings_chat_history_cleared)
    }

    fun consumeClearResult() { _clearResult.value = null }

    // ---- Model Import ----

    /** Import a .litertlm file from a user-picked document URI. */
    fun importModel(sourceUri: Uri) {
        _isImporting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = modelRepository.importModel(sourceUri)) {
                    is AppResult.Success -> _clearResult.value =
                        context.getString(R.string.settings_model_imported, result.data.modelId)
                    is AppResult.Error -> _clearResult.value =
                        context.getString(R.string.settings_import_failed, result.error.message ?: "")
                }
            } finally {
                _isImporting.value = false
            }
        }
    }
}
