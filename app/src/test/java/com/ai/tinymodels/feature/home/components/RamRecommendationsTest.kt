package com.ai.tinymodels.feature.home.components

import com.ai.tinymodels.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recommendations based on current available RAM. These pin the rules so
 * the UI cannot silently give wrong advice.
 */
class RamRecommendationsTest {

    @Test fun `high available RAM suggests larger models`() {
        val recs = computeRecommendations(6000, 8000)
        assertTrue(recs.any { it.textRes == R.string.ram_rec_plenty_capacity })
        assertTrue(recs.any { it.textRes == R.string.ram_rec_plenty_context })
    }

    @Test fun `moderate available RAM suggests smaller models`() {
        val recs = computeRecommendations(2500, 8000)
        assertTrue(recs.any { it.textRes == R.string.ram_rec_moderate_capacity })
    }

    @Test fun `low available RAM warns to close apps`() {
        val recs = computeRecommendations(500, 4000)
        val closeApps = recs.first { it.textRes == R.string.ram_rec_limited_close_apps }
        assertTrue(closeApps.isWarning)
        assertEquals(RamRecommendationIcon.WARNING, closeApps.icon)
    }

    @Test fun `critical available RAM blocks all models`() {
        val recs = computeRecommendations(100, 4000)
        val critical = recs.first { it.textRes == R.string.ram_rec_critical_close_apps }
        assertTrue(critical.isWarning)
        assertTrue(recs.any { it.textRes == R.string.ram_rec_critical_no_models })
    }

    @Test fun `recommendations include thermal caveat when throttling`() {
        val recs = computeRecommendations(6000, 8000, thermalState = 3)
        val thermal = recs.first { it.textRes == R.string.ram_rec_thermal_throttling }
        assertEquals(RamRecommendationIcon.THERMAL, thermal.icon)
        assertTrue(thermal.isWarning)
    }

    @Test fun `recommendations include battery caveat when low`() {
        val recs = computeRecommendations(6000, 8000, batteryLevel = 10)
        val battery = recs.first { it.textRes == R.string.ram_rec_battery_low }
        assertEquals(RamRecommendationIcon.BATTERY, battery.icon)
        assertTrue(battery.isWarning)
    }

    @Test fun `no thermal caveat when thermal state normal`() {
        val recs = computeRecommendations(6000, 8000, thermalState = 0)
        assertFalse(recs.any { it.textRes == R.string.ram_rec_thermal_throttling })
    }
}
