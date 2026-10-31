package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.common.InferenceError
import com.ai.tinymodels.core.common.TinyLog
import com.ai.tinymodels.core.inference.InferenceException
import com.ai.tinymodels.core.inference.gguf.GgufDiagnostics
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

    /** Context size the engine was actually loaded with (from GGUF metadata). */
    private var loadedContextSize: Int? = null

    override val format: ModelFormat = ModelFormat.GGUF

    override val supportsToolCalling: Boolean = false

    override val isInitialized: Boolean get() = engine.isLoaded

    /** The real context size the engine was loaded with — n_ctx from the
     *  GGUF metadata — so ModelManager/chat UI report actual numbers rather
     *  than the caller's requested budget. */
    override fun extractedMaxTokens(): Int? = loadedContextSize

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

            val modelFile = request.modelFile
            val header = GgufDiagnostics.readHeader(modelFile)
            TinyLog.i(
                TAG,
                "load:start ${GgufDiagnostics.describe(modelFile)} " +
                    "requestedCtx=${request.maxNumTokens} vision=${request.enableVision} backend=${request.backend}"
            )
            TinyLog.i(
                TAG,
                "load:header isGguf=${header.isGguf} raw=${header.rawMagicHex} version=${header.version} " +
                    "tensors=${header.tensorCount} kv=${header.kvCount} bytesRead=${header.bytesRead}" +
                    (header.ioError?.let { " ioError=$it" } ?: "")
            )
            if (!header.isGguf) {
                TinyLog.w(
                    TAG,
                    "load:header NOT GGUF — first bytes=${header.rawMagicHex} " +
                        "(expected 47 47 55 46 = \"GGUF\")"
                )
            }
            val nameLooksLikeProjector = GgufDiagnostics.looksLikeProjectorName(modelFile.name)
            if (nameLooksLikeProjector) {
                TinyLog.w(
                    TAG,
                    "load:file name looks like an mmproj vision projector (${modelFile.name}); " +
                        "it cannot run as the main model"
                )
            }

            // Read metadata first (pure Kotlin, no native) for max tokens.
            val md = try {
                GgufMetadataReader.read(modelFile).also { m ->
                    TinyLog.i(
                        TAG,
                        "load:metadata arch=${m.architecture} ctx=${m.contextLength} " +
                            "embed=${m.embeddingLength} layers=${m.blockCount} name=${m.name} " +
                            "quant=${m.fileType} quantVersion=${m.quantizationVersion} " +
                            "tensors=${m.tensorCount} kv=${m.kvCount}"
                    )
                    if (GgufDiagnostics.isProjectorArchitecture(m.architecture)) {
                        TinyLog.w(
                            TAG,
                            "load:metadata arch='${m.architecture}' is a CLIP/mmproj projector, " +
                                "not a runnable language model"
                        )
                    }
                }
            } catch (t: Throwable) {
                TinyLog.w(
                    TAG,
                    "load:metadata FAILED for ${modelFile.absolutePath}: " +
                        "${t.javaClass.simpleName}: ${t.message}",
                    t
                )
                null
            }
            metadata = md
            activeRequest = request

            // A projector can be detected either by name or by the CLIP
            // architecture stamped into its GGUF metadata.
            val isProjector = nameLooksLikeProjector ||
                GgufDiagnostics.isProjectorArchitecture(md?.architecture)

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
            TinyLog.i(
                TAG,
                "load:engine start path=${modelFile.absolutePath} ctx=$ctx " +
                    "flashAttn=true cacheK=q8_0 cacheV=q8_0"
            )
            val startedAt = System.nanoTime()
            val success = try {
                engine.load(
                    path = modelFile.absolutePath,
                    contextSize = ctx,
                    flashAttn = true,
                    cacheTypeK = "q8_0",
                    cacheTypeV = "q8_0",
                )
            } catch (t: Throwable) {
                val reason = GgufDiagnostics.failureReason(header, md, isProjector)
                TinyLog.e(
                    TAG,
                    "load:engine THREW after ${elapsedMs(startedAt)}ms: " +
                        "${t.javaClass.simpleName}: ${t.message}. $reason",
                    t
                )
                throw InferenceException(
                    InferenceError.LoadFailed(
                        "GGUF load failed: ${t.message ?: t.javaClass.simpleName}. $reason",
                        t
                    )
                )
            }
            TinyLog.i(
                TAG,
                "load:engine result=$success ctx=$ctx elapsed=${elapsedMs(startedAt)}ms " +
                    "isLoaded=${engine.isLoaded}"
            )
            if (!success) {
                val reason = GgufDiagnostics.failureReason(header, md, isProjector)
                TinyLog.e(TAG, "load:engine FAILED. $reason")
                throw InferenceException(
                    InferenceError.LoadFailed("Failed to load GGUF model. $reason", null)
                )
            }
            loadedContextSize = ctx
            TinyLog.i(TAG, "load:engine OK arch=${md?.architecture ?: "unknown"} ctx=$ctx")

            // Optional vision projector (mmproj). Loaded only when requested AND
            // a companion mmproj file exists next to the model.
            if (request.enableVision) {
                val mmproj = findMmprojFile(modelFile)
                if (mmproj != null) {
                    TinyLog.i(TAG, "load:mmproj found ${GgufDiagnostics.describe(mmproj)}")
                    try {
                        val m = GgufMetadataReader.read(mmproj)
                        TinyLog.i(
                            TAG,
                            "load:mmproj metadata arch=${m.architecture} name=${m.name} " +
                                "tensors=${m.tensorCount} kv=${m.kvCount}"
                        )
                    } catch (t: Throwable) {
                        TinyLog.w(TAG, "load:mmproj metadata failed: ${t.message}", t)
                    }
                    val vlmOk = try {
                        engine.loadVlmProjector(
                            path = mmproj.absolutePath,
                            threads = 2,
                            imageMinTokens = 256,
                            imageMaxTokens = 1024,
                        )
                    } catch (t: Throwable) {
                        TinyLog.e(TAG, "load:mmproj THREW", t)
                        false
                    }
                    TinyLog.i(TAG, "load:mmproj result=$vlmOk (${mmproj.name})")
                    if (!vlmOk) {
                        // Keep the text model usable; vision just disabled.
                        TinyLog.w(TAG, "load:mmproj failed; continuing text-only")
                    }
                } else {
                    TinyLog.i(TAG, "load:mmproj none beside ${modelFile.name}; vision disabled")
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
                loadedContextSize = null
            }
        }
    }

    /** Find a companion mmproj file (vision projector) next to the model. */
    private fun findMmprojFile(modelFile: File): File? {
        val parent = modelFile.parentFile ?: return null
        return parent.listFiles()
            ?.firstOrNull {
                it.isFile &&
                    it.absolutePath != modelFile.absolutePath &&
                    it.name.contains("mmproj", ignoreCase = true) &&
                    it.name.endsWith(".gguf", ignoreCase = true)
            }
    }

    private fun elapsedMs(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos) / 1_000_000

    /** Exposed for tests / UI: last-read metadata (null if unreadable). */
    fun lastMetadata(): GgufMetadataReader.Metadata? = metadata

    companion object {
        private const val TAG = "GgufRuntime"
    }
}