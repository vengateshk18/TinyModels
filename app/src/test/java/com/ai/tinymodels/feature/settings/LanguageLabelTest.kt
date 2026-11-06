package com.ai.tinymodels.feature.settings

import com.ai.tinymodels.core.common.fakeStringContext
import com.ai.tinymodels.domain.model.AppLanguage
import com.ai.tinymodels.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The language picker row: native name first, English hint second. */
class LanguageLabelTest {

    private val ctx = fakeStringContext()

    @Test
    fun `language with an english hint formats native then parenthesised english`() {
        assertEquals(
            "${R.string.language_option_format}(${R.string.language_native_tamil},${R.string.language_name_tamil})",
            AppLanguage.TAMIL.displayLabel(ctx)
        )
    }

    @Test
    fun `language whose native name is already english renders bare`() {
        // English has no parenthetical hint; the label is the native name only.
        assertEquals(
            R.string.language_english.toString(),
            AppLanguage.ENGLISH.displayLabel(ctx)
        )
        assertEquals(
            R.string.language_system.toString(),
            AppLanguage.SYSTEM.displayLabel(ctx)
        )
    }

    @Test
    fun `fromTag round-trips known tags and falls back to system`() {
        for (language in AppLanguage.entries) {
            if (language.tag.isNotEmpty()) {
                assertEquals(language, AppLanguage.fromTag(language.tag))
            }
        }
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag("xx"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
    }
}
