package com.ai.tinymodels.core.common

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import com.ai.tinymodels.domain.model.AppLanguage
import java.util.Locale

/**
 * Applies the user's chosen app language.
 *
 * - **API 33+**: the platform [LocaleManager] owns the per-app language, so the
 *   system settings screen shows it and the system recreates the activity for
 *   us. The app's own configuration override is deliberately skipped there so
 *   it can never fight the user's system-level choice.
 * - **API < 33**: there is no per-app language API, so the chosen locale is
 *   applied by overriding the base context configuration (see [wrapContext]).
 */
object AppLocale {

    /**
     * Wraps [base] with the configuration of [tag] so resources resolve against
     * the chosen language. A blank tag leaves the context untouched.
     */
    fun wrapContext(base: Context, tag: String): Context {
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Applies [language] to the running app.
     *
     * On API 33+ the platform takes over (and recreates the activity itself);
     * on older versions the caller must recreate the activity for the new
     * configuration to take effect.
     */
    fun apply(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = activity.getSystemService(LocaleManager::class.java)
            val locales = if (language == AppLanguage.SYSTEM) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(language.tag)
            }
            if (manager.applicationLocales != locales) {
                // The platform recreates the activity when the locales change.
                manager.applicationLocales = locales
            } else {
                activity.recreate()
            }
        } else {
            activity.recreate()
        }
    }

    /** The language currently in effect (API 33+ reads it from the platform). */
    fun currentLanguage(context: Context, fallback: AppLanguage): AppLanguage {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val tag = manager.applicationLocales[0]?.toLanguageTag().orEmpty()
            if (tag.isNotBlank()) return AppLanguage.fromTag(tag)
        }
        return fallback
    }
}
