package com.ai.tinymodels.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device capability classification from total RAM. Shared by the Home
 * dashboard and the Device Information screen.
 */
class AiCapabilityClassifierTest {

    private fun label(mb: Long) = AiCapabilityClassifier.classify(mb).first.label
    private fun params(mb: Long) = AiCapabilityClassifier.classify(mb).second

    @Test fun `low ram phones report the Low tier`() {
        // 2 GB device — the classiclow-end Android phone.
        assertEquals("Low", label(2_000))
        // 1 GB device.
        assertEquals("Low", label(1_000))
    }

    @Test fun `4 GB phones report Limited and can fit 1B models`() {
        // Limited starts at the 4 GB boundary exactly.
        assertEquals("Limited", label(4_000))
        assertEquals("1B", params(4_000))
        assertEquals("Limited", label(4_500))
    }

    @Test fun `just under the boundary still reports Low not Unknown`() {
        // A "4 GB" phone usually reports ~3.9 GB totalMem after kernel
        // reservations — it must NEVER read as Unknown.
        assertEquals("Low", label(3_900))
        assertEquals("0.5B", params(3_900))
    }

    @Test fun `8 GB phones report Good and can fit 3B models`() {
        assertEquals("Good", label(8_000))
        assertEquals("3B", params(8_000))
    }

    @Test fun `12 GB plus phones report Excellent and can fit 8B models`() {
        assertEquals("Excellent", label(12_500))
        assertEquals("8B", params(12_500))
    }

    @Test fun `unusable readings are the only Unknown case`() {
        assertEquals("Unknown", label(0))
        assertEquals("Unknown", label(-100))
    }

    @Test fun `low tier recommends small models not a dash`() {
        assertEquals("0.5B", params(2_000))
        assertEquals("0.5B", params(1_000))
    }
}
