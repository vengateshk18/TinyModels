package com.ai.tinymodels.domain.model

import androidx.annotation.StringRes
import com.ai.tinymodels.R

/**
 * Languages the app ships translations for.
 *
 * [tag] is the BCP-47 language tag used to resolve `res/values-<tag>` resources
 * (empty for [SYSTEM], meaning "follow the device language").
 */
enum class AppLanguage(val tag: String, @StringRes val labelRes: Int) {
    /** Follow the system/device language. */
    SYSTEM("", R.string.language_system),
    ENGLISH("en", R.string.language_english),
    SPANISH("es", R.string.language_spanish),
    TAMIL("ta", R.string.language_tamil),
    HINDI("hi", R.string.language_hindi);

    companion object {
        /** Resolves a stored tag back to a language; unknown/blank -> [SYSTEM]. */
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
