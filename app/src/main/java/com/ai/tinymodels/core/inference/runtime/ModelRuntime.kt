package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.domain.model.BackendPreference
import com.ai.tinymodels.domain.model.ModelFormat
import com.ai.tinymodels.domain.model.SamplerSettings
import com.google.ai.edge.litertlm.ToolProvider
import java.io.File

/**
 * One executable model backend — the pluggable "model running" part.
 *
 * There is exactly one runtime per [format]. [ModelManager] owns the product
 * policy (memory guard, load progress, GPU→CPU fallback, atomic swap,
 * lifecycle state) and delegates all format-specific native work here.
 *
 * Lifecycle: [initialize] → [createSession]* → [close]. All native calls are
 * serialized by [ModelManager]'s lock.
 */
interface ModelRuntime {

    /** The file format this runtime executes. */
    val format: ModelFormat

    /** True when the runtime can execute model-callable tools (LiteRT-LM only today). */
    val supportsToolCalling: Boolean

    /**
     * True when [modelFile] itself ships what this runtime needs for photo
     * input. Runtimes without a vision path (MediaPipe, today) return false
     * so histories are stripped and photo sends blocked up-front.
     */
    fun detectVisionSupport(modelFile: File): Boolean

    /**
     * Create + initialize the native handle for ONE backend attempt. Called
     * under [ModelManager]'s lock, off the main thread. Throws on failure.
     * On success, any previously initialized handle of THIS runtime has been
     * closed (only after the new one was fully ready).
     */
    suspend fun initialize(request: RuntimeInitRequest)

    /** True when a native handle is initialized and ready to build sessions. */
    val isInitialized: Boolean

    /**
     * Build a live session on the initialized handle. Callers serialize
     * native access (ChatViewModel's native mutex / ModelManager's lock).
     */
    fun createSession(request: SessionRequest): ChatSession

    /**
     * Re-initialize without the vision path, keeping backend + token budget.
     * Self-healing path for engines built with vision against a text-only
     * model. Returns false when not applicable or the rebuild failed.
     */
    suspend fun reloadWithoutVision(): Boolean = false

    /** Release the native handle. Safe when nothing is initialized. */
    suspend fun close()
}

/** Load-time parameters for one runtime initialize attempt. */
data class RuntimeInitRequest(
    val modelFile: File,
    val backend: BackendPreference,
    val maxNumTokens: Int,
    val cacheDir: File,
    /** True only when the runtime's [ModelRuntime.detectVisionSupport] approved the file. */
    val enableVision: Boolean
)

/** Per-conversation parameters for building a session. */
data class SessionRequest(
    val maxContextTokens: Int,
    val systemInstruction: String? = null,
    val history: List<ChatTurn> = emptyList(),
    val sampler: SamplerSettings = SamplerSettings(),
    val priorSummary: String? = null,
    /**
     * LiteRT-LM tool providers (e.g. web search). Runtimes without tool
     * support ignore this; callers only populate it when
     * `ModelManager.activeRuntimeSupportsTools` is true.
     */
    val tools: List<ToolProvider> = emptyList()
)
