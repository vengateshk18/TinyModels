package com.ai.tinymodels.core.inference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compaction digest must stay small: it is folded into the system
 * instruction of EVERY later prompt, so an over-long or ever-growing summary
 * is a permanent cost on the context window.
 *
 * These pin the budget (a share of the window, clamped) and the acceptance
 * rules applied to whatever the model actually produced.
 */
class CompactionDigestBudgetTest {

    @Test fun `digest budget is an eighth of the window clamped to 64-256`() {
        // 2048 / 8 = 256
        assertEquals(256, SessionBudget.digestTokenBudget(2048))
        // Large windows never buy a fatter digest.
        assertEquals(256, SessionBudget.digestTokenBudget(32_768))
        // 1024 / 8 = 128
        assertEquals(128, SessionBudget.digestTokenBudget(1024))
        // Tiny windows still get a usable digest floor.
        assertEquals(64, SessionBudget.digestTokenBudget(512))
        assertEquals(64, SessionBudget.digestTokenBudget(100))
    }

    @Test fun `a blank digest means summarization failed`() {
        assertNull(SessionBudget.acceptDigest(null, 256))
        assertNull(SessionBudget.acceptDigest("", 256))
        assertNull(SessionBudget.acceptDigest("   \n\t ", 256))
    }

    @Test fun `a digest within budget passes through trimmed`() {
        assertEquals(
            "User wants a summary.",
            SessionBudget.acceptDigest("  User wants a summary.  ", 256)
        )
    }

    @Test fun `an over-long digest is cut to the budget`() {
        val verbose = "Sentence number one. ".repeat(400) // ~8400 chars → ~2100 tokens
        val digest = SessionBudget.acceptDigest(verbose, 256)!!
        assertTrue(
            "expected <= 256 estimated tokens, got ${SessionBudget.estimateTokens(digest)}",
            SessionBudget.estimateTokens(digest) <= 256
        )
        assertTrue(digest.isNotBlank())
    }

    @Test fun `an over-long digest is cut at a sentence boundary`() {
        val verbose = "Sentence number one. ".repeat(400)
        val digest = SessionBudget.acceptDigest(verbose, 64)!!
        assertTrue("expected a clean sentence end, got: …${digest.takeLast(40)}", digest.endsWith("."))
    }
}
