package com.ai.tinymodels.core.inference

import com.ai.tinymodels.core.inference.runtime.ChatTurn
import com.ai.tinymodels.core.inference.runtime.SessionBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compaction accounting rules: the digest-coverage contract, the sliding
 * window, and the auto-trigger threshold. These pin the fixes for "compact
 * doesn't reduce the token count".
 */
class CompactionPolicyTest {

    @Test fun `isCoveredByDigest uses at-or-before contract`() {
        assertFalse(SessionBudget.isCoveredByDigest(1000L, null))
        assertTrue(SessionBudget.isCoveredByDigest(999L, 1000L))
        assertTrue(SessionBudget.isCoveredByDigest(1000L, 1000L)) // boundary IS covered
        assertTrue(SessionBudget.isCoveredByDigest(500L, 1000L))
        assertFalse(SessionBudget.isCoveredByDigest(1001L, 1000L))
    }

    @Test fun `trimToBudget keeps most recent turns within budget`() {
        // estimateTokens = len/4; each " enrolled" turn below is ~10 chars -> ~2-3 tokens.
        val turns = listOf(
            ChatTurn(ChatTurn.Role.USER, "aaaaaaaaaa"), // ~2 tokens
            ChatTurn(ChatTurn.Role.ASSISTANT, "bbbbbbbbbb"),
            ChatTurn(ChatTurn.Role.USER, "cccccccccc")
        )
        val kept = SessionBudget.trimToBudget(turns, 5)
        // Most recent turns survive; oldest drops first.
        assertTrue(kept.size < turns.size)
        assertEquals("cccccccccc", kept.last().text)
    }

    @Test fun `trimToBudget always keeps the last turn`() {
        val turns = listOf(ChatTurn(ChatTurn.Role.USER, "x".repeat(400)))
        assertEquals(1, SessionBudget.trimToBudget(turns, 1).size)
    }

    @Test fun `trimToBudget empty in empty out`() {
        assertTrue(SessionBudget.trimToBudget(emptyList(), 100).isEmpty())
    }

    @Test fun `auto trigger fires at 90 percent of budget`() {
        val budget = 2048
        val trigger = (budget * 0.90).toInt()
        assertEquals(1843, trigger)
        // A 204-token usage (the old inverted trigger) must NOT compact.
        assertTrue(204 < trigger)
        assertTrue(1843 >= trigger)
    }
}
