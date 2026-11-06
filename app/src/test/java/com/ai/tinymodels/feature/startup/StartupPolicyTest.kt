package com.ai.tinymodels.feature.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Startup policy: which screen the very first frame should show, and how long
 * the splash may stay up while Home is still assembling its data.
 */
class StartupPolicyTest {

    @Test
    fun `first launch opens the intro`() {
        assertEquals(StartupDestination.INTRO, startupDestination(onboardingCompleted = false))
    }

    @Test
    fun `returning users go straight home`() {
        assertEquals(StartupDestination.HOME, startupDestination(onboardingCompleted = true))
    }

    @Test
    fun `splash stays while home data is loading`() {
        assertTrue(shouldShowSplash(homeReady = false, introComplete = true, elapsedMillis = 0L))
        assertTrue(shouldShowSplash(homeReady = false, introComplete = true, elapsedMillis = 2_500L))
    }

    @Test
    fun `splash stays for the minimum even when home is instant`() {
        assertTrue(shouldShowSplash(homeReady = true, introComplete = true, elapsedMillis = 0L))
        assertTrue(shouldShowSplash(homeReady = true, introComplete = true, elapsedMillis = MIN_SPLASH_MILLIS - 1))
    }

    @Test
    fun `splash leaves as soon as the minimum has elapsed and home is ready`() {
        assertFalse(shouldShowSplash(homeReady = true, introComplete = true, elapsedMillis = MIN_SPLASH_MILLIS))
        assertFalse(shouldShowSplash(homeReady = true, introComplete = true, elapsedMillis = 1_800L))
    }

    @Test
    fun `splash never traps the user when home is slow`() {
        assertTrue(shouldShowSplash(homeReady = false, introComplete = true, elapsedMillis = MAX_SPLASH_MILLIS - 1))
        assertFalse(shouldShowSplash(homeReady = false, introComplete = true, elapsedMillis = MAX_SPLASH_MILLIS))
    }

    @Test
    fun `the entrance animation is never cut short, even when home is instant`() {
        assertTrue(shouldShowSplash(homeReady = true, introComplete = false, elapsedMillis = 0L))
        assertTrue(shouldShowSplash(homeReady = true, introComplete = false, elapsedMillis = 700L))
        assertTrue(shouldShowSplash(homeReady = true, introComplete = false, elapsedMillis = MIN_SPLASH_MILLIS))
    }

    @Test
    fun `the hard cap still applies if the animation never reports back`() {
        assertFalse(shouldShowSplash(homeReady = true, introComplete = false, elapsedMillis = MAX_SPLASH_MILLIS))
    }
}
