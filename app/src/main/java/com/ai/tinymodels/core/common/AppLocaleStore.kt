package com.ai.tinymodels.core.common

import android.content.Context
import com.ai.tinymodels.domain.model.AppLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the user's app-language choice.
 *
 * Kept in [android.content.SharedPreferences] (not DataStore) on purpose: the
 * chosen locale has to be readable **synchronously** while the activity's base
 * context is being created — before any coroutine can run.
 */
@Singleton
class AppLocaleStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _language = MutableStateFlow(AppLanguage.fromTag(prefs.getString(KEY_TAG, "")))

    /** The language the user picked (or [AppLanguage.SYSTEM]). */
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /** Current selection, readable without suspending. */
    fun current(): AppLanguage = _language.value

    /** Persists [language]; the caller is responsible for applying it. */
    fun set(language: AppLanguage) {
        prefs.edit().putString(KEY_TAG, language.tag).apply()
        _language.value = language
    }

    companion object {
        const val PREFS_NAME = "app_locale"
        const val KEY_TAG = "language_tag"

        /** Synchronous read of the stored tag — safe during `attachBaseContext`. */
        fun currentTag(context: Context): String = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TAG, "")
            .orEmpty()
    }
}
