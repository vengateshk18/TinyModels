package com.ai.tinymodels.core.inference.runtime

/**
 * JNI bridge to the native llama.cpp library.
 *
 * The native library (`libgguf_bridge.so`) is NOT bundled in this build — it
 * must be produced by building llama.cpp with the Android NDK (see
 * `app/src/main/cpp/README.md`). When the library is absent, [isAvailable]
 * returns false and every native method throws [UnsatisfiedLinkError], which
 * callers must handle gracefully.
 *
 * When available, the bridge exposes the minimal surface TinyModels needs:
 * load a model, generate text (streaming via callback), query context size,
 * and close. Chat templates / sampling are handled on the Kotlin side using
 * the same per-family logic as the LiteRT/MediaPipe runtimes.
 */
object LlamaCppBridge {

    private val loaded: Boolean = try {
        System.loadLibrary("gguf_bridge")
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    }

    /** True when the native llama.cpp bridge is present on this device/build. */
    fun isAvailable(): Boolean = loaded

    /**
     * Load a GGUF model from [path] with the requested context size.
     * Returns an opaque handle (0 on failure).
     */
    @JvmStatic
    external fun nativeLoadModel(path: String, nCtx: Int): Long

    /** Number of context tokens the loaded model supports (0 if unknown). */
    @JvmStatic
    external fun nativeContextSize(handle: Long): Int

    /**
     * Run generation. [onToken] is invoked for each produced token (may be
     * called on a background thread). Returns the full generated text.
     */
    @JvmStatic
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        onToken: ((String) -> Unit)?,
    ): String

    /** Release the native handle. */
    @JvmStatic
    external fun nativeClose(handle: Long)
}