package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.domain.model.SamplerSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Maps the app's [SamplerSettings] onto MediaPipe session sampling parameters.
 * The one hard constraint: the session's topK must never exceed the value the
 * model was loaded with (the native side rejects larger topK).
 */
class MediaPipeSamplerMapperTest {

    @Test fun `defaults map straight through`() {
        val values = MediaPipeSamplerMapper.map(SamplerSettings(temperature = 0.7, topK = 40, topP = 0.95))
        assertEquals(40, values.topK)
        assertEquals(0.95f, values.topP, 0.0001f)
        assertEquals(0.7f, values.temperature, 0.0001f)
    }

    @Test fun `topK clamps to the loadable maximum`() {
        assertEquals(
            MediaPipeSamplerMapper.MAX_TOP_K,
            MediaPipeSamplerMapper.map(SamplerSettings(topK = 500)).topK
        )
    }

    @Test fun `topK clamps to at least one`() {
        assertEquals(1, MediaPipeSamplerMapper.map(SamplerSettings(topK = 0)).topK)
        assertEquals(1, MediaPipeSamplerMapper.map(SamplerSettings(topK = -3)).topK)
    }

    @Test fun `greedy decode maps to temperature zero`() {
        val values = MediaPipeSamplerMapper.map(SamplerSettings(temperature = 0.0))
        assertEquals(0f, values.temperature, 0.0001f)
    }
}
