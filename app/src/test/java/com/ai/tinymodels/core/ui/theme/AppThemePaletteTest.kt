package com.ai.tinymodels.core.ui.theme

import com.ai.tinymodels.domain.model.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

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
}
