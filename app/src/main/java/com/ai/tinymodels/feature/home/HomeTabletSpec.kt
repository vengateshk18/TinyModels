package com.ai.tinymodels.feature.home

import com.ai.tinymodels.core.ui.components.WindowSize
import com.ai.tinymodels.feature.home.model.UsageStats

/**
 * Pure specification rules for the tablet home layouts (unit-testable,
 * covered by HomeTabletSpecTest). HomeScreen consumes these instead of
 * encoding the numbers inline so the stat grid and tile contents stay pinned
 * as the layout evolves.
 */
object HomeTabletSpec {

    /**
     * Stat tiles per row for the "Your usage" grid: three on expanded windows
     * (the tiles stay readable at a comfortable width), two on medium windows
     * where three columns would be too cramped. Compact windows never show
     * the tablet two-pane layout, but the phone grid uses exactly three.
     */
    fun usageTilesPerRow(windowSize: WindowSize): Int =
        if (windowSize == WindowSize.Expanded) 3 else 2

    /**
     * The six dashboard usage tiles in display order, as value/label pairs.
     * Pinned here so tile contents cannot be silently reordered or dropped
     * by a layout refactor.
     */
    fun usageStatTiles(usage: UsageStats): List<Pair<String, String>> = listOf(
        "${usage.totalChats}" to "chats",
        "${usage.totalTokens}" to "tokens",
        "${usage.modelsTried}" to "models tried",
        "${usage.totalMessages}" to "messages",
        "${usage.chatsThisWeek}" to "chats this week",
        "${usage.compactionsRun}" to "compactions"
    )
}
