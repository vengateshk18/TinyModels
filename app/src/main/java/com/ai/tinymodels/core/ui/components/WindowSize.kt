package com.ai.tinymodels.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Coarse window-size bucket used to drive adaptive (tablet / foldable) layouts.
 *
 * Buckets follow the Material 3 window-size classes, but are derived from
 * [LocalConfiguration] so no extra dependency is required:
 *   - [Compact]   < 600 dp  (phones in portrait)
 *   - [Medium]    600–839 dp (tablets in portrait, large phones / foldables)
 *   - [Expanded]  >= 840 dp  (tablets in landscape)
 */
enum class WindowSize { Compact, Medium, Expanded }

/**
 * Returns the current [WindowSize] based on the available screen width.
 *
 * Recomputes on configuration changes (rotation, fold, multi-window) because
 * [LocalConfiguration] updates automatically.
 */
@Composable
fun rememberWindowSize(): WindowSize {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> WindowSize.Compact
        widthDp < 840 -> WindowSize.Medium
        else -> WindowSize.Expanded
    }
}

/** Convenience: true when running on a tablet-class (medium or expanded) screen. */
@Composable
fun rememberIsTablet(): Boolean = rememberWindowSize() != WindowSize.Compact