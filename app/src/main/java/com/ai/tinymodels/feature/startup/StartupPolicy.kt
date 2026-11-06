package com.ai.tinymodels.feature.startup

/**
 * Which screen the first composed frame must be.
 *
 * The decision is made from settings read *synchronously before the first
 * composition* (see `MainActivity`), so there is no frame where the wrong
 * screen flashes before switching.
 */
enum class StartupDestination { INTRO, HOME }

fun startupDestination(onboardingCompleted: Boolean): StartupDestination =
    if (onboardingCompleted) StartupDestination.HOME else StartupDestination.INTRO

/**
 * How long the splash overlay may cover the app.
 *
 * The splash exists to (a) hide Home's loading spinner and (b) give the brand
 * a beat. It is not a delay for its own sake: it disappears at the first frame
 * where Home is settled *and* the minimum beat has passed, and it never keeps
 * the user waiting past [MAX_SPLASH_MILLIS] even if Home is unusually slow.
 */
fun shouldShowSplash(homeReady: Boolean, elapsedMillis: Long): Boolean {
    if (elapsedMillis >= MAX_SPLASH_MILLIS) return false
    if (!homeReady) return true
    return elapsedMillis < MIN_SPLASH_MILLIS
}

/** Minimum time the splash stays up, so an instant Home still gets a beat. */
const val MIN_SPLASH_MILLIS: Long = 1_100L

/** Upper bound: never trap the user behind the splash. */
const val MAX_SPLASH_MILLIS: Long = 3_000L
