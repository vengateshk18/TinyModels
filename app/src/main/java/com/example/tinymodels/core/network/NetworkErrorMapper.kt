package com.example.tinymodels.core.network

import com.example.tinymodels.core.common.AppError
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
            401, 403 -> AppError.Auth(gatedMessage(throwable.code))
            404 -> AppError.NotFound()
            429 -> AppError.ServerError(
                "Too many requests. Wait a moment and try again.",
                throwable.code
            )
            in 500..599 -> AppError.ServerError(
                "Hugging Face is having trouble right now. Try again later.",
                throwable.code
            )
            else -> AppError.Network(throwable.message)
        }
        is UnknownHostException -> AppError.NoConnection()
        is ConnectException -> AppError.NoConnection()
        is SocketTimeoutException -> AppError.Timeout()
        else -> AppError.Network(throwable.message)
    }

    /** Typed error → canonical user-facing copy. */
    fun friendlyMessage(error: AppError): String = when (error) {
        is AppError.NoConnection ->
            error.message ?: "You're offline. Check your internet connection and try again."
        is AppError.Timeout ->
            error.message ?: "Connection timed out. Try again."
        is AppError.Auth ->
            error.message ?: "This model is gated. Add a Hugging Face access token in Settings."
        is AppError.ServerError ->
            error.message ?: "Something went wrong. Try again in a moment."
        is AppError.NotFound ->
            error.message ?: "Model not found. It may have been removed from Hugging Face."
        is AppError.Network ->
            error.message ?: "Network error. Check your connection and try again."
        is AppError.Storage ->
            error.message ?: "Not enough free storage."
        is AppError.Unknown ->
            error.message ?: "Something went wrong. Try again."
    }

    private fun gatedMessage(code: Int) = when (code) {
        401 -> "This model is gated. Add a Hugging Face access token in Settings, " +
            "or accept its license on huggingface.co first."
        else -> "You don't have access to this model. It may be gated — " +
            "check its license on huggingface.co."
    }
}