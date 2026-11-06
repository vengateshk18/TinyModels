package com.ai.tinymodels.core.network

import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.AppError
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

    // ---- friendlyMessageRes ----

    @Test
    fun `NoConnection copy is the offline resource`() {
        assertEquals(
            R.string.error_no_connection,
            NetworkErrorMapper.friendlyMessageRes(AppError.NoConnection())
        )
    }

    @Test
    fun `401 copy points at the access token`() {
        assertEquals(
            R.string.error_gated,
            NetworkErrorMapper.friendlyMessageRes(AppError.Auth(code = 401))
        )
    }

    @Test
    fun `403 copy says access was refused`() {
        assertEquals(
            R.string.error_no_access,
            NetworkErrorMapper.friendlyMessageRes(AppError.Auth(code = 403))
        )
    }

    @Test
    fun `NotFound copy is the not-found resource`() {
        assertEquals(
            R.string.error_not_found,
            NetworkErrorMapper.friendlyMessageRes(AppError.NotFound())
        )
    }

    @Test
    fun `429 copy is the rate-limit resource`() {
        assertEquals(
            R.string.error_too_many_requests,
            NetworkErrorMapper.friendlyMessageRes(AppError.ServerError(code = 429))
        )
    }

    @Test
    fun `5xx copy is the server resource`() {
        assertEquals(
            R.string.error_server,
            NetworkErrorMapper.friendlyMessageRes(AppError.ServerError(code = 500))
        )
    }

    @Test
    fun `Storage copy is the storage resource`() {
        assertEquals(
            R.string.error_storage,
            NetworkErrorMapper.friendlyMessageRes(AppError.Storage())
        )
    }
}