package com.ai.tinymodels.core.network

import java.net.URI

/** Validates URLs before the model's `open_page` or any page read touches them. */
object PageUrl {

    fun isFetchable(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (!uri.userInfo.isNullOrBlank()) return false
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (host.isBlank()) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (host == "::1" || host == "[::1]" || host == "0.0.0.0") return false
        if (isPrivateIpv4(host)) return false
        return true
    }

    fun host(url: String): String? = runCatching { URI(url).host?.lowercase() }.getOrNull()

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return host == "127.0.0.1"
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return a == 10 ||
            a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 169 && b == 254) ||
            a == 0
    }
}
