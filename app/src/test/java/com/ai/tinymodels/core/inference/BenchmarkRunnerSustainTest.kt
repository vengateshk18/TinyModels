package com.ai.tinymodels.core.inference

import com.ai.tinymodels.core.inference.runtime.ChatSession
import com.ai.tinymodels.core.inference.runtime.ChatTurn
import com.ai.tinymodels.core.inference.runtime.SessionRequest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sustained (thermal) phase of the benchmark: repeats the medium prompt
 * and returns the decode speed of each run, in order.
 */
class BenchmarkRunnerSustainTest {

    private val modelManager = mockk<ModelManager>(relaxed = true)
    private val runner = BenchmarkRunner(modelManager)

    private fun fakeSession(): ChatSession {
        val session = mockk<ChatSession>(relaxed = true)
        every { session.estimatedTokensInContext } returns 0
        every { session.isContextNearFull } returns false
        coEvery { session.send(any(), any()) } returns flow {
            emit("one")
            delay(2)
            emit("one two")
            delay(2)
            emit("one two three")
        }
        coEvery { session.summarizeOlderTurns(any()) } returns null
        every { session.close() } returns Unit
        return session
    }

    private suspend fun stubSession(session: ChatSession) {
        coEvery {
            modelManager.withSession<RunMetrics>(any<SessionRequest>(), any<suspend (ChatSession) -> RunMetrics>())
        } coAnswers {
            secondArg<suspend (ChatSession) -> RunMetrics>().invoke(session)
        }
    }

    @Test
    fun `runSustained returns at least one decode speed sample`() = runTest {
        stubSession(fakeSession())
        val samples = runner.runSustained(durationMs = 150)
        assertTrue("expected at least one sustained sample, got $samples", samples.isNotEmpty())
        assertTrue(
            "samples must be positive decode speeds, got $samples",
            samples.all { it > 0.0 }
        )
    }

    @Test
    fun `runSustained reports progress with non-negative elapsed times`() = runTest {
        stubSession(fakeSession())
        val events = mutableListOf<Pair<Long, Double>>()
        runner.runSustained(durationMs = 150) { elapsedMs, tps -> events += elapsedMs to tps }
        assertTrue("expected progress events, got $events", events.isNotEmpty())
        assertTrue("elapsed times must not be negative, got $events", events.all { it.first >= 0 })
        assertTrue("speeds must be positive, got $events", events.all { it.second > 0.0 })
    }

    @Test
    fun `runSustained stops after the duration elapses instead of running forever`() = runTest {
        stubSession(fakeSession())
        val started = System.nanoTime()
        runner.runSustained(durationMs = 120)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // Loop must exit promptly even if sessions complete instantly.
        assertTrue("sustained loop ran too long: $elapsedMs ms", elapsedMs < 5_000)
    }
}
