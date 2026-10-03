package com.example.tinymodels.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Snackbar notifications across the app.
 * Provides a centralized way to show toast/snackbar messages.
 */
@Singleton
class SnackbarManager @Inject constructor() {
    
    private val _snackbar = MutableStateFlow<SnackbarMessage?>(null)
    val snackbar: StateFlow<SnackbarMessage?> = _snackbar.asStateFlow()
    
    /**
     * Show a snackbar message with the specified duration.
     * Automatically clears the message after the duration.
     */
    fun showMessage(
        message: String, 
        duration: SnackbarDuration = SnackbarDuration.SHORT
    ) {
        _snackbar.value = SnackbarMessage(message, duration)
        
        // Auto-clear after duration using the provided coroutine scope
        kotlinx.coroutines.GlobalScope.launch {
            kotlinx.coroutines.delay(duration.toMillis())
            _snackbar.value = null
        }
    }
    
    /**
     * Clear any current snackbar message immediately.
     */
    fun clearMessage() {
        _snackbar.value = null
    }
    
    enum class SnackbarDuration(val millis: Long) {
        SHORT(2000),
        LONG(4000)
    }
    
    data class SnackbarMessage(
        val message: String,
        val duration: SnackbarDuration
    )
}

/**
 * Extension function to convert duration to milliseconds.
 */
private fun SnackbarManager.SnackbarDuration.toMillis(): Long = this.millis
