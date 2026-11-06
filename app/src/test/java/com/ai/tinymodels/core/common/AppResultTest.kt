package com.ai.tinymodels.core.common

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResultTest {

    @Test
    fun `runCatching returns Success when the block succeeds`() = runTest {
        val result = AppResult.runCatching { 42 }
        assertEquals(AppResult.Success(42), result)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `runCatching maps thrown exceptions through the error mapper`() = runTest {
        val result = AppResult.runCatching(errorMapper = { AppError.Unknown("mapped: ${it.message}", it) }) {
            throw IllegalStateException("boom")
        }
        assertTrue(result is AppResult.Error)
        assertEquals("mapped: boom", (result as AppResult.Error).error.message)
    }

    @Test
    fun `runCatching wraps unexpected failures in Unknown by default`() = runTest {
        val result = AppResult.runCatching { throw RuntimeException("x") } as AppResult.Error
        assertTrue(result.error is AppError.Unknown)
        assertEquals("x", result.error.message)
    }

    @Test
    fun `runCatching propagates CancellationException instead of swallowing it`() = runTest {
        var caught: Throwable? = null
        try {
            AppResult.runCatching<Unit> { throw kotlinx.coroutines.CancellationException("bye") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = e
        }
        assertEquals("bye", caught?.message)
    }

    @Test
    fun `map transforms success but passes error through`() {
        val ok: AppResult<Int> = AppResult.success(2)
        assertEquals(4, ok.map { it * 2 }.getOrNull())
        val err: AppResult<Int> = AppResult.error(AppError.NotFound())
        val mapped = err.map { it * 2 }
        assertSame(err, mapped)
    }

    @Test
    fun `onSuccess and onError only fire for their branch`() {
        var successSeen: Int? = null
        var errorSeen: AppError? = null
        AppResult.success(7).onSuccess { successSeen = it }.onError { errorSeen = it }
        assertEquals(7, successSeen)
        assertEquals(null, errorSeen)

        var errorSuccess: Int? = null
        var timeoutSeen: AppError? = null
        AppResult.error(AppError.Timeout()).onSuccess { errorSuccess = it }.onError { timeoutSeen = it }
        assertEquals(null, errorSuccess)
        assertTrue(timeoutSeen is AppError.Timeout)
    }

    @Test
    fun `getOrNull and errorOrNull expose the payload or nothing`() {
        assertEquals("a", AppResult.success("a").getOrNull())
        assertEquals(null, AppResult.error(AppError.Unknown("m")).getOrNull())
        assertEquals("m", AppResult.error(AppError.Unknown("m")).errorOrNull()?.message)
        assertEquals(null, AppResult.success("a").errorOrNull())
    }
}
