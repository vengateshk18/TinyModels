package com.ai.tinymodels.feature.navigation

/**
 * Decision logic for bottom-navigation taps, kept pure so the navigation
 * behavior can be unit tested.
 *
 * Bug being guarded: tapping the tab you are already on re-created the
 * destination (popUpTo popped the current entry before launchSingleTop could
 * dedupe it), so every repeat tap rebuilt the screen. The decision is made
 * here: a tap on the active tab never reaches the NavController.
 */
object TabNavigator {

    /**
     * True when the NavController should switch to [targetRoute].
     *
     * @param currentRoute route of the destination currently shown, or null
     * before the graph is resolved.
     */
    fun shouldNavigate(currentRoute: String?, targetRoute: String): Boolean =
        currentRoute != targetRoute
}
