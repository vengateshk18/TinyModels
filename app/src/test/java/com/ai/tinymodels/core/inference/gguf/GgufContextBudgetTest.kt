package com.ai.tinymodels.core.inference.gguf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufContextBudgetTest {

    private val mb = 1024L * 1024L

    private fun gemma3Metadata() = GgufMetadataReader.Metadata(
        magic = 0x46554747,
        version = 3,
        tensorCount = 445,
        kvCount = 44,
        values = mapOf(
            "general.architecture" to "gemma3",
            "gemma3.context_length" to 131_072,
            "gemma3.embedding_length" to 2560,
            "gemma3.block_count" to 34,
            "gemma3.attention.head_count" to 8,
            "gemma3.attention.head_count_kv" to 4,
            "gemma3.attention.key_length" to 256,
            "gemma3.attention.value_length" to 256,
        )
    )

    @Test
    fun `estimates q8 KV cost per token from GGUF geometry`() {
        // 34 layers * 4 KV heads * (256 + 256) elements * 34/32 bytes
        assertEquals(73_984L, GgufContextBudget.estimateKvBytesPerToken(gemma3Metadata()))
    }

    @Test
    fun `missing geometry falls back conservatively`() {
        assertEquals(0L, GgufContextBudget.estimateKvBytesPerToken(null))
        assertEquals(
            GgufContextBudget.FALLBACK_CONTEXT,
            GgufContextBudget.chooseContextTokens(
                modelMaxContext = 131_072,
                requestedTokens = 131_072,
                modelBytes = 1_400L * mb,
                projectorBytes = 0L,
                availableBytes = 4_000L * mb,
                totalRamBytes = 8_000L * mb,
                kvBytesPerToken = 0L
            )
        )
    }

    @Test
    fun `caps the medra gemma3 vision load to what fits in RAM`() {
        val chosen = GgufContextBudget.chooseContextTokens(
            modelMaxContext = 131_072,
            requestedTokens = 131_072,
            modelBytes = 1_354L * mb,
            projectorBytes = 811L * mb,
            availableBytes = 4_149L * mb,
            totalRamBytes = 7_385L * mb,
            kvBytesPerToken = 73_984L
        )

        assertTrue("context should be aligned", chosen % 256 == 0)
        assertTrue("must keep a usable context", chosen >= GgufContextBudget.MIN_CONTEXT)
        assertTrue("must not use the model's 131k maximum", chosen < 131_072)
        // KV + weights + projector + overhead must fit inside 85% of free RAM.
        val ramBudget = 4_149L * mb * 85 / 100
        val planned = 1_354L * mb + 811L * mb + chosen * 73_984L + 384L * mb
        assertTrue("planned footprint $planned exceeds budget $ramBudget", planned <= ramBudget)
    }

    @Test
    fun `honours a lower caller request`() {
        val chosen = GgufContextBudget.chooseContextTokens(
            modelMaxContext = 131_072,
            requestedTokens = 4_096,
            modelBytes = 400L * mb,
            projectorBytes = 0L,
            availableBytes = 6_000L * mb,
            totalRamBytes = 8_000L * mb,
            kvBytesPerToken = 20_000L
        )
        assertEquals(4_096, chosen)
    }

    @Test
    fun `never exceeds the hard ceiling`() {
        val chosen = GgufContextBudget.chooseContextTokens(
            modelMaxContext = 131_072,
            requestedTokens = 131_072,
            modelBytes = 100L * mb,
            projectorBytes = 0L,
            availableBytes = 32_000L * mb,
            totalRamBytes = 32_000L * mb,
            kvBytesPerToken = 1_024L
        )
        assertEquals(GgufContextBudget.MAX_CONTEXT, chosen)
    }

    @Test
    fun `falls back to minimum context when nothing fits`() {
        val chosen = GgufContextBudget.chooseContextTokens(
            modelMaxContext = 131_072,
            requestedTokens = 131_072,
            modelBytes = 6_000L * mb,
            projectorBytes = 1_000L * mb,
            availableBytes = 3_000L * mb,
            totalRamBytes = 7_000L * mb,
            kvBytesPerToken = 73_984L
        )
        assertEquals(GgufContextBudget.MIN_CONTEXT, chosen)
    }
}
