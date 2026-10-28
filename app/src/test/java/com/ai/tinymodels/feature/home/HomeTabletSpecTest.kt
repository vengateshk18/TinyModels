package com.ai.tinymodels.feature.home

import com.ai.tinymodels.core.ui.components.WindowSize
import com.ai.tinymodels.feature.home.model.UsageStats
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the tablet home spec ([HomeTabletSpec]): the stat-tile grid density
 * per window size, and the exact dashboard tile contents and order. Pure
 * logic only — the composables are exercised by UI tests (same pattern as
 * RamUsageChartTest).
 */
class HomeTabletSpecTest {

    private val usage = UsageStats(
        totalChats = 12,
        totalTokens = 45_000L,
        modelsTried = 3,
        totalMessages = 210,
        chatsThisWeek = 4,
        compactionsRun = 2,
        webSearchChats = 1,
        favoriteModel = "google/gemma-2b-it"
    )

    @Test fun `expanded windows pack three stat tiles per row`() {
        assertEquals(3, HomeTabletSpec.usageTilesPerRow(WindowSize.Expanded))
    }

    @Test fun `medium and compact windows pack two tiles per row`() {
        // Three columns on a medium window would be too cramped; compact
        // windows never run the tablet two-pane layout but keep grid parity.
        assertEquals(2, HomeTabletSpec.usageTilesPerRow(WindowSize.Medium))
        assertEquals(2, HomeTabletSpec.usageTilesPerRow(WindowSize.Compact))
    }

    @Test fun `stat tiles carry the six metrics in dashboard order`() {
        assertEquals(
            listOf(
                "12" to "chats",
                "45000" to "tokens",
                "3" to "models tried",
                "210" to "messages",
                "4" to "chats this week",
                "2" to "compactions"
            ),
            HomeTabletSpec.usageStatTiles(usage)
        )
    }

    @Test fun `stat tiles chunk evenly at both grid densities`() {
        val tiles = HomeTabletSpec.usageStatTiles(usage)

        val expandedRows = tiles.chunked(HomeTabletSpec.usageTilesPerRow(WindowSize.Expanded))
        assertEquals(listOf(3, 3), expandedRows.map { it.size })

        val mediumRows = tiles.chunked(HomeTabletSpec.usageTilesPerRow(WindowSize.Medium))
        assertEquals(listOf(2, 2, 2), mediumRows.map { it.size })
    }

    @Test fun `stat tiles never lossily truncate the token counter`() {
        // Token totals are Long; the tile must carry the full value rather
        // than a rounded or abbreviated string.
        val big = usage.copy(totalTokens = Long.MAX_VALUE) // 9223372036854775807
        assertEquals(
            Long.MAX_VALUE.toString(),
            HomeTabletSpec.usageStatTiles(big)[1].first
        )
    }

    @Test fun `zeroed usage still renders all six tiles`() {
        val tiles = HomeTabletSpec.usageStatTiles(UsageStats())
        assertEquals(6, tiles.size)
        assertEquals("0" to "chats", tiles[0])
        assertEquals("0" to "compactions", tiles[5])
    }
}
