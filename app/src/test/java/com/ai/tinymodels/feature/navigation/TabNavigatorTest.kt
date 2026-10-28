package com.ai.tinymodels.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract for bottom-navigation tab taps: tapping the tab you are already on
 * must be a no-op (no re-navigation, no state churn), while tapping a sibling
 * tab switches with state save/restore.
 */
class TabNavigatorTest {

    @Test fun `tapping the currently active tab does nothing`() {
        assertEquals(false, TabNavigator.shouldNavigate(Routes.HOME, Routes.HOME))
        assertEquals(false, TabNavigator.shouldNavigate(Routes.CHAT, Routes.CHAT))
        assertEquals(false, TabNavigator.shouldNavigate(Routes.MODELS, Routes.MODELS))
        assertEquals(false, TabNavigator.shouldNavigate(Routes.SETTINGS, Routes.SETTINGS))
    }

    @Test fun `tapping a sibling tab navigates`() {
        assertEquals(true, TabNavigator.shouldNavigate(Routes.HOME, Routes.CHAT))
        assertEquals(true, TabNavigator.shouldNavigate(Routes.CHAT, Routes.HOME))
        assertEquals(true, TabNavigator.shouldNavigate(Routes.MODELS, Routes.SETTINGS))
    }

    @Test fun `unknown or missing current route still navigates`() {
        assertEquals(true, TabNavigator.shouldNavigate(null, Routes.HOME))
        assertEquals(true, TabNavigator.shouldNavigate("model_details/x", Routes.HOME))
    }
}
