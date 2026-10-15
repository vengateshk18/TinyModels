package com.ai.tinymodels.core.inference.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared session bookkeeping: token accounting and the context high-water
 * mark every runtime inherits. A 80%-of-budget policy must behave the same
 * for LiteRT-LM and MediaPipe sessions.
 */
class BaseChatSessionTest {

    private class TestSession(maxContextTokens: Int, initialTokens: Int = 0) :
        BaseChatSession(maxContextTokens, initialTokens) {

        override fun send(text: String, imagePaths: List<String>): Flow<String> = emptyFlow()
        override fun close() = Unit
        override suspend fun summarizeOlderTurns(older: List<ChatTurn>): String? = null

        fun setTokens(tokens: Int) {
            estimatedTokensInContext = tokens
        }

        fun prompt(text: String) = countPrompt(text)
        fun photo() = countImage()
        fun delta(chars: Int) = countResponseDelta(chars)
    }

    @Test fun `initial token estimate is reported`() {
        assertEquals(123, TestSession(1000, initialTokens = 123).estimatedTokensInContext)
    }

    @Test fun `prompt counting uses the shared estimate`() {
        val session = TestSession(1000)
        session.prompt("abcdefgh") // 8 chars -> 2 tokens
        assertEquals(2, session.estimatedTokensInContext)
    }

    @Test fun `photo counting uses the image cost`() {
        val session = TestSession(1000)
        session.photo()
        assertEquals(SessionBudget.IMAGE_TOKEN_COST, session.estimatedTokensInContext)
    }

    @Test fun `response deltas count characters over four`() {
        val session = TestSession(1000)
        session.delta(10)
        session.delta(6)
        assertEquals(3, session.estimatedTokensInContext) // 10/4 + 6/4 = 2 + 1
    }

    @Test fun `high-water mark trips at 80 percent of the budget`() {
        val session = TestSession(100)
        session.setTokens(79)
        assertFalse(session.isContextNearFull)
        session.setTokens(80)
        assertTrue(session.isContextNearFull)
    }

    @Test fun `high-water mark scales with the budget`() {
        val session = TestSession(4096)
        session.setTokens(3276)
        assertFalse(session.isContextNearFull)
        session.setTokens(3277)
        assertTrue(session.isContextNearFull) // 4096 * 0.8 = 3276.8
    }
}
