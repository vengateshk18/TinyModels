package com.example.tinymodels.core.network

import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the user's Hugging Face access token in memory for the network layer.
 *
 * Gated repos (all Gemma litert-community models) reject anonymous downloads
 * with HTTP 401 — a `Authorization: Bearer <token>` header is required even
 * after accepting the license on the model page.
 *
 * The token is persisted in DataStore (see [com.example.tinymodels.data.repository.SettingsRepositoryImpl])
 * and pushed into this holder by the Application class at startup, so blocking
 * call sites (OkHttp workers, HEAD size checks) never need suspend access.
 *
 * Note: OkHttp strips the Authorization header on cross-host redirects, so the
 * token is never leaked to HF's pre-signed CDN URLs.
 */
@Singleton
class HuggingFaceAuth @Inject constructor() {

    @Volatile
    private var token: String? = null

    /** Called by the app-scope collector whenever the stored token changes. */
    fun update(newToken: String?) {
        token = newToken?.trim()?.takeIf { it.isNotBlank() }
    }

    /** The current token, or null when the user hasn't configured one. */
    fun current(): String? = token

    /** Adds the `Authorization: Bearer` header when a token is configured. */
    fun authorize(builder: Request.Builder): Request.Builder {
        val t = token ?: return builder
        return builder.header("Authorization", "Bearer $t")
    }
}
