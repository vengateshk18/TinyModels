package com.ai.tinymodels.core.inference

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.common.InferenceError
import com.ai.tinymodels.core.common.MemoryUtils
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.EngineLoadConfig
import com.ai.tinymodels.domain.model.LoadedModel
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide owner of the LiteRT-LM [Engine].
 *
 * Memory contract:
 *  - At most ONE engine (and its native weights) exists at any moment.
 *  - Loading a new model swaps engines atomically: the new engine is fully
 *    initialized BEFORE the old one is closed, so a failed load never leaves
 *    the app with no working engine.
 *  - [unloadModel] frees native memory immediately via [Engine.close].
 *  - [onTrimMemory] releases the engine under system memory pressure.
 *
 * The engine lives independently of any ViewModel, so configuration changes
 * and navigation never trigger an expensive reload.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) {
    companion object {
        private const val TAG = "ModelManager"
        const val DEFAULT_MAX_TOKENS = 2048

        /**
         * Images the live engine accepts. Covers several photo turns in one
         * history; each photo also costs [ConversationSession.IMAGE_TOKEN_COST]
         * context tokens, so the trim logic keeps this bounded.
         */
        const val MAX_IMAGES = 4
    }

    /** High-level lifecycle of the loaded model, surfaced to the UI. */
    sealed interface EngineState {
        data object Idle : EngineState
        data class Loading(val modelId: String) : EngineState
        data class Ready(val model: LoadedModel) : EngineState
        data class Error(val modelId: String, val error: InferenceError) : EngineState
    }

    /** Model loading progress stages. */
    enum class ModelLoadStage {
        INITIALIZING,
        LOADING_WEIGHTS,
        READY
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var engine: Engine? = null
    private var activeConfig: EngineLoadConfig? = null

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    val isModelLoaded: Boolean get() = engine != null
    val loadedModelId: String? get() = (engineState.value as? EngineState.Ready)?.model?.modelId

    /** The maxNumTokens the live engine was actually loaded with, if any. */
    val activeMaxNumTokens: Int?
        get() = (engineState.value as? EngineState.Ready)?.model?.maxNumTokens

    /** True when the live engine was built with a vision backend. */
    val isVisionModelLoaded: Boolean
        get() = (engineState.value as? EngineState.Ready)?.model?.visionEnabled == true

    /**
     * Load [modelFile] with the given backend preference + token budget.
     *
     * Guards available RAM up-front, tries GPU (if requested) and falls back to
     * CPU, and only swaps the live engine after a successful initialize.
     */
    suspend fun loadModel(
        modelId: String,
        modelFile: File,
        backend: BackendPreference,
        maxNumTokens: Int = DEFAULT_MAX_TOKENS,
        cacheDir: File = context.cacheDir,
        onProgress: ((Float, ModelLoadStage) -> Unit)? = null
    ): AppResult<LoadedModel> = mutex.withLock {
        if (!modelFile.exists()) {
            val error = InferenceError.ModelFileMissing("Missing: ${modelFile.absolutePath}")
            _engineState.value = EngineState.Error(modelId, error)
            return AppResult.Error(error.asAppError())
        }

        // Pre-load memory guard.
        val snapshot = MemoryUtils.snapshot(context)
        val required = MemoryUtils.requiredBytesForModel(modelFile)
        if (!MemoryUtils.hasHeadroom(snapshot, required)) {
            val error = InferenceError.OutOfMemory(
                "Need ~${required / (1024 * 1024)}MB free, have ${snapshot.availableBytes / (1024 * 1024)}MB"
            )
            _engineState.value = EngineState.Error(modelId, error)
            return AppResult.Error(error.asAppError())
        }

        _engineState.value = EngineState.Loading(modelId)
        onProgress?.invoke(0.1f, ModelLoadStage.INITIALIZING)

        // Vision backends are ONLY valid for multimodal models. Enabling them
        // for a text-only `.litertlm` (no TF_LITE_VISION_ENCODER section) makes
        // `createConversation()` fail with
        // "NOT_FOUND: TF_LITE_VISION_ENCODER not found in the model", so detect
        // support from the file up-front and leave vision off otherwise.
        // File I/O here: callers invoke loadModel off the main thread.
        val visionSupported = ModelVisionSupport.supportsVision(modelFile)
        Log.i(TAG, "Vision support for $modelId: $visionSupported")

        val backendsToTry: List<Pair<BackendPreference, Backend>> = when (backend) {
            BackendPreference.CPU -> listOf(BackendPreference.CPU to Backend.CPU())
            BackendPreference.GPU, BackendPreference.AUTO -> listOf(
                BackendPreference.GPU to Backend.GPU(),
                BackendPreference.CPU to Backend.CPU()
            )
        }

        var lastFailure: InferenceError? = null
        for ((preference, backendImpl) in backendsToTry) {
            onProgress?.invoke(0.3f, ModelLoadStage.INITIALIZING)
            // First attempt uses vision only when the model supports it.
            val firstTryVision = visionSupported
            when (val result = initializeEngine(modelFile, backendImpl, maxNumTokens, cacheDir, firstTryVision)) {
                is AppResult.Success -> {
                    onProgress?.invoke(0.5f, ModelLoadStage.LOADING_WEIGHTS)
                    val newEngine = result.data
                    // Swap: close old engine only after the new one is ready.
                    val old = engine
                    engine = newEngine
                    activeConfig = EngineLoadConfig(
                        modelPath = modelFile.absolutePath,
                        backend = preference,
                        maxNumTokens = maxNumTokens,
                        cacheDir = cacheDir.absolutePath,
                        visionEnabled = firstTryVision
                    )
                    old?.closeQuietly()

                    val loaded = LoadedModel(modelId, preference, maxNumTokens, firstTryVision)
                    _engineState.value = EngineState.Ready(loaded)
                    onProgress?.invoke(1.0f, ModelLoadStage.READY)
                    Log.i(TAG, "Loaded $modelId on $preference (maxTokens=$maxNumTokens, visionImages=${if (firstTryVision) MAX_IMAGES else 0})")
                    return AppResult.Success(loaded)
                }
                is AppResult.Error -> {
                    // Self-heal a wrong vision guess: if we enabled vision and
                    // the native side reports a vision problem, retry the same
                    // backend once without vision before falling through.
                    if (firstTryVision && isVisionLoadError(result.error.message)) {
                        Log.w(TAG, "Load with vision failed on $preference, retrying without vision")
                        when (val retry = initializeEngine(modelFile, backendImpl, maxNumTokens, cacheDir, false)) {
                            is AppResult.Success -> {
                                onProgress?.invoke(0.5f, ModelLoadStage.LOADING_WEIGHTS)
                                val newEngine = retry.data
                                val old = engine
                                engine = newEngine
                                activeConfig = EngineLoadConfig(
                                    modelPath = modelFile.absolutePath,
                                    backend = preference,
                                    maxNumTokens = maxNumTokens,
                                    cacheDir = cacheDir.absolutePath,
                                    visionEnabled = false
                                )
                                old?.closeQuietly()

                                val loaded = LoadedModel(modelId, preference, maxNumTokens, false)
                                _engineState.value = EngineState.Ready(loaded)
                                onProgress?.invoke(1.0f, ModelLoadStage.READY)
                                Log.i(TAG, "Loaded $modelId on $preference without vision (maxTokens=$maxNumTokens)")
                                return AppResult.Success(loaded)
                            }
                            is AppResult.Error -> {
                                lastFailure = InferenceError.LoadFailed(retry.error.message)
                                Log.w(TAG, "Load failed on $preference: ${retry.error.message}")
                            }
                        }
                    } else {
                        lastFailure = InferenceError.LoadFailed(result.error.message)
                        Log.w(TAG, "Load failed on $preference: ${result.error.message}")
                    }
                }
            }
        }

        val finalError = lastFailure ?: InferenceError.BackendUnavailable()
        _engineState.value = EngineState.Error(modelId, finalError)
        onProgress?.invoke(0f, ModelLoadStage.INITIALIZING)
        AppResult.Error(finalError.asAppError())
    }

    /** Initialize an [Engine] off the main thread. Returns the engine on success. */
    private suspend fun initializeEngine(
        modelFile: File,
        backend: Backend,
        maxNumTokens: Int,
        cacheDir: File,
        enableVision: Boolean = false
    ): AppResult<Engine> = withContext(dispatchers.io) {
        try {
            // Vision backend + image slots are REQUIRED for photo messages —
            // but ONLY for multimodal models. Setting them on a text-only
            // model (no TF_LITE_VISION_ENCODER section) makes
            // createConversation() fail with NOT_FOUND, so callers must gate
            // [enableVision] on ModelVisionSupport.supportsVision().
            // Without them the native vision executor stays null and any
            // image part fails with "Vision executor should not be null…
            // TryLoadingVisionExecutor() first. Status code 3". The vision
            // backend follows the tried backend so GPU→CPU fallback covers it.
            val engine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    visionBackend = if (enableVision) backend else null,
                    maxNumTokens = maxNumTokens,
                    maxNumImages = if (enableVision) MAX_IMAGES else null,
                    cacheDir = cacheDir.absolutePath
                )
            )
            engine.initialize()
            AppResult.Success(engine)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AppResult.Error(InferenceError.LoadFailed(throwable.message, throwable).asAppError())
        }
    }

    /** Free the engine and its native memory. Safe to call when nothing is loaded. */
    suspend fun unloadModel() {
        mutex.withLock {
            engine?.closeQuietly()
            engine = null
            activeConfig = null
            _engineState.value = EngineState.Idle
            Log.i(TAG, "Model unloaded")
        }
    }

    /**
     * Unload the model on the singleton [scope] that outlives any ViewModel.
     * Use this from [ViewModel.onCleared] where [viewModelScope] is already
     * cancelled and a regular `launch` would be a no-op.
     */
    fun unloadInBackground() {
        scope.launch { unloadModel() }
    }

    /**
     * Rebuild the live engine WITHOUT a vision backend, keeping the same
     * model file + backend + token budget. Self-healing path for
     * `TF_LITE_VISION_ENCODER not found`: an engine that was built with
     * vision against a text-only model can never create a conversation, so
     * drop vision and retry instead of leaving the chat unusable.
     *
     * @return true when the engine was rebuilt without vision.
     */
    suspend fun reloadWithoutVision(): Boolean = mutex.withLock {
        val config = activeConfig ?: return false
        if (!config.visionEnabled) return false
        val modelId = (engineState.value as? EngineState.Ready)?.model?.modelId
            ?: return false
        val modelFile = File(config.modelPath)
        if (!modelFile.exists()) return false
        val backendImpl = when (config.backend) {
            BackendPreference.CPU -> Backend.CPU()
            BackendPreference.GPU, BackendPreference.AUTO -> try {
                Backend.GPU()
            } catch (throwable: Throwable) {
                Backend.CPU()
            }
        }
        Log.w(TAG, "Reloading $modelId without vision (self-heal for missing vision encoder)")
        when (val result = initializeEngine(
            modelFile, backendImpl, config.maxNumTokens, File(config.cacheDir), false
        )) {
            is AppResult.Success -> {
                val old = engine
                engine = result.data
                activeConfig = config.copy(visionEnabled = false)
                old?.closeQuietly()
                _engineState.value = EngineState.Ready(
                    LoadedModel(modelId, config.backend, config.maxNumTokens, false)
                )
                Log.i(TAG, "Reloaded $modelId without vision")
                true
            }
            is AppResult.Error -> {
                Log.w(TAG, "Reload without vision failed: ${result.error.message}")
                false
            }
        }
    }

    private fun isVisionLoadError(message: String?): Boolean =
        message != null && (
            message.contains("TF_LITE_VISION_ENCODER") ||
                message.contains("vision encoder", ignoreCase = true) ||
                message.contains("vision executor", ignoreCase = true)
            )

    /**
     * Execute [block] with the live engine, holding the mutex so the engine
     * cannot be unloaded mid-generation.
     */
    suspend fun <T> withEngine(block: suspend (Engine, EngineLoadConfig) -> T): T =
        mutex.withLock {
            val current = engine ?: throw InferenceException(InferenceError.NoModelLoaded)
            val config = activeConfig ?: throw InferenceException(InferenceError.NoModelLoaded)
            block(current, config)
        }

    /** Called by the app's ComponentCallbacks2 under memory pressure. */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE && engine != null) {
            Log.w(TAG, "onTrimMemory($level): unloading model to relieve pressure")
            scope.launch { unloadModel() }
        }
    }

    private fun Engine.closeQuietly() {
        try {
            close()
        } catch (throwable: Throwable) {
            Log.w(TAG, "Error closing engine: ${throwable.message}")
        }
    }

    private fun InferenceError.asAppError(): AppError = AppError.Unknown(this.message)
}

/** Thrown when an operation requires a loaded model but none is present. */
class InferenceException(val inferenceError: InferenceError) : Exception(inferenceError.message)
