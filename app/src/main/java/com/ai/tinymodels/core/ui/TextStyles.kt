package com.ai.tinymodels.core.ui

import androidx.compose.ui.text.TextStyle

/**
 * Tabular ("tnum") figures give every digit the same advance width. Live
 * download counters change several times per second; in a proportional font
 * "1" is narrower than "8", so the whole stats line shimmers and shifts even
 * though the text is identical in length.
 */
fun TextStyle.withTabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")
