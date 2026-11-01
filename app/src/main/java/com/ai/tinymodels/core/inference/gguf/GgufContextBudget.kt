package com.ai.tinymodels.core.inference.gguf

/**
 * Chooses how large a context window the GGUF engine can actually afford.
 *
 * GGUF stores the model's *training* maximum (e.g. 131,072 for Gemma 3), but
 * llama.cpp allocates the KV cache up front for the loaded `n_ctx`. On a phone
 * that number is frequently larger than the whole device's RAM: a Gemma3-4B
 * with q8_0 K/V caches costs ~74 KB per token, so 131k tokens is ~9.2 GB.
 * Loading at that size gets the process killed by lmkd.
 *
 * This budget keeps the declared context as an upper bound and shrinks it to
 * what fits next to the weights/projector with headroom for compute buffers.
 */
object GgufContextBudget {

    /** llama.cpp's practical floor for a usable chat context. */
    const val MIN_CONTEXT = 512

    /** No phone needs more than this; also protects against absurd metadata. */
    const val MAX_CONTEXT = 32_768

    /** Used when KV geometry is missing from the metadata. */
    const val FALLBACK_CONTEXT = 4_096

    /** Context sizes are rounded down to this multiple (KV page alignment). */
    private const val CONTEXT_ALIGNMENT = 256

    /** Reserved for llama.cpp compute buffers, graph, tokenizer and the app. */
    private const val RUNTIME_OVERHEAD_BYTES = 384L * 1024 * 1024

    /** Never plan to use all currently-free RAM; Android needs room to breathe. */
    private const val AVAILABLE_FRACTION_PERCENT = 85L

    /** Hard ceiling relative to physical RAM, regardless of reported free memory. */
    private const val TOTAL_RAM_FRACTION_PERCENT = 55L

    /** q8_0: 32 values stored in 34 bytes (2-byte scale + 32 int8 values). */
    private const val Q8_0_BYTES_PER_ELEMENT = 34.0 / 32.0

    /**
     * Bytes of K+V cache consumed by one token, based on the GGUF geometry.
     * Returns 0 when the metadata lacks enough information.
     */
    fun estimateKvBytesPerToken(metadata: GgufMetadataReader.Metadata?): Long {
        metadata ?: return 0L
        val arch = metadata.architecture ?: return 0L
        val layers = metadata.blockCount ?: return 0L
        val headCount = (metadata.values["$arch.attention.head_count"] as? Number)?.toInt()
        val kvHeads = (metadata.values["$arch.attention.head_count_kv"] as? Number)?.toInt()
            ?: headCount
            ?: return 0L
        val keyLength = (metadata.values["$arch.attention.key_length"] as? Number)?.toInt()
            ?: metadata.embeddingLength?.let { embedding ->
                if (headCount != null && headCount > 0) embedding / headCount else null
            }
            ?: return 0L
        val valueLength = (metadata.values["$arch.attention.value_length"] as? Number)?.toInt()
            ?: keyLength
        val elementsPerToken = layers.toLong() * kvHeads * (keyLength + valueLength)
        return (elementsPerToken * 34L) / 32L
    }

    /**
     * @param modelMaxContext context length declared by the model file.
     * @param requestedTokens what the caller asked for (usually the same value).
     * @param modelBytes size of the GGUF weights actually loaded.
     * @param projectorBytes size of the vision projector, if one will be loaded.
     * @param availableBytes `MemoryInfo.availMem` (free + reclaimable).
     * @param totalRamBytes physical RAM.
     * @param kvBytesPerToken from [estimateKvBytesPerToken].
     */
    fun chooseContextTokens(
        modelMaxContext: Int,
        requestedTokens: Int,
        modelBytes: Long,
        projectorBytes: Long,
        availableBytes: Long,
        totalRamBytes: Long,
        kvBytesPerToken: Long
    ): Int {
        val declaredMax = modelMaxContext.takeIf { it > 0 } ?: MAX_CONTEXT
        val requested = requestedTokens.takeIf { it > 0 } ?: MAX_CONTEXT
        val hardMax = minOf(declaredMax, requested, MAX_CONTEXT)
            .coerceAtLeast(MIN_CONTEXT)

        // Without KV geometry, do not gamble: use a conservative, useful default.
        if (kvBytesPerToken <= 0L) return minOf(hardMax, FALLBACK_CONTEXT)

        val ramBudget = minOf(
            availableBytes * AVAILABLE_FRACTION_PERCENT / 100,
            totalRamBytes * TOTAL_RAM_FRACTION_PERCENT / 100
        )
        // Weights are mmap'd file pages and the projector is loaded too, so both
        // count against the budget even though they are reclaimable in theory.
        val kvBudget = ramBudget - modelBytes - projectorBytes - RUNTIME_OVERHEAD_BYTES
        if (kvBudget <= 0L) return MIN_CONTEXT

        val byRam = (kvBudget / kvBytesPerToken).toInt()
        val aligned = (byRam / CONTEXT_ALIGNMENT) * CONTEXT_ALIGNMENT
        return aligned.coerceIn(MIN_CONTEXT, hardMax)
    }

    /** Convenience: bytes per token for a q8_0 cache (kept for tests/tools). */
    fun q8BytesPerElement(): Double = Q8_0_BYTES_PER_ELEMENT
}
