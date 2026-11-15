package com.ai.tinymodels.core.inference.gguf

import com.ai.tinymodels.core.inference.runtime.ChatTurn
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GGUF compaction. The vendored engine exposes its own summarize call
 * (`GGMLEngine.compact`), so GGUF should produce a real digest instead of
 * silently falling back to a trim.
 *
 * [GgufCompaction] holds the policy — transcript, token cap, success check,
 * digest acceptance — so it is testable without the native engine.
 */
class GgufCompactionTest {

    private val older = listOf(
        ChatTurn(ChatTurn.Role.USER, "hi"),
        ChatTurn(ChatTurn.Role.ASSISTANT, "hello")
    )

    @Test fun `summarizes older turns through the engine`() = runTest {
        var transcriptSent = ""
        var capSent = -1

        val digest = GgufCompaction.digest(older, maxContextTokens = 2048) { transcript, cap ->
            transcriptSent = transcript
            capSent = cap
            GgufDigestAttempt("User said hi, assistant said hello.", success = true)
        }

        assertEquals("User said hi, assistant said hello.", digest)
        assertEquals(256, capSent)
        assertTrue(transcriptSent.contains("User: hi"))
        assertTrue(transcriptSent.contains("Assistant: hello"))
    }

    @Test fun `the engine token cap follows the context window`() = runTest {
        var capSent = -1

        GgufCompaction.digest(older, maxContextTokens = 1024) { _, cap ->
            capSent = cap
            GgufDigestAttempt("short", success = true)
        }

        assertEquals(128, capSent)
    }

    @Test fun `an engine failure yields no digest`() = runTest {
        val digest = GgufCompaction.digest(older, maxContextTokens = 2048) { _, _ ->
            GgufDigestAttempt(null, success = false)
        }

        assertNull(digest)
    }

    @Test fun `an empty history never calls the engine`() = runTest {
        var called = false

        val digest = GgufCompaction.digest(emptyList(), maxContextTokens = 2048) { _, _ ->
            called = true
            GgufDigestAttempt("should not happen", success = true)
        }

        assertNull(digest)
        assertTrue("engine must not be invoked for an empty history", !called)
    }

    @Test fun `an over-long engine summary is capped`() = runTest {
        val digest = GgufCompaction.digest(older, maxContextTokens = 2048) { _, _ ->
            GgufDigestAttempt("Sentence number one. ".repeat(400), success = true)
        }!!

        assertTrue(
            "digest must respect the window budget",
            com.ai.tinymodels.core.inference.runtime.SessionBudget.estimateTokens(digest) <= 256
        )
    }
}
