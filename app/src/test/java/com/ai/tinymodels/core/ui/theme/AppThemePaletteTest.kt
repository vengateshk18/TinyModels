package com.ai.tinymodels.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ai.tinymodels.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class AppThemePaletteTest {

    @Test
    fun `default theme uses existing light and dark schemes`() {
        assertEquals(lightScheme, colorSchemeFor(AppTheme.DEFAULT, darkTheme = false))
        assertEquals(darkScheme, colorSchemeFor(AppTheme.DEFAULT, darkTheme = true))
    }

    @Test
    fun `non-default themes return distinct schemes`() {
        val githubLight = colorSchemeFor(AppTheme.GITHUB, darkTheme = false)
        val githubDark = colorSchemeFor(AppTheme.GITHUB, darkTheme = true)
        assertNotEquals(githubLight, githubDark)
        assertNotEquals(githubLight, lightScheme)
    }

    @Test
    fun `every theme provides light and dark schemes`() {
        AppTheme.entries.forEach { theme ->
            val light = colorSchemeFor(theme, darkTheme = false)
            val dark = colorSchemeFor(theme, darkTheme = true)
            assertNotEquals("$theme light/dark should differ", light, dark)
        }
    }

    @Test
    fun `custom theme accent roles are visually distinct`() {
        AppTheme.entries.filter { it != AppTheme.DEFAULT }.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val scheme = colorSchemeFor(theme, dark)
                val mode = if (dark) "dark" else "light"
                assertDistinct("$theme $mode primary/secondary", scheme.primary, scheme.secondary)
                assertDistinct("$theme $mode primary/tertiary", scheme.primary, scheme.tertiary)
                assertDistinct("$theme $mode secondary/tertiary", scheme.secondary, scheme.tertiary)
            }
        }
    }

    @Test
    fun `custom theme containers are tinted and role distinct`() {
        AppTheme.entries.filter { it != AppTheme.DEFAULT }.forEach { theme ->
            listOf(false, true).forEach { dark ->
                val scheme = colorSchemeFor(theme, dark)
                val mode = if (dark) "dark" else "light"
                assertDistinct("$theme $mode primary/secondary container", scheme.primaryContainer, scheme.secondaryContainer, minDistance = 12.0)
                assertDistinct("$theme $mode primary/tertiary container", scheme.primaryContainer, scheme.tertiaryContainer, minDistance = 12.0)
                assertDistinct("$theme $mode secondary/tertiary container", scheme.secondaryContainer, scheme.tertiaryContainer, minDistance = 12.0)
                // Containers should read as tinted roles, not just the card surface.
                assertDistinct("$theme $mode secondaryContainer/surface", scheme.secondaryContainer, scheme.surface, minDistance = 10.0)
                assertDistinct("$theme $mode tertiaryContainer/surface", scheme.tertiaryContainer, scheme.surface, minDistance = 10.0)
                // On-container text should follow its container role.
                assertDistinct("$theme $mode onSecondary/onTertiary container", scheme.onSecondaryContainer, scheme.onTertiaryContainer, minDistance = 10.0)
            }
        }
    }

    private fun assertDistinct(label: String, a: Color, b: Color, minDistance: Double = 24.0) {
        val d = distance(a, b)
        assertTrue("$label too similar (distance=$d, a=$a, b=$b)", d >= minDistance)
    }

    private fun distance(a: Color, b: Color): Double {
        val dr = ((a.red - b.red) * 255f).toDouble()
        val dg = ((a.green - b.green) * 255f).toDouble()
        val db = ((a.blue - b.blue) * 255f).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }
}
