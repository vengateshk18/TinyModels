package com.example.tinymodels.feature.home

import org.junit.Test
import org.junit.Assert.*

class DeviceInfoStateTest {

    @Test
    fun `state holds real-time ram fields with defaults`() {
        val state = DeviceInfoState()
        assertEquals(0.0, state.ramUsagePercent, 0.001)
        assertTrue(state.ramHistory.isEmpty())
        assertEquals(0, state.thermalState)
        assertEquals(-1, state.batteryLevel)
        assertEquals(0, state.batteryStatus)
        assertEquals("", state.gpuRenderer)
    }
}