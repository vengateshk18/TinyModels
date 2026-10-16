package com.ai.tinymodels.core.inference.runtime

import android.content.Context
import com.ai.tinymodels.core.common.TinyLog
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.common.InferenceError
import com.ai.tinymodels.core.inference.InferenceException
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.ModelFormat
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The MediaPipe (.task) [ModelRuntime]: owns the process-wide [LlmInference]
 * and builds [MediaPipeSession]s on it.
 *
 * Scope notes:
 *  - Text-only today. `detectVisionSupport` returns false, so photo sends are
 *    blocked up-front and histories are stripped by the existing guards.
 *  - No tool calling (that is a LiteRT-LM feature).
 *  - MediaPipe LLM Inference is in maintenance mode upstream, but it remains
 *    the only library that executes `.task` bundles.
 */
@Singleton
class MediaPipeLlmRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) : ModelRuntime {

    private var llmInference: LlmInference? = null

    override val format: ModelFormat = ModelFormat.TASK

    override val supportsToolCalling: Boolean = false

    override val isInitialized: Boolean get() = llmInference != null

    /** No vision path in this app's MediaPipe usage (text LLM `.task` bundles). */
    override fun detectVisionSupport(modelFile: File): Boolean = false

    override suspend fun initialize(request: RuntimeInitRequest) {
        // Native model load blocks — always off the main thread.
        val created = withContext(dispatchers.io) {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(request.modelFile.absolutePath)
                // Total (input + output) token budget for every session.
                .setMaxTokens(request.maxNumTokens)
                // Session topK is clamped to this ceiling.
                .setMaxTopK(MediaPipeSamplerMapper.MAX_TOP_K)
                .setPreferredBackend(backendOf(request.backend))
                .build()
            LlmInference.createFromOptions(context, options)
        }
        // Swap only after the new model is fully created.
        val old = llmInference
        llmInference = created
        old?.closeQuietly()
        TinyLog.i(TAG, "Loaded ${request.modelFile.name} on ${request.backend} (maxTokens=${request.maxNumTokens})")
    }

    override fun createSession(request: SessionRequest): ChatSession {
        val current = llmInference ?: throw InferenceException(InferenceError.NoModelLoaded)
        return MediaPipeSession(llmInference = current, request = request)
    }

    override suspend fun close() {
        withContext(dispatchers.io) {
            llmInference?.closeQuietly()
            llmInference = null
        }
    }

    private fun backendOf(preference: BackendPreference): LlmInference.Backend = when (preference) {
        BackendPreference.CPU -> LlmInference.Backend.CPU
        BackendPreference.GPU -> LlmInference.Backend.GPU
        BackendPreference.AUTO -> LlmInference.Backend.DEFAULT
    }

    private fun LlmInference.closeQuietly() {
        try {
            close()
        } catch (throwable: Throwable) {
            TinyLog.w(TAG, "Error closing LlmInference: ${throwable.message}")
        }
    }

    companion object {
        private const val TAG = "MediaPipeLlmRuntime"
    }
}
