package com.example.tinymodels.core.inference

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.example.tinymodels.core.common.AppError
import com.example.tinymodels.core.common.AppResult
import com.example.tinymodels.core.common.DispatcherProvider
import com.example.tinymodels.core.common.InferenceError
import com.example.tinymodels.core.common.MemoryUtils
import com.example.tinymodels.domain.model.BackendPreference
import com.example.tinymodels.domain.model.EngineLoadConfig
import com.example.tinymodels.domain.model.LoadedModel
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
            when (val result = initializeEngine(modelFile, backendImpl, maxNumTokens, cacheDir)) {
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
                        cacheDir = cacheDir.absolutePath
                    )
                    old?.closeQuietly()

                    val loaded = LoadedModel(modelId, preference, maxNumTokens)
                    _engineState.value = EngineState.Ready(loaded)
                    onProgress?.invoke(1.0f, ModelLoadStage.READY)
                    Log.i(TAG, "Loaded $modelId on $preference (maxTokens=$maxNumTokens)")
                    return AppResult.Success(loaded)
                }
                is AppResult.Error -> {
                    lastFailure = InferenceError.LoadFailed(result.error.message)
                    Log.w(TAG, "Load failed on $preference: ${result.error.message}")
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
        cacheDir: File
    ): AppResult<Engine> = withContext(dispatchers.io) {
        try {
            val engine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    cacheDir = cacheDir.absolutePath,
                    maxNumTokens = maxNumTokens
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
