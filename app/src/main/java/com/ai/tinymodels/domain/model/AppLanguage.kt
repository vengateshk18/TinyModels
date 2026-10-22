package com.ai.tinymodels.domain.model

import androidx.annotation.StringRes
import com.ai.tinymodels.R

/**
 * Languages the app ships translations for.
 *
 * [tag] is the BCP-47 language tag used to resolve `res/values-<tag>` resources
 * (empty for [SYSTEM], meaning "follow the device language").
 */
enum class AppLanguage(
    val tag: String,
    /**
     * The language's name written in its own script — never translated, so it
     * always reads as "Español" / "தமிழ்" / "हिन्दी".
     */
    @StringRes val nativeLabelRes: Int,
    /**
     * The language's name in English, shown in parentheses after the native name
     * so the option stays readable for users who cannot read that script.
     *
     * `null` when the native name already is the English name (English itself,
     * and the language-agnostic "system default" entry).
     */
    @StringRes val englishLabelRes: Int? = null
) {
    /** Follow the system/device language. */
    SYSTEM("", R.string.language_system),
    ENGLISH("en", R.string.language_english),
    SPANISH("es", R.string.language_native_spanish, R.string.language_name_spanish),
    TAMIL("ta", R.string.language_native_tamil, R.string.language_name_tamil),
    HINDI("hi", R.string.language_native_hindi, R.string.language_name_hindi);

    companion object {
        /** Resolves a stored tag back to a language; unknown/blank -> [SYSTEM]. */
        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
