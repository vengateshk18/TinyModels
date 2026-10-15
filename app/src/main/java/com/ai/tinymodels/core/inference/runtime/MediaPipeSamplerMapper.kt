package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.domain.model.SamplerSettings

/**
 * Maps the app's [SamplerSettings] onto MediaPipe session sampling parameters.
 *
 * Hard constraint: the session's topK must never exceed the value the model
 * was loaded with (`LlmInferenceOptions.setMaxTopK(MAX_TOP_K)`); the native
 * side rejects larger session topK.
 */
object MediaPipeSamplerMapper {

    /** Ceiling passed to `LlmInferenceOptions.setMaxTopK`; session topK clamps to this. */
    const val MAX_TOP_K = 64

    data class Values(val topK: Int, val topP: Float, val temperature: Float)

    fun map(sampler: SamplerSettings): Values = Values(
        topK = sampler.topK.coerceIn(1, MAX_TOP_K),
        topP = sampler.topP.toFloat(),
        temperature = sampler.temperature.toFloat()
    )
}
