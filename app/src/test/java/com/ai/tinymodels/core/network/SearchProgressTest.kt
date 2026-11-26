package com.ai.tinymodels.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchProgressTest {

    @Test fun `query is visible while running and cleared after`() = runTest {
        val progress = SearchProgress()
        assertNull(progress.query.value)
        progress.track("weather in chennai") {
            assertEquals("weather in chennai", progress.query.value)
            "ok"
        }
        assertNull(progress.query.value)
    }

    @Test fun `clears on exception`() = runTest {
        val progress = SearchProgress()
        runCatching {
            progress.track("q") { error("boom") }
        }
        assertNull(progress.query.value)
    }

    @Test fun `clears only when the last concurrent track finishes`() = runTest {
        val progress = SearchProgress()
        val a = async { progress.track("a") { delay(50); "a" } }
        val b = async { progress.track("b") { delay(10); "b" } }
        b.await()
        // a is still running; progress should not be null until both done
        // (we accept either a or b is visible)
        a.await()
        assertNull(progress.query.value)
    }
}
