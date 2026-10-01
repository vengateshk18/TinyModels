package com.example.tinymodels.core.common

/**
 * A lightweight Result type that carries a typed error for clean,
 * exhaustive handling across the app. Replaces the legacy utils/Result.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Error(val error: AppError) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data

    fun errorOrNull(): AppError? = (this as? Error)?.error

    fun <R> map(transform: (T) -> R): AppResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    fun onSuccess(block: (T) -> Unit): AppResult<T> {
        if (this is Success) block(data)
        return this
    }

    fun onError(block: (AppError) -> Unit): AppResult<T> {
        if (this is Error) block(error)
        return this
    }

    companion object {
        fun <T> success(data: T): AppResult<T> = Success(data)
        fun error(error: AppError): AppResult<Nothing> = Error(error)

        /** Wrap a suspending block, converting thrown exceptions into typed errors. */
        suspend fun <T> runCatching(
            errorMapper: (Throwable) -> AppError = { AppError.Unknown(it.message, it) },
            block: suspend () -> T
        ): AppResult<T> = try {
            Success(block())
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Error(errorMapper(throwable))
        }
    }
}

/**
 * Typed, user-presentable errors. Each feature can map these to friendly copy.
 */
sealed class AppError(open val message: String?) {
    data class Network(override val message: String?) : AppError(message)
    data class NotFound(override val message: String?) : AppError(message)
    data class Storage(override val message: String?) : AppError(message)
    data class Unknown(override val message: String?, val cause: Throwable? = null) : AppError(message)
}

/**
 * Inference-specific errors, kept separate so the chat layer can present
 * actionable guidance (e.g. "close other apps" for OOM).
 */
sealed class InferenceError(open val message: String?) {
    data class OutOfMemory(override val message: String? = "Not enough free memory to load this model") :
        InferenceError(message)

    data class ModelFileMissing(override val message: String? = "Model file not found on device") :
        InferenceError(message)

    data class BackendUnavailable(override val message: String? = "No supported inference backend available") :
        InferenceError(message)

    data class LoadFailed(override val message: String?, val cause: Throwable? = null) : InferenceError(message)

    data class GenerationFailed(override val message: String?, val cause: Throwable? = null) :
        InferenceError(message)

    object NoModelLoaded : InferenceError("No model is loaded")
}