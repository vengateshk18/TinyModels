package com.ai.tinymodels.feature.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.ai.tinymodels.R
import com.ai.tinymodels.domain.model.AppLanguage

/**
 * The label shown for a language option: the language's name in its own script
 * followed by its English name in parentheses — e.g. "தமிழ் (Tamil)".
 *
 * The native name keeps the entry recognisable to the people who speak that
 * language, while the English hint makes it pickable by everyone else.
 */
fun AppLanguage.displayLabel(context: Context): String {
    val native = context.getString(nativeLabelRes)
    val english = englishLabelRes?.let { context.getString(it) }
    return if (english == null) {
        native
    } else {
        context.getString(R.string.language_option_format, native, english)
    }
}

/** Compose-friendly variant of [displayLabel]. */
@Composable
fun AppLanguage.displayLabel(): String = displayLabel(LocalContext.current)
