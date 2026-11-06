package com.ai.tinymodels.core.network

import androidx.annotation.StringRes
import com.ai.tinymodels.R
import com.ai.tinymodels.core.common.AppError
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * HTTP-status failure thrown by [HuggingFaceApi]. Carries the status code so
 * [NetworkErrorMapper] can branch on it (auth vs not-found vs server error).
 * The message keeps the "HTTP <code>" prefix — the download worker and some
 * UI heuristics match on it.
 */
class HttpException(val code: Int, message: String) : IOException(message)

/**
 * Maps raw [Throwable]s from the network layer to typed, user-presentable
 * [AppError]s, and provides the canonical friendly copy for each error kind.
 * Screens should never print raw exception text.
 */
object NetworkErrorMapper {

    /** Raw throwable → typed error. */
    fun toAppError(throwable: Throwable): AppError = when (throwable) {
        is HttpException -> when (throwable.code) {
            401, 403 -> AppError.Auth(code = throwable.code)
            404 -> AppError.NotFound()
            429 -> AppError.ServerError(code = throwable.code)
            in 500..599 -> AppError.ServerError(code = throwable.code)
            else -> AppError.Network(null)
        }
        is UnknownHostException -> AppError.NoConnection()
        is ConnectException -> AppError.NoConnection()
        is SocketTimeoutException -> AppError.Timeout()
        else -> AppError.Network(null)
    }

    /** Typed error → string resource with canonical, localized user-facing copy. */
    @StringRes
    fun friendlyMessageRes(error: AppError): Int = when (error) {
        is AppError.NoConnection -> R.string.error_no_connection
        is AppError.Timeout -> R.string.error_timeout
        is AppError.Auth ->
            if (error.code == 403) R.string.error_no_access else R.string.error_gated
        is AppError.ServerError ->
            if (error.code == 429) R.string.error_too_many_requests else R.string.error_server
        is AppError.NotFound -> R.string.error_not_found
        is AppError.Network -> R.string.error_network
        is AppError.Storage -> R.string.error_storage
        is AppError.Unknown -> R.string.error_unknown
    }
}