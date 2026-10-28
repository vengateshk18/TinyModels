package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.common.InferenceError
import com.ai.tinymodels.core.common.TinyLog
import com.ai.tinymodels.core.inference.InferenceException
import com.ai.tinymodels.core.inference.gguf.GgufMetadataReader
import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.ModelFormat
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The GGUF (llama.cpp) [ModelRuntime].
 *
 * GGUF metadata (context length, architecture, quantization, …) is parsed
 * in pure Kotlin via [GgufMetadataReader], so the app can discover a model's
 * max-token budget without loading it. The actual native inference requires
 * the llama.cpp library built with the Android NDK; that binary is NOT bundled
 * in this build, so [initialize] records the metadata and then fails fast with
 * a clear [InferenceException] instead of crashing inside native code.
 *
 * To enable real GGUF execution:
 *   1. Build llama.cpp (with the Android toolchain) into libllama.so for
 *      arm64-v8a and place it under app/src/main/jniLibs/arm64-v8a/.
 *   2. Add a JNI bridge (app/src/main/cpp/gguf_jni.cpp) exposing
 *      loadModel / generate / close, mirroring LitertLmRuntime's contract.
 *   3. Replace the throw in [initialize] with native handle creation and
 *      implement [createSession] against that handle.
 *
 * The metadata reader is independently tested and reusable regardless of the
 * native runtime.
 */
@Singleton
class GgufRuntime @Inject constructor(
    private val dispatchers: DispatcherProvider
) : ModelRuntime {

    private var metadata: GgufMetadataReader.Metadata? = null
    private var activeRequest: RuntimeInitRequest? = null

    override val format: ModelFormat = ModelFormat.GGUF

    override val supportsToolCalling: Boolean = false

    override val isInitialized: Boolean get() = false

    override fun detectVisionSupport(modelFile: File): Boolean = false

    /**
     * Read and record GGUF metadata (max context tokens etc.) so it is
     * available for budgeting/UI, then fail fast because the native llama.cpp
     * runtime is not bundled in this build.
     */
    override suspend fun initialize(request: RuntimeInitRequest) {
        withContext(dispatchers.io) {
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
        }
        throw InferenceException(
            InferenceError.UnsupportedFormat("GGUF")
        )
    }

    override fun createSession(request: SessionRequest): ChatSession {
        throw InferenceException(InferenceError.NoModelLoaded)
    }

    override suspend fun close() {
        withContext(dispatchers.io) {
            metadata = null
            activeRequest = null
        }
    }

    /** Exposed for tests / UI: last-read metadata (null if unreadable). */
    fun lastMetadata(): GgufMetadataReader.Metadata? = metadata

    /** Exposed for tests / UI: max context tokens extracted from the GGUF. */
    fun extractedMaxTokens(): Int? = metadata?.contextLength

    companion object {
        private const val TAG = "GgufRuntime"
    }
}