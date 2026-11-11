package com.ai.tinymodels.feature.home.components

import com.ai.tinymodels.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Thermal and battery label mappings for the DeviceInsights card.
 */
class DeviceInsightsTest {

    @Test fun `thermal label maps correctly`() {
        assertEquals(R.string.device_thermal_normal, thermalLabelRes(0))
        assertEquals(R.string.device_thermal_light, thermalLabelRes(1))
        assertEquals(R.string.device_thermal_moderate, thermalLabelRes(2))
        assertEquals(R.string.device_thermal_severe, thermalLabelRes(3))
        assertEquals(R.string.device_thermal_critical, thermalLabelRes(4))
        assertEquals(R.string.device_thermal_emergency, thermalLabelRes(5))
        assertEquals(R.string.device_thermal_shutdown, thermalLabelRes(6))
        assertEquals(R.string.device_thermal_unknown, thermalLabelRes(99))
    }

    @Test fun `battery status label maps correctly`() {
        assertEquals(R.string.device_battery_unknown, batteryStatusLabelRes(0))
        assertEquals(R.string.device_battery_charging, batteryStatusLabelRes(2))
        assertEquals(R.string.device_battery_discharging, batteryStatusLabelRes(3))
        assertEquals(R.string.device_battery_full, batteryStatusLabelRes(5))
        assertEquals(R.string.device_battery_not_charging, batteryStatusLabelRes(4))
    }

    @Test fun `battery level percentage clamps correctly`() {
        assertEquals(0, batteryLevelClamped(-5))
        assertEquals(50, batteryLevelClamped(50))
        assertEquals(100, batteryLevelClamped(150))
        assertEquals(-1, batteryLevelClamped(-1)) // unknown
    }
}
