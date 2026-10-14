package com.ai.tinymodels.core.network

import java.net.URI

/**
 * Page reading ported from Thinai's `extractReadableText` / `isReadablePage`.
 *
 * A snippet is one line an engine chose; the page is what it actually says.
 */
object PageReader {

    const val PAGE_TEXT_BUDGET = 1500
    const val MAX_PAGE_BYTES = 600_000

    private val deadWeight = Regex(
        """<(script|style|noscript|svg|head|nav|footer|form|aside)\b[^>]*>.*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val unclosedTag = Regex(
        """<(script|style)\b[^>]*>[\s\S]*$""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val codeish = Regex("""[{}\[\];=<>\|\\^~`]""")
    private val paragraph = Regex(
        """<(p|h1|h2|h3|li)\b[^>]*>(.*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    /** Table cells: hourly series, price tables, scorecards live here, not in <p>. */
    private val tableCell = Regex(
        """<td\b[^>]*>(.*?)</td>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    /** Short stat blocks: "Feels like 35° · Low 28°", "25 km/h Wind", "68% Humidity". */
    private val statBlock = Regex(
        """<(div|span)\b[^>]*>(.*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val anyTag = Regex("""<[^>]*>""", RegexOption.DOT_MATCHES_ALL)
    private val whitespace = Regex("""\s+""")
    private val hasDigit = Regex("""\d""")
    /** Units that mark a number as data, not chrome ("Home 24", "Page 2"). */
    private val dataUnit = Regex("""°|%|km/h|m/s|mm\b|AQI|UV|₹|\$|Rs\.?|/""", RegexOption.IGNORE_CASE)
    /** Labels that mark a short block as data even without a query term. */
    private val dataLabel = Regex(
        """\b(temp\w*|feels?|high|low|rain|wind|humid\w*|chance|sunrise|sunset|price|cost|score|AQI|UV index|pollution)\b""",
        RegexOption.IGNORE_CASE
    )
    /** A cell that is only numbers/punctuation ("28.5 °") — joinable into a series. */
    private val numericCell = Regex("""^[\d.,°%\s/+\-~:–—]+$""")
    /**
     * Split value+label stats: `<div class="stat-value">68%</div>
     * <div class="stat-label">Humidity</div>`. Apart they are useless
     * ("68%" is too short, "Humidity" has no digit) — together the answer.
     */
    private val statPair = Regex(
        """<div\b[^>]*class="[^"]*stat-value[^"]*"[^>]*>(.*?)</div>\s*<div\b[^>]*class="[^"]*stat-label[^"]*"[^>]*>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    /** Queries whose answer is numbers, not prose. */
    private val wantsDataQuery = Regex(
        """\b(weather|forecast|temperature|rain|humidity|wind|AQI|air quality|price|prices|cost|rate|rates|score|scores|stock|gold|petrol|diesel|result)\b""",
        RegexOption.IGNORE_CASE
    )

    private val unreadableHosts = setOf(
        "youtube.com", "youtu.be", "facebook.com", "instagram.com",
        "x.com", "twitter.com", "tiktok.com", "pinterest.com",
        "linkedin.com", "reddit.com"
    )

    /** Whether fetching [url] is likely worth a request (not an app shell). */
    fun isReadablePage(url: String): Boolean {
        val host = try {
            URI(url).host?.lowercase() ?: return false
        } catch (_: Exception) {
            return false
        }
        if (host.isEmpty()) return false
        for (blocked in unreadableHosts) {
            if (host == blocked || host.endsWith(".$blocked")) return false
        }
        return true
    }

    internal fun readsAsProse(text: String): Boolean {
        if (text.length < 40) return false
        val codeishCount = codeish.findAll(text).count()
        val letters = text.count { it.code > 64 }
        if (letters < text.length * 0.5) return false
        return codeishCount < text.length * 0.02
    }

    private fun paragraphsAbout(candidates: List<String>, terms: List<String>, limit: Int): List<String> {
        val scores = candidates.map { c ->
            val lower = c.lowercase()
            terms.count { WebSearchQuery.mentions(lower, it) }
        }
        val best = scores.maxOrNull() ?: 0
        val floor = if (best >= 2) 2 else 1
        val order = candidates.indices
            .filter { scores[it] >= floor }
            .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
        val picked = mutableListOf<Int>()
        var length = 0
        for (i in order) {
            val cost = candidates[i].length + 1
            if (picked.isNotEmpty() && length + cost > limit) continue
            picked.add(i)
            length += cost
            if (length >= limit) break
        }
        // Floor starvation fix: once anything names two query words, one-word
        // paragraphs are dropped — but on fact pages the fact itself often
        // names only one ("The temperature today in Chennai is ... 31°C").
        // Fill leftover budget with the best below-floor candidates so facts
        // are not crowded out by generic brand paragraphs.
        if (length < limit) {
            val rest = candidates.indices
                .filter { scores[it] in 1 until floor && it !in picked }
                .sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
            for (i in rest) {
                val cost = candidates[i].length + 1
                if (length + cost > limit) continue
                picked.add(i)
                length += cost
                if (length >= limit) break
            }
        }
        return picked.sorted().map { candidates[it] }
    }

    /** Whether [query] asks for numbers (weather, price, score) rather than prose. */
    fun wantsData(query: String?): Boolean =
        query != null && wantsDataQuery.containsMatchIn(query)

    /**
     * Structured data lines from tables + stat blocks. Weather/price/score
     * pages keep numbers in `<td>` cells and short `<div>`/`<span>` stats,
     * never in `<p>` — without this the reader returns brand fluff.
     * Runs of pure-numeric cells become one series line ("Hourly: 28.5°, …").
     */
    internal fun dataLines(stripped: String, terms: List<String>, limit: Int = 600): List<String> {
        val lines = mutableListOf<String>()
        var spent = 0
        fun offer(line: String) {
            val t = line.trim()
            if (t.isEmpty() || t.length < 4) return
            if (t in lines) return
            if (spent + t.length + 1 > limit) return
            lines.add(t)
            spent += t.length + 1
        }
        // 0. Split value+label pairs first ("68%" + "Humidity").
        for (m in statPair.findAll(stripped)) {
            offer("${textOf(m.groupValues[1])} ${textOf(m.groupValues[2])}".replace(whitespace, " "))
            if (spent >= limit) return lines
        }
        // 1. Stat blocks: short, has a digit, and either a unit or a data
        // label plus a query term — keeps "Feels like 35° · Low 28°" while
        // dropping nav junk like "Home 24".
        for (m in statBlock.findAll(stripped)) {
            val t = textOf(m.groupValues[2])
            if (t.length < 4 || t.length > 160 || !hasDigit.containsMatchIn(t)) continue
            // Skip blocks that are just a heading echo without numbers+units.
            if (!dataUnit.containsMatchIn(t) && !dataLabel.containsMatchIn(t)) continue
            val lower = t.lowercase()
            val mentionsQuery = terms.any { WebSearchQuery.mentions(lower, it) }
            if (!mentionsQuery && !(dataUnit.containsMatchIn(t) && dataLabel.containsMatchIn(t))) continue
            offer(t)
            if (spent >= limit) return lines
        }
        // 2. Table runs: consecutive pure-numeric cells form a series.
        val cells = tableCell.findAll(stripped).map { textOf(it.groupValues[1]) }.toList()
        var run = mutableListOf<String>()
        fun flushRun() {
            if (run.size >= 3) {
                offer("Values: " + run.joinToString(", ").take(220))
            } else {
                run.forEach { offer(it) }
            }
            run = mutableListOf()
        }
        for (c in cells) {
            if (c.isEmpty()) {
                flushRun()
                continue
            }
            if (c.length <= 24 && numericCell.matches(c)) run.add(c) else {
                flushRun()
                // A labelled cell with digits+units ("17% Rain") is data too.
                if (hasDigit.containsMatchIn(c) && c.length <= 120 &&
                    (dataUnit.containsMatchIn(c) || dataLabel.containsMatchIn(c))
                ) {
                    offer(c)
                }
            }
            if (spent >= limit) return lines
        }
        flushRun()
        return lines
    }

    private fun textOf(html: String): String {
        val stripped = html.replace(anyTag, " ")
        var out = WebSearchParser.decodeHtmlEntities(stripped)
        if (stripped.contains("&amp;")) out = WebSearchParser.decodeHtmlEntities(out)
        return out.replace(whitespace, " ").trim()
    }

    /**
     * Turns a page into the prose a reader would see. Budget goes to the
     * paragraphs mentioning [query], emitted in page order. For data queries
     * (weather, price, score) structured numbers lead, prose follows.
     * Falls back to top-of-page, then whole-page-if-prose.
     */
    fun extractReadableText(html: String, limit: Int = PAGE_TEXT_BUDGET, query: String? = null): String {
        if (html.isEmpty()) return ""
        val body = if (html.length > MAX_PAGE_BYTES) html.substring(0, MAX_PAGE_BYTES) else html
        val stripped = body.replace(deadWeight, " ").replace(unclosedTag, " ")
        val candidates = mutableListOf<String>()
        for (m in paragraph.findAll(stripped)) {
            val tag = m.groupValues[1].lowercase()
            val text = textOf(m.groupValues[2])
            val floor = if (tag.startsWith("h")) 12 else 40
            if (text.length < floor) continue
            candidates.add(text)
        }
        val terms = query?.let { WebSearchQuery.queryTerms(it) } ?: emptyList()
        val dataFirst = wantsData(query)
        val dataBudget = if (dataFirst) (limit * 0.45).toInt().coerceAtLeast(300) else 0
        val data = if (dataFirst) dataLines(stripped, terms, dataBudget) else emptyList()
        val proseLimit = limit - data.sumOf { it.length + 1 }.coerceAtMost(limit)
        var parts = if (terms.isEmpty()) emptyList() else paragraphsAbout(candidates, terms, proseLimit)
        if (parts.isEmpty()) {
            parts = mutableListOf<String>().also { out ->
                var length = 0
                for (t in candidates) {
                    out.add(t)
                    length += t.length + 1
                    if (length >= proseLimit) break
                }
            }
        }
        // Numbers first for data queries — the model must see them before prose.
        var text = (data + parts).joinToString("\n")
        if (text.isEmpty()) {
            val whole = textOf(stripped)
            text = if (readsAsProse(whole)) whole else ""
        }
        if (text.length > limit) text = text.substring(0, limit).trimEnd() + "…"
        return text
    }
}
