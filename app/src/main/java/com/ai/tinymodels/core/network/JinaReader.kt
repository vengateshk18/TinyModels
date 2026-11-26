package com.ai.tinymodels.core.network

/** Minimal r.jina.ai reader fallback for JS-heavy/unreadable pages. */
object JinaReader {

    fun urlFor(pageUrl: String): String = "https://r.jina.ai/${pageUrl.trim()}"

    fun cleanMarkdown(raw: String, limit: Int): String {
        if (raw.isBlank()) return ""
        val kept = ArrayList<String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (kept.isNotEmpty() && kept.last().isNotEmpty()) kept.add("")
                continue
            }
            if (trimmed.startsWith("Title:", ignoreCase = true)) continue
            if (trimmed.startsWith("URL Source:", ignoreCase = true)) continue
            if (trimmed.startsWith("Published Time:", ignoreCase = true)) continue
            if (trimmed.startsWith("Markdown Content:", ignoreCase = true)) continue
            if (trimmed.matches(Regex("""^!\[.*]\(.*\)$"""))) continue
            kept.add(trimmed)
        }
        val joined = kept.joinToString("\n").trim()
        if (joined.length <= limit) return joined
        return joined.substring(0, limit).trimEnd() + "…"
    }
}
