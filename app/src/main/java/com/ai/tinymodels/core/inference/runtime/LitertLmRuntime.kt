package com.ai.tinymodels.core.inference.runtime

import android.util.Log
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.common.InferenceError
import com.ai.tinymodels.core.inference.InferenceException
import com.ai.tinymodels.core.inference.ModelVisionSupport
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.ModelFormat
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The LiteRT-LM (.litertlm) [ModelRuntime]: owns the process-wide [Engine]
 * and builds [ConversationSession]s on it.
 *
 * Vision handling: a vision backend + image slots are REQUIRED for photo
 * messages — but ONLY for multimodal models. Setting them on a text-only
 * model (no TF_LITE_VISION_ENCODER section) makes `createConversation()`
 * fail with NOT_FOUND, so [ModelManager] gates [RuntimeInitRequest.enableVision]
 * on [detectVisionSupport].
 */
@Singleton
class LitertLmRuntime @Inject constructor(
    private val dispatchers: DispatcherProvider
) : ModelRuntime {

    private var engine: Engine? = null
    private var activeRequest: RuntimeInitRequest? = null

    override val format: ModelFormat = ModelFormat.LITERTLM

    override val supportsToolCalling: Boolean = true

    override val isInitialized: Boolean get() = engine != null

    override fun detectVisionSupport(modelFile: File): Boolean =
        ModelVisionSupport.supportsVision(modelFile)

    override suspend fun initialize(request: RuntimeInitRequest) {
        // Native engine construction blocks — always off the main thread.
        val created = withContext(dispatchers.io) { createEngine(request) }
        // Swap only after the new engine is fully initialized, so a failed
        // load never leaves the app with no working engine.
        val old = engine
        engine = created
        activeRequest = request
        old?.closeQuietly()
    }

    override fun createSession(request: SessionRequest): ChatSession {
        val current = engine ?: throw InferenceException(InferenceError.NoModelLoaded)
        return ConversationSession.create(
            engine = current,
            systemInstruction = request.systemInstruction,
            history = request.history,
            sampler = request.sampler,
            maxContextTokens = request.maxContextTokens,
            priorSummary = request.priorSummary,
            tools = request.tools
        )
    }

    override suspend fun reloadWithoutVision(): Boolean {
        val config = activeRequest ?: return false
        if (!config.enableVision) return false
        return try {
            Log.w(TAG, "Reloading ${config.modelFile.name} without vision (self-heal)")
            initialize(config.copy(enableVision = false))
            true
        } catch (throwable: Throwable) {
            Log.w(TAG, "Reload without vision failed: ${throwable.message}")
            false
        }
    }

    override suspend fun close() {
        withContext(dispatchers.io) {
            engine?.closeQuietly()
            engine = null
            activeRequest = null
        }
    }

    /**
     * Build + initialize an engine for one backend attempt. Throws on failure;
     * a half-initialized engine is closed before rethrowing so no native
     * memory leaks on failed loads.
     */
    private fun createEngine(request: RuntimeInitRequest): Engine {
        val backend = when (request.backend) {
            BackendPreference.CPU -> Backend.CPU()
            BackendPreference.GPU, BackendPreference.AUTO -> Backend.GPU()
        }
        // The vision backend follows the tried backend so GPU→CPU fallback
        // covers vision too. Without vision slots the native vision executor
        // stays null and any image part fails with "Vision executor should
        // not be null… Status code 3".
        val engine = Engine(
            EngineConfig(
                modelPath = request.modelFile.absolutePath,
                backend = backend,
                visionBackend = if (request.enableVision) backend else null,
                maxNumTokens = request.maxNumTokens,
                maxNumImages = if (request.enableVision) MAX_IMAGES else null,
                cacheDir = request.cacheDir.absolutePath
            )
        )
        try {
            engine.initialize()
        } catch (cancellation: CancellationException) {
            engine.closeQuietly()
            throw cancellation
        } catch (throwable: Throwable) {
            engine.closeQuietly()
            throw throwable
        }
        return engine
    }

    private fun Engine.closeQuietly() {
        try {
            close()
        } catch (throwable: Throwable) {
            Log.w(TAG, "Error closing engine: ${throwable.message}")
        }
    }

    companion object {
        private const val TAG = "LitertLmRuntime"

        /**
         * Images the live engine accepts. Covers several photo turns in one
         * history; each photo also costs [SessionBudget.IMAGE_TOKEN_COST]
         * context tokens, so the trim logic keeps this bounded.
         */
        const val MAX_IMAGES = 4
    }
}
