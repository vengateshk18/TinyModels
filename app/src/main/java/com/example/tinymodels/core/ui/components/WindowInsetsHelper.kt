package com.example.tinymodels.core.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// ---------------------------------------------------------------------------
// Edge-to-Edge inset strategy (canonical approach as of this codebase)
// ---------------------------------------------------------------------------
//
// Every Scaffold passes `contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)`
// (referred to as `safeDrawing` at call sites).  This means Scaffold's own
// padding lambda already includes:
//   • Top    – status bar height  (via TopAppBar)
//   • Bottom – navigation bar height (via BottomBar / FAB slot)
//   • Left   – display cutout (camera notch) in landscape
//   • Right  – navigation bar in landscape
//
// Screens should therefore just call `.padding(paddingValues)` from the
// Scaffold lambda — no extra horizontal padding needed.
//
// The helpers below are kept for use in:
//   • Bottom-sheet modifiers (sheets draw outside the normal Scaffold flow)
//   • Sticky bottom bars that sit inside a Scaffold with contentWindowInsets = 0
// ---------------------------------------------------------------------------

/**
 * The canonical `safeDrawing` inset set: `systemBars ∪ displayCutout`.
 *
 * Pass this as `contentWindowInsets` to every [Scaffold]:
 * ```kotlin
 * Scaffold(contentWindowInsets = safeDrawingInsets) { … }
 * ```
 * The resulting `paddingValues` from the lambda will already include all four
 * sides (status bar, nav bar, left/right cutouts).
 */
val safeDrawingInsets: WindowInsets
    @Composable get() = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * Status-bar top padding for [ModalBottomSheet] that can expand to full height.
 *
 * Apply to the **sheet `modifier`** (not the content column) so the sheet
 * surface itself stays below the status bar when fully expanded:
 * ```kotlin
 * ModalBottomSheet(
 *     modifier = Modifier.bottomSheetTopSafePadding()
 * ) { … }
 * ```
 */
@Composable
fun Modifier.bottomSheetTopSafePadding(): Modifier =
    this.windowInsetsPadding(WindowInsets.statusBars)

/**
 * Safe-area padding for content **inside** a [ModalBottomSheet]:
 *  - Horizontal: `systemBars ∪ displayCutout` — protects camera notch in landscape
 *  - Bottom: navigation bar — clears gesture handle / button bar
 *
 * Apply to the root `Column` inside the sheet:
 * ```kotlin
 * ModalBottomSheet(modifier = Modifier.bottomSheetTopSafePadding()) {
 *     Column(modifier = Modifier.bottomSheetSafePadding()) { … }
 * }
 * ```
 */
@Composable
fun Modifier.bottomSheetSafePadding(): Modifier =
    this
        .windowInsetsPadding(
            WindowInsets.systemBars
                .union(WindowInsets.displayCutout)
                .only(WindowInsetsSides.Horizontal)
        )
        .navigationBarsPadding()
