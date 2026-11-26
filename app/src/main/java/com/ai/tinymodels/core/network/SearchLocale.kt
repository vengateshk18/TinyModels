package com.ai.tinymodels.core.network

import java.util.Locale

/** Locale hints for keyless engines. */
object SearchLocale {
    private val DDG_KL = mapOf(
        "en-us" to "us-en",
        "en-gb" to "uk-en",
        "en-in" to "in-en",
        "en-au" to "au-en",
        "en-ca" to "ca-en",
        "de-de" to "de-de",
        "fr-fr" to "fr-fr",
        "es-es" to "es-es",
        "it-it" to "it-it",
        "ja-jp" to "jp-jp",
        "ko-kr" to "kr-kr",
        "pt-br" to "br-pt",
        "ru-ru" to "ru-ru"
    )

    fun ddgKl(languageTag: String): String {
        val normalized = languageTag.trim().replace('_', '-').lowercase(Locale.ROOT)
        if (normalized.isBlank()) return "wt-wt"
        return DDG_KL[normalized] ?: "wt-wt"
    }

    fun acceptLanguage(languageTag: String): String {
        val tag = languageTag.trim().ifBlank { "en-US" }
        val primary = tag.substringBefore('-').lowercase(Locale.ROOT)
        return if (primary == "en") "$tag,en;q=0.9" else "$tag,$primary;q=0.9,en;q=0.8"
    }
}
