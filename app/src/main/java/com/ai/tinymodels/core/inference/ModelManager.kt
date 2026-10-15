package com.ai.tinymodels.core.inference

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.ai.tinymodels.core.common.AppError
import com.ai.tinymodels.core.common.AppResult
import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.common.InferenceError
import com.ai.tinymodels.core.common.MemoryUtils
import com.ai.tinymodels.core.inference.runtime.ChatSession
import com.ai.tinymodels.core.inference.runtime.ModelRuntime
import com.ai.tinymodels.core.inference.runtime.RuntimeInitRequest
import com.ai.tinymodels.core.inference.runtime.RuntimeRegistry
import com.ai.tinymodels.core.inference.runtime.SessionRequest
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.EngineLoadConfig
import com.ai.tinymodels.domain.model.LoadedModel
import com.ai.tinymodels.domain.model.ModelFormat
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
 * Process-wide façade over the model-running layer.
 *
 * The format-specific native work lives in [ModelRuntime]s (LiteRT-LM for
 * `.litertlm`, MediaPipe for `.task`, dispatched by [RuntimeRegistry]);
 * this class owns the product-level policy that is identical for all of them:
 *
 * Memory contract:
 *  - At most ONE runtime handle (and its native weights) exists at any moment.
 *  - Loading a new model swaps runtimes atomically: the new handle is fully
 *    initialized BEFORE the old one is closed, so a failed load never leaves
 *    the app with no working model.
 *  - [unloadModel] frees native memory immediately.
 *  - [onTrimMemory] releases the model under system memory pressure.
 *
 * The loaded model lives independently of any ViewModel, so configuration
 * changes and navigation never trigger an expensive reload.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val runtimeRegistry: RuntimeRegistry
) {
    companion object {
        private const val TAG = "ModelManager"
        const val DEFAULT_MAX_TOKENS = 2048
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
    private var runtime: ModelRuntime? = null
    private var activeConfig: EngineLoadConfig? = null

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Idle)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    val isModelLoaded: Boolean get() = runtime?.isInitialized == true
    val loadedModelId: String? get() = (engineState.value as? EngineState.Ready)?.model?.modelId

    /** The maxNumTokens the live model was actually loaded with, if any. */
    val activeMaxNumTokens: Int?
        get() = (engineState.value as? EngineState.Ready)?.model?.maxNumTokens

    /** True when the live model was built with a vision encoder + image slots. */
    val isVisionModelLoaded: Boolean
        get() = (engineState.value as? EngineState.Ready)?.model?.visionEnabled == true

    /** True when the live runtime can execute model-callable tools (LiteRT-LM only today). */
    val activeRuntimeSupportsTools: Boolean
        get() = runtime?.supportsToolCalling == true

    /** The format the live runtime executes, if any (`.litertlm`, `.task`, ...). */
    val activeRuntimeFormat: ModelFormat?
        get() = runtime?.format

    /**
     * Load [modelFile] with the given backend preference + token budget.
     *
     * Guards available RAM up-front, tries GPU (if requested) then CPU, and
     * only swaps the live runtime after a successful initialize. Formats
     * without a registered runtime fail fast with an actionable message.
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

        // Dispatch by file format. Formats without a runtime (raw `.tflite`,
        // GGUF — planned for later) would fail deep inside a native engine
        // with an opaque error — fail fast with an actionable message instead.
        val format = ModelFormat.fromFileName(modelFile.name)
        val target = runtimeRegistry.runtimeFor(format)
        if (target == null) {
            val error = InferenceError.UnsupportedFormat(format.displayName)
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

        // Vision is opt-in per runtime AND per file: only the LiteRT-LM
        // runtime can drive a vision backend, and only models that actually
        // ship an encoder can back it (file inspection happens off the main
        // thread — callers invoke loadModel off the main thread).
        val visionSupported = target.detectVisionSupport(modelFile)
        Log.i(
            TAG,
            "Runtime ${target.format.displayName} selected for $modelId " +
                "(vision=$visionSupported, tools=${target.supportsToolCalling})"
        )

        val backendsToTry: List<BackendPreference> = when (backend) {
            BackendPreference.CPU -> listOf(BackendPreference.CPU)
            BackendPreference.GPU, BackendPreference.AUTO -> listOf(
                BackendPreference.GPU,
                BackendPreference.CPU
            )
        }

        var lastFailure: InferenceError? = null
        for (preference in backendsToTry) {
            onProgress?.invoke(0.3f, ModelLoadStage.INITIALIZING)
            // First attempt uses vision only when the model supports it.
            var visionThisTry = visionSupported
            while (true) {
                try {
                    target.initialize(
                        RuntimeInitRequest(
                            modelFile = modelFile,
                            backend = preference,
                            maxNumTokens = maxNumTokens,
                            cacheDir = cacheDir,
                            enableVision = visionThisTry
                        )
                    )
                    onProgress?.invoke(0.5f, ModelLoadStage.LOADING_WEIGHTS)
                    // Swap: close the previous runtime only after the new one
                    // is ready (same-runtime reloads swap internally).
                    val previous = runtime
                    runtime = target
                    activeConfig = EngineLoadConfig(
                        modelPath = modelFile.absolutePath,
                        backend = preference,
                        maxNumTokens = maxNumTokens,
                        cacheDir = cacheDir.absolutePath,
                        visionEnabled = visionThisTry
                    )
                    if (previous != null && previous !== target) {
                        withContext(dispatchers.io) { previous.close() }
                    }

                    val loaded = LoadedModel(modelId, preference, maxNumTokens, visionThisTry)
                    _engineState.value = EngineState.Ready(loaded)
                    onProgress?.invoke(1.0f, ModelLoadStage.READY)
                    Log.i(
                        TAG,
                        "Loaded $modelId on $preference via ${target.format.displayName} " +
                            "(maxTokens=$maxNumTokens, vision=$visionThisTry)"
                    )
                    return AppResult.Success(loaded)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    // Self-heal a wrong vision guess: if we enabled vision and
                    // the native side reports a vision problem, retry the same
                    // backend once without vision before falling through.
                    if (visionThisTry && isVisionLoadError(throwable.message)) {
                        Log.w(TAG, "Load with vision failed on $preference, retrying without vision")
                        visionThisTry = false
                        continue
                    }
                    lastFailure = InferenceError.LoadFailed(throwable.message, throwable)
                    Log.w(TAG, "Load failed on $preference: ${throwable.message}")
                    break
                }
            }
        }

        val finalError = lastFailure ?: InferenceError.BackendUnavailable()
        _engineState.value = EngineState.Error(modelId, finalError)
        onProgress?.invoke(0f, ModelLoadStage.INITIALIZING)
        AppResult.Error(finalError.asAppError())
    }

    /**
     * Build a fresh [ChatSession] on the live runtime, restoring prior history
     * and applying [SessionRequest.sampler].
     *
     * Native work — callers invoke this off the main thread. The load mutex is
     * held only during creation so a concurrent [unloadModel] can never pull
     * the runtime out from under a new session.
     *
     * @throws InferenceException when no model is loaded.
     */
    suspend fun createSession(request: SessionRequest): ChatSession = mutex.withLock {
        val current = runtime ?: throw InferenceException(InferenceError.NoModelLoaded)
        current.createSession(request)
    }

    /**
     * Run [block] with a session whose runtime cannot be unloaded in the
     * meantime (the load mutex is held for the whole block). The session is
     * always closed afterwards. Used by [BenchmarkRunner], which measures a
     * full generation and must not race an engine swap.
     */
    suspend fun <T> withSession(request: SessionRequest, block: suspend (ChatSession) -> T): T =
        mutex.withLock {
            val current = runtime ?: throw InferenceException(InferenceError.NoModelLoaded)
            val session = current.createSession(request)
            try {
                block(session)
            } finally {
                session.close()
            }
        }

    /** Free the live runtime and its native memory. Safe when nothing is loaded. */
    suspend fun unloadModel() {
        mutex.withLock {
            withContext(dispatchers.io) { runtime?.close() }
            runtime = null
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
     * Rebuild the live runtime WITHOUT its vision path, keeping the same
     * model file + backend + token budget. Self-healing path for
     * `TF_LITE_VISION_ENCODER not found`: a handle that was built with vision
     * against a text-only model can never create a conversation, so drop
     * vision and retry instead of leaving the chat unusable.
     *
     * @return true when the runtime was rebuilt without vision.
     */
    suspend fun reloadWithoutVision(): Boolean = mutex.withLock {
        val current = runtime ?: return false
        val config = activeConfig ?: return false
        if (!config.visionEnabled) return false
        val modelId = (engineState.value as? EngineState.Ready)?.model?.modelId ?: return false
        val reloaded = current.reloadWithoutVision()
        if (reloaded) {
            activeConfig = config.copy(visionEnabled = false)
            _engineState.value = EngineState.Ready(
                LoadedModel(modelId, config.backend, config.maxNumTokens, false)
            )
            Log.i(TAG, "Reloaded $modelId without vision")
        }
        reloaded
    }

    private fun isVisionLoadError(message: String?): Boolean =
        message != null && (
            message.contains("TF_LITE_VISION_ENCODER") ||
                message.contains("vision encoder", ignoreCase = true) ||
                message.contains("vision executor", ignoreCase = true)
            )

    /** Called by the app's ComponentCallbacks2 under memory pressure. */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE && runtime != null) {
            Log.w(TAG, "onTrimMemory($level): unloading model to relieve pressure")
            scope.launch { unloadModel() }
        }
    }

    private fun InferenceError.asAppError(): AppError = AppError.Unknown(this.message)
}

/** Thrown when an operation requires a loaded model but none is present. */
class InferenceException(val inferenceError: InferenceError) : Exception(inferenceError.message)
