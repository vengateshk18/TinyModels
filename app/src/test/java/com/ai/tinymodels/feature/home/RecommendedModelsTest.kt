package com.ai.tinymodels.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for [RecommendedModels.pickForDevice]: RAM tiers and the
 * storage guard that drops a tier when the model would not fit.
 */
class RecommendedModelsTest {

    private val huge = Long.MAX_VALUE / 2

    @Test
    fun `high ram picks high tier`() {
        assertEquals(RecommendedModels.HIGH, RecommendedModels.pickForDevice(12_000, huge))
        assertEquals(RecommendedModels.HIGH, RecommendedModels.pickForDevice(6_000, huge))
    }

    @Test
    fun `medium ram picks medium tier`() {
        assertEquals(RecommendedModels.MEDIUM, RecommendedModels.pickForDevice(5_999, huge))
        assertEquals(RecommendedModels.MEDIUM, RecommendedModels.pickForDevice(2_500, huge))
    }

    @Test
    fun `low ram picks small tier`() {
        assertEquals(RecommendedModels.SMALL, RecommendedModels.pickForDevice(2_499, huge))
        assertEquals(RecommendedModels.SMALL, RecommendedModels.pickForDevice(0, huge))
    }

    @Test
    fun `storage guard drops one tier when model does not fit`() {
        val justUnder = (RecommendedModels.MEDIUM.sizeBytes * 1.1).toLong() - 1
        assertEquals(
            RecommendedModels.SMALL,
            RecommendedModels.pickForDevice(8_000, justUnder)
        )
    }

    @Test
    fun `storage guard keeps tier when comfortably fitting`() {
        val fits = (RecommendedModels.MEDIUM.sizeBytes * 1.1).toLong() + 1_000_000L
        assertEquals(
            RecommendedModels.MEDIUM,
            RecommendedModels.pickForDevice(8_000, fits)
        )
    }

    @Test
    fun `tiers are ordered small to high`() {
        assertEquals(
            listOf(RecommendedModels.SMALL, RecommendedModels.MEDIUM, RecommendedModels.HIGH),
            RecommendedModels.all
        )
    }
}
