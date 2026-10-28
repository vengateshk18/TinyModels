package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.common.InferenceError
import com.ai.tinymodels.core.common.TinyLog
import com.ai.tinymodels.core.inference.InferenceException
import com.ai.tinymodels.core.inference.gguf.GgufMetadataReader
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.ModelFormat
import com.dark.gguf_lib.GGMLEngine
import com.dark.tn_security.TnModule
import com.dark.tn_security.TnSecurity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * The GGUF (llama.cpp) [ModelRuntime], backed by ToolNeuron's prebuilt
 * `gguf_lib-release.aar` (`com.dark.gguf_lib.GGMLEngine`).
 *
 * This reuses the same native llama.cpp build ToolNeuron ships, so `.gguf`
 * models actually run on-device. Metadata (max context tokens) is still read
 * in pure Kotlin via [GgufMetadataReader] for budgeting/UI even before load.
 */
@Singleton
class GgufRuntime @Inject constructor(
    private val dispatchers: DispatcherProvider,
    @ApplicationContext private val context: android.content.Context,
) : ModelRuntime {

    private val engine = GGMLEngine()

    /**
     * Serializes ALL native engine access — load, unload, and every
     * generation stream. ToolNeuron achieves the same discipline by keeping
     * its whole engine behind a single-purpose `:inference` process; here the
     * engine lives in the app process, so the mutex does that job. Without
     * it, closing or re-initializing the engine while a decode loop is inside
     * `nativeGenerateStreamMultiTurn` frees llama.cpp weights mid-use — a
     * use-after-free SIGSEGV, not a catchable exception.
     */
    private val engineMutex = Mutex()

    /** Runs [block] while no load/unload/reload can touch the native engine. */
    suspend fun <T> withEngineLock(block: suspend () -> T): T = engineMutex.withLock { block() }

    private var metadata: GgufMetadataReader.Metadata? = null
    private var activeRequest: RuntimeInitRequest? = null

    override val format: ModelFormat = ModelFormat.GGUF

    override val supportsToolCalling: Boolean = false

    override val isInitialized: Boolean get() = engine.isLoaded

    override fun detectVisionSupport(modelFile: File): Boolean =
        findMmprojFile(modelFile) != null

    override suspend fun initialize(request: RuntimeInitRequest) {
        withContext(dispatchers.io) {
            // tn_security must be initialized before the gguf native lib is
            // touched — its static initializer references TnSecurity.
            try {
                val crashDir = java.io.File(context.filesDir, "tn_security/crashes").apply { mkdirs() }
                TnSecurity.init(
                    context = context,
                    module = TnModule.TN_APP,
                    crashDir = crashDir,
                    installSignalHandlers = false,
                )
            } catch (t: Throwable) {
                TinyLog.w(TAG, "TnSecurity init failed: ${t.message}")
            }

            // Read metadata first (pure Kotlin, no native) for max tokens.
            val md = try {
                GgufMetadataReader.read(request.modelFile)
            } catch (t: Throwable) {
                TinyLog.w(TAG, "Could not read GGUF metadata: ${t.message}")
                null
            }
            metadata = md
            activeRequest = request

            if (md != null) {
                TinyLog.i(
                    TAG,
                    "GGUF metadata: arch=${md.architecture} ctx=${md.contextLength} " +
                        "embed=${md.embeddingLength} layers=${md.blockCount} name=${md.name}"
                )
            }

            // The engine-touching span runs under the same lock the session
            // streams acquire, so a load can never interleave with a decode.
            engineMutex.withLock {
            // Mirror ToolNeuron's unload discipline (unloadEverything()): stop
            // any in-flight decode so the native loop is never left running
            // across a weight free/reload, and drop a stale projector from a
            // previously loaded model before loading this one.
            try { engine.stopGeneration() } catch (_: Throwable) {}
            if (engine.isLoaded) engine.unload()
            try { engine.releaseVlmProjector() } catch (_: Throwable) {}

            engine.setThreadMode(1)
            val ctx = (md?.contextLength ?: request.maxNumTokens).coerceIn(512, 131_072)
            val success = engine.load(
                path = request.modelFile.absolutePath,
                contextSize = ctx,
                flashAttn = true,
                cacheTypeK = "q8_0",
                cacheTypeV = "q8_0",
            )
            if (!success) {
                throw InferenceException(InferenceError.LoadFailed("Failed to load GGUF model", null))
            }

            // Optional vision projector (mmproj). Loaded only when requested AND
            // a companion mmproj file exists next to the model.
            if (request.enableVision) {
                val mmproj = findMmprojFile(request.modelFile)
                if (mmproj != null) {
                    val vlmOk = engine.loadVlmProjector(
                        path = mmproj.absolutePath,
                        threads = 2,
                        imageMinTokens = 256,
                        imageMaxTokens = 1024,
                    )
                    TinyLog.i(TAG, "GGUF VLM projector load: $vlmOk (${mmproj.name})")
                    if (!vlmOk) {
                        // Keep the text model usable; vision just disabled.
                        TinyLog.w(TAG, "VLM projector failed to load; continuing text-only")
                    }
                } else {
                    TinyLog.i(TAG, "No companion mmproj file; vision disabled")
                }
            }
            } // engineMutex.withLock
        }
    }

    override fun createSession(request: SessionRequest): ChatSession {
        if (!engine.isLoaded) throw InferenceException(InferenceError.NoModelLoaded)
        return GgufChatSession(engine = engine, runtime = this, request = request)
    }

    override suspend fun close() {
        withContext(dispatchers.io) {
            // Ask the decode loop to wind down BEFORE waiting on the lock, so
            // an in-flight generation exits quickly instead of delaying the
            // unload. Freeing weights mid-decode is the hard crash this class
            // exists to prevent.
            try { engine.stopGeneration() } catch (_: Throwable) {}
            engineMutex.withLock {
                try { engine.releaseVlmProjector() } catch (_: Throwable) {}
                try { engine.unload() } catch (_: Throwable) {}
                metadata = null
                activeRequest = null
            }
        }
    }

    /** Find a companion mmproj file (vision projector) next to the model. */
    private fun findMmprojFile(modelFile: File): File? {
        val parent = modelFile.parentFile ?: return null
        return parent.listFiles()
            ?.firstOrNull { it.isFile && it.name.contains("mmproj", ignoreCase = true) && it.name.endsWith(".gguf", ignoreCase = true) }
    }

    /** Exposed for tests / UI: last-read metadata (null if unreadable). */
    fun lastMetadata(): GgufMetadataReader.Metadata? = metadata

    /** Exposed for tests / UI: max context tokens extracted from the GGUF. */
    fun extractedMaxTokens(): Int? = metadata?.contextLength

    companion object {
        private const val TAG = "GgufRuntime"
    }
}