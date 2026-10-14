package com.ai.tinymodels.feature.home.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Thermal and battery label mappings for the DeviceInsights card.
 */
class DeviceInsightsTest {

    @Test fun `thermal label maps correctly`() {
        assertEquals("Normal", thermalLabel(0))
        assertEquals("Light", thermalLabel(1))
        assertEquals("Moderate", thermalLabel(2))
        assertEquals("Severe", thermalLabel(3))
        assertEquals("Critical", thermalLabel(4))
        assertEquals("Emergency", thermalLabel(5))
        assertEquals("Shutdown", thermalLabel(6))
        assertEquals("Unknown", thermalLabel(99))
    }

    @Test fun `battery status label maps correctly`() {
        assertEquals("Unknown", batteryStatusLabel(0))
        assertEquals("Charging", batteryStatusLabel(2))
        assertEquals("Discharging", batteryStatusLabel(3))
        assertEquals("Full", batteryStatusLabel(5))
        assertEquals("Not charging", batteryStatusLabel(4))
    }

    @Test fun `battery level percentage clamps correctly`() {
        assertEquals(0, batteryLevelClamped(-5))
        assertEquals(50, batteryLevelClamped(50))
        assertEquals(100, batteryLevelClamped(150))
        assertEquals(-1, batteryLevelClamped(-1)) // unknown
    }
}
