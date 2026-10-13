package com.example.tinymodels.feature.home.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recommendations based on current available RAM. These pin the rules so
 * the UI cannot silently give wrong advice.
 */
class RamRecommendationsTest {

    @Test fun `high available RAM suggests larger models`() {
        val recs = computeRecommendations(6000, 8000)
        assertTrue(recs.any { it.contains("3B") || it.contains("7B") })
    }

    @Test fun `moderate available RAM suggests smaller models`() {
        val recs = computeRecommendations(2500, 8000)
        assertTrue(recs.any { it.contains("1B") || it.contains("3B") })
    }

    @Test fun `low available RAM warns to close apps`() {
        val recs = computeRecommendations(500, 4000)
        assertTrue(recs.any { it.contains("Close other apps", ignoreCase = true) })
    }

    @Test fun `critical available RAM blocks all models`() {
        val recs = computeRecommendations(100, 4000)
        assertTrue(recs.any { it.contains("Critical", ignoreCase = true) })
    }

    @Test fun `recommendations include thermal caveat when throttling`() {
        val recs = computeRecommendations(6000, 8000, thermalState = 3)
        assertTrue(recs.any { it.contains("thermal", ignoreCase = true) || it.contains("hot", ignoreCase = true) })
    }

    @Test fun `recommendations include battery caveat when low`() {
        val recs = computeRecommendations(6000, 8000, batteryLevel = 10)
        assertTrue(recs.any { it.contains("battery", ignoreCase = true) })
    }

    @Test fun `no thermal caveat when thermal state normal`() {
        val recs = computeRecommendations(6000, 8000, thermalState = 0)
        assertTrue(recs.none { it.contains("thermal", ignoreCase = true) })
    }
}
