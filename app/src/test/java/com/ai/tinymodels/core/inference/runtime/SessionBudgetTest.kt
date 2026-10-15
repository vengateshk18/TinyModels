package com.ai.tinymodels.core.inference.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Session budgeting rules shared by every runtime: the token estimate, the
 * digest-coverage contract, and the system-instruction fold (system prompt +
 * compaction digest) that both LiteRT-LM and MediaPipe feed to the model.
 *
 * The trim/window cases stay pinned in [com.ai.tinymodels.core.inference.CompactionPolicyTest].
 */
class SessionBudgetTest {

    @Test fun `estimateTokens is length over four`() {
        assertEquals(2, SessionBudget.estimateTokens("abcdefgh"))
        assertEquals(25, SessionBudget.estimateTokens("x".repeat(100)))
    }

    @Test fun `estimateTokens never returns zero`() {
        assertEquals(1, SessionBudget.estimateTokens(""))
        assertEquals(1, SessionBudget.estimateTokens("abc"))
    }

    @Test fun `fold with neither system nor summary is null`() {
        assertNull(SessionBudget.foldSystemInstruction(null, null))
    }

    @Test fun `fold with blank system is null`() {
        assertNull(SessionBudget.foldSystemInstruction("", null))
        assertNull(SessionBudget.foldSystemInstruction("   ", null))
    }

    @Test fun `fold with blank summary is null`() {
        assertNull(SessionBudget.foldSystemInstruction(null, ""))
        assertNull(SessionBudget.foldSystemInstruction(null, "   "))
    }

    @Test fun `fold drops a blank summary but keeps a real system instruction`() {
        assertEquals("sys", SessionBudget.foldSystemInstruction("sys", "  "))
    }

    @Test fun `fold passes the system instruction through`() {
        assertEquals("You are helpful.", SessionBudget.foldSystemInstruction("You are helpful.", null))
    }

    @Test fun `fold carries the summary alone when there is no system`() {
        assertEquals(
            "Conversation so far (summary of earlier messages):\ndigest",
            SessionBudget.foldSystemInstruction(null, "digest")
        )
    }

    @Test fun `fold appends the summary after the system instruction`() {
        assertEquals(
            "You are helpful.\n\nConversation so far (summary of earlier messages):\ndigest",
            SessionBudget.foldSystemInstruction("You are helpful.", "digest")
        )
    }

    @Test fun `fold drops a blank system but keeps the summary`() {
        assertEquals(
            "Conversation so far (summary of earlier messages):\ndigest",
            SessionBudget.foldSystemInstruction("   ", "digest")
        )
    }
}
