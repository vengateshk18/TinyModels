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
 * The splash exists to (a) let its own entrance animation finish, (b) hide
 * Home's loading spinner and (c) give the brand a beat. It disappears at the
 * first frame where the animation has genuinely completed, Home is settled and
 * the minimum hold has passed — and it never keeps the user waiting past
 * [MAX_SPLASH_MILLIS], even if either signal never arrives.
 */
fun shouldShowSplash(
    homeReady: Boolean,
    introComplete: Boolean,
    elapsedMillis: Long
): Boolean {
    if (elapsedMillis >= MAX_SPLASH_MILLIS) return false
    // Never fade out mid-animation: a cut slogan looks like a glitch.
    if (!introComplete) return true
    if (!homeReady) return true
    return elapsedMillis < MIN_SPLASH_MILLIS
}

/**
 * Minimum time the splash stays up. The entrance sequence itself finishes at
 * roughly 0.9 s; the remainder is a calm hold so the completed wordmark and
 * slogan are actually readable before the fade.
 */
const val MIN_SPLASH_MILLIS: Long = 1_500L

/** Upper bound: never trap the user behind the splash. */
const val MAX_SPLASH_MILLIS: Long = 3_000L
