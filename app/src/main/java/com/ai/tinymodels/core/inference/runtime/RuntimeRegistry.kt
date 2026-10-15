package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.domain.model.ModelFormat

/**
 * Format → runtime dispatch. Formats without a registered runtime resolve to
 * null so [com.ai.tinymodels.core.inference.ModelManager] can fail fast with
 * the user-facing "not supported yet" message instead of an opaque native
 * error. Adding a runtime (e.g. GGUF later) means adding one entry to
 * [RuntimeModule].
 */
class RuntimeRegistry(private val runtimes: List<ModelRuntime>) {

    private val byFormat: Map<ModelFormat, ModelRuntime> =
        runtimes.associateBy { it.format }

    /** The runtime that executes [format], or null when none exists yet. */
    fun runtimeFor(format: ModelFormat): ModelRuntime? = byFormat[format]

    /** Formats with a working on-device executor today. */
    val supportedFormats: Set<ModelFormat> get() = byFormat.keys
}
