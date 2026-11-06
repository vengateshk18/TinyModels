package com.ai.tinymodels.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry contract for [TinyModelsLogo]. The launcher artwork fills only part
 * of its adaptive-icon canvas, so the composable upscales it — but never to the
 * point where the logo touches the box edge: a tile pressed against the edge
 * looks clipped and harsh.
 */
class BrandLogoGeometryTest {

    @Test
    fun `the artwork is upscaled so it fills most of the box`() {
        assertEquals(
            LOGO_VISIBLE_FRACTION,
            LOGO_CANVAS_ART_FRACTION * logoUpscale(),
            0.0001f
        )
    }

    @Test
    fun `there is real padding on every side`() {
        // At least 5% of the box per side, so the rounded tile edge is clearly
        // inside the bounds instead of being clipped by them.
        assertTrue("visible fraction is too large", LOGO_VISIBLE_FRACTION <= 0.90f)
        assertTrue("logo would be too small", LOGO_VISIBLE_FRACTION >= 0.78f)
    }

    @Test
    fun `the upscale never lets the artwork exceed the box`() {
        assertTrue(LOGO_CANVAS_ART_FRACTION * logoUpscale() <= 1f)
    }

    @Test
    fun `the logo is presented as a rounded card, never a sharp square`() {
        // A card corner has to be clearly visible, not a token 2–3%.
        assertTrue("corner radius is too subtle", LOGO_CORNER_FRACTION >= 0.20f)
        // …but not so round that it turns into a circle / pill.
        assertTrue("corner radius is too round", LOGO_CORNER_FRACTION <= 0.28f)
    }
}
