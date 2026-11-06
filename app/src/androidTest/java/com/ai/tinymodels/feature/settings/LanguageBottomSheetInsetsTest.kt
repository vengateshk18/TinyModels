package com.ai.tinymodels.feature.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.tinymodels.R
import com.ai.tinymodels.domain.model.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * The language picker is a [androidx.compose.material3.ModalBottomSheet] whose
 * built-in inset handling is switched off (`contentWindowInsets = { WindowInsets(0) }`),
 * so the sheet is responsible for keeping its content clear of the navigation
 * bar and of the display cutout.
 *
 * These tests assert exactly that — the sheet content must never sit under the
 * gesture pill / button bar, and must never run into the camera notch.
 */
@RunWith(AndroidJUnit4::class)
class LanguageBottomSheetInsetsTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sheetContentStaysClearOfNavigationBar() {
        composeRule.setContent {
            LanguageBottomSheet(
                selected = AppLanguage.SYSTEM,
                onSelect = {},
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        assertSheetClearsSystemInsets()
    }

    @Test
    fun sheetContentStaysClearOfNavigationBarAndCutoutInLandscape() {
        // Rotate the host activity so the navigation bar and the display cutout
        // end up on the sides — the case where a sheet is easily clipped.
        composeRule.activity.requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.activity.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
        }

        composeRule.setContent {
            LanguageBottomSheet(
                selected = AppLanguage.SYSTEM,
                onSelect = {},
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        assertSheetClearsSystemInsets()
    }

    /**
     * Every option shows the language's name in its own script plus its English
     * name in parentheses, e.g. "தமிழ் (Tamil)" — so the option is readable
     * even for users who cannot read that script.
     */
    @Test
    fun everyLanguageOptionShowsNativeNameWithEnglishHint() {
        composeRule.setContent {
            LanguageBottomSheet(
                selected = AppLanguage.SYSTEM,
                onSelect = {},
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        val activity = composeRule.activity
        AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.forEach { language ->
            val native = activity.getString(language.nativeLabelRes)
            val english = language.englishLabelRes?.let { activity.getString(it) }
            val expected = if (english == null) {
                native
            } else {
                activity.getString(R.string.language_option_format, native, english)
            }
            composeRule.onNodeWithText(expected).assertIsDisplayed()
        }

        // Spot-check the exact rendering: native script first, English hint after.
        composeRule.onNodeWithText("Español (Spanish)").assertIsDisplayed()

        // The checks above derive their expectation from the same resources the
        // UI reads, so they cannot catch a wrong script (e.g. Tamil escaped with
        // Devanagari codepoints). These literals are the independent source of truth.
        assertEquals("Español", activity.getString(R.string.language_native_spanish))
        assertEquals("தமிழ்", activity.getString(R.string.language_native_tamil))
        assertEquals("हिन्दी", activity.getString(R.string.language_native_hindi))
    }

    /**
     * The sheet content must stay clear of the union of the system bars and the
     * display cutout on every side that has an inset.
     */
    private fun assertSheetClearsSystemInsets() {
        val activity = composeRule.activity
        val insets = activity.window.decorView.rootWindowInsets
            ?: error("window insets unavailable")
        val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
        val cutout = insets.getInsets(WindowInsets.Type.displayCutout())

        // The union of system bars and the display cutout — exactly what the
        // sheet content has to stay clear of.
        val leftInset = max(systemBars.left, cutout.left)
        val rightInset = max(systemBars.right, cutout.right)
        val bottomInset = max(systemBars.bottom, cutout.bottom)

        val metrics = activity.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        val content = composeRule.onNodeWithTag(LANGUAGE_SHEET_CONTENT_TAG)
            .fetchSemanticsNode()
            .boundsInRoot

        val firstLabel = AppLanguage.entries.first().displayLabel(activity)
        val lastLabel = AppLanguage.entries.last().displayLabel(activity)
        val firstText = composeRule.onNodeWithText(firstLabel).fetchSemanticsNode().boundsInRoot
        val lastText = composeRule.onNodeWithText(lastLabel).fetchSemanticsNode().boundsInRoot

        println(
            "DIAG screen=${screenWidth}x$screenHeight content=$content firstText=$firstText " +
                "lastText=$lastText insets(l=$leftInset r=$rightInset b=$bottomInset) " +
                "systemBars=$systemBars cutout=$cutout"
        )

        // 1. Bottom — the last option must not disappear under the navigation bar.
        assertTrue(
            "last language option ends at ${lastText.bottom}, inside the navigation bar " +
                "(inset $bottomInset, screen height $screenHeight)",
            lastText.bottom <= screenHeight - bottomInset
        )

        // 2. Horizontal — in landscape the cutout / nav bar sits on one side.
        assertTrue(
            "sheet content starts at ${firstText.left}, inside the left inset $leftInset",
            firstText.left >= leftInset
        )
        assertTrue(
            "sheet content ends at ${lastText.right}, inside the right inset $rightInset",
            lastText.right <= screenWidth - rightInset
        )
    }
}
