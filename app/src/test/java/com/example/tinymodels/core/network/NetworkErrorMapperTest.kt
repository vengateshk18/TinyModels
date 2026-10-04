package com.example.tinymodels.core.network

import com.example.tinymodels.core.common.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Verifies raw network throwables map to typed [AppError]s with friendly,
 * user-presentable copy — never raw exception text.
 */
class NetworkErrorMapperTest {

    // ---- toAppError ----

    @Test
    fun `UnknownHostException maps to NoConnection`() {
        val error = NetworkErrorMapper.toAppError(UnknownHostException("api.huggingface.co"))
        assertTrue(error is AppError.NoConnection)
    }

    @Test
    fun `ConnectException maps to NoConnection`() {
        val error = NetworkErrorMapper.toAppError(ConnectException("refused"))
        assertTrue(error is AppError.NoConnection)
    }

    @Test
    fun `SocketTimeoutException maps to Timeout`() {
        val error = NetworkErrorMapper.toAppError(SocketTimeoutException("timeout"))
        assertTrue(error is AppError.Timeout)
    }

    @Test
    fun `HTTP 401 maps to Auth`() {
        val error = NetworkErrorMapper.toAppError(HttpException(401, "HTTP 401: Unauthorized"))
        assertTrue(error is AppError.Auth)
    }

    @Test
    fun `HTTP 403 maps to Auth`() {
        val error = NetworkErrorMapper.toAppError(HttpException(403, "HTTP 403: Forbidden"))
        assertTrue(error is AppError.Auth)
    }

    @Test
    fun `HTTP 404 maps to NotFound`() {
        val error = NetworkErrorMapper.toAppError(HttpException(404, "HTTP 404: Not Found"))
        assertTrue(error is AppError.NotFound)
    }

    @Test
    fun `HTTP 429 maps to ServerError`() {
        val error = NetworkErrorMapper.toAppError(HttpException(429, "HTTP 429"))
        assertTrue(error is AppError.ServerError)
        assertEquals(429, (error as AppError.ServerError).code)
    }

    @Test
    fun `HTTP 500 maps to ServerError`() {
        val error = NetworkErrorMapper.toAppError(HttpException(500, "HTTP 500"))
        assertTrue(error is AppError.ServerError)
        assertEquals(500, (error as AppError.ServerError).code)
    }

    @Test
    fun `other IOException maps to Network`() {
        val error = NetworkErrorMapper.toAppError(java.io.IOException("connection reset"))
        assertTrue(error is AppError.Network)
    }

    // ---- friendlyMessage ----

    @Test
    fun `NoConnection copy mentions being offline`() {
        val message = NetworkErrorMapper.friendlyMessage(AppError.NoConnection())
        assertTrue(message.contains("offline", ignoreCase = true))
    }

    @Test
    fun `Auth copy mentions the token`() {
        val message = NetworkErrorMapper.friendlyMessage(AppError.Auth())
        assertTrue(message.contains("token", ignoreCase = true))
    }

    @Test
    fun `NotFound copy mentions the model`() {
        val message = NetworkErrorMapper.friendlyMessage(AppError.NotFound())
        assertTrue(message.contains("not found", ignoreCase = true))
    }

    @Test
    fun `custom message overrides default copy`() {
        val message = NetworkErrorMapper.friendlyMessage(AppError.Network("custom reason"))
        assertEquals("custom reason", message)
    }
}