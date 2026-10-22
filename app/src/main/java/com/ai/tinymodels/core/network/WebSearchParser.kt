package com.ai.tinymodels.core.network

import com.ai.tinymodels.core.common.TinyConstants
import com.ai.tinymodels.domain.model.WebSearchResult
import java.net.URI
import java.util.Base64

/**
 * HTML parsing ported from Thinai's `lib/web/web_search.dart`.
 *
 * Deliberately shape-based, not class-exact: DDG surfaces are read via
 * `result__a / result-link` markers, Bing via heading-shape. A layout tweak
 * upstream should cost a result, not the feature.
 */
object WebSearchParser {

    private val linkTag = Regex(
        """<a\b([^>]*(?:result__a|result-link)[^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val snippetTag = Regex(
        """<(a|td)\b([^>]*(?:result__snippet|result-snippet)[^>]*)>(.*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val hrefAttr = Regex(
        """href\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
        RegexOption.IGNORE_CASE
    )
    private val anyTag = Regex("""<[^>]*>""", RegexOption.DOT_MATCHES_ALL)
    private val whitespace = Regex("""\s+""")

    private val entity = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,9});""")

    internal val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”",
        "percnt" to "%", "copy" to "©", "reg" to "®", "trade" to "™",
        "deg" to "°", "middot" to "·", "bull" to "•", "times" to "×", "rupee" to "₹"
    )

    private val headingLink = Regex(
        """<h[23][^>]*>\s*(?:<[^/>][^>]*>\s*)*<a\b([^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val linkedHeading = Regex(
        """<a\b([^>]*\bhref[^>]*)>\s*(?:<[^/>][^>]*>\s*)*<h[23][^>]*>(.*?)</h[23]>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    // Never across the start of another paragraph (Bing mobile serves unclosed <p).
    private val looseParagraph = Regex(
        """<p\b[^>]*>((?:(?!<p\b).)*?)</p>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val challengeMarkers = Regex(
        """anomaly|captcha|challenge-form|unusual traffic|verify (?:that )?you are (?:a )?human|bots use duckduckgo""",
        RegexOption.IGNORE_CASE
    )

    /** Unknown entities are left alone rather than dropped. */
    fun decodeHtmlEntities(input: String): String {
        return entity.replace(input) { m ->
            val body = m.groupValues[1]
            if (body.startsWith("#")) {
                val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
                val digits = if (hex) body.substring(2) else body.substring(1)
                val code = digits.toIntOrNull(if (hex) 16 else 10)
                if (code == null || code <= 0 || code > 0x10FFFF) m.value
                else String(Character.toChars(code))
            } else {
                namedEntities[body.lowercase()] ?: m.value
            }
        }
    }

    /** Markup to readable text (titles arrive with <b> around matched words). */
    internal fun text(html: String): String {
        val stripped = html.replace(anyTag, " ")
        var out = decodeHtmlEntities(stripped)
        if (stripped.contains("&amp;")) out = decodeHtmlEntities(out)
        return out.replace(whitespace, " ").trim()
    }

    internal fun isHostOrSubdomain(host: String, domain: String): Boolean {
        val h = host.lowercase()
        return h == domain || h.endsWith(".$domain")
    }

    private fun isWeb(uri: URI): Boolean =
        (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrEmpty()

    internal fun decodeBase64Url(value: String): String? {
        return try {
            var padded = value
            val rem = padded.length % 4
            if (rem != 0) padded += "=".repeat(4 - rem)
            String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryParam(uri: URI, name: String): String? {
        val raw = uri.rawQuery ?: return null
        for (part in raw.split("&")) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val k = part.substring(0, eq)
            if (k == name) {
                return try {
                    java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                } catch (_: Exception) {
                    part.substring(eq + 1)
                }
            }
        }
        return null
    }

    private fun parseUri(href: String): URI? {
        return try {
            val u = URI(href)
            // Require absolute http(s) or parseable relative for DDG unwrapping.
            u
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The destination behind a result link. Unwraps DDG `uddg` redirects and
     * Bing `/ck/?u=a1<base64>` trackers. Returns null (drop) when nothing
     * unwraps to an http(s) address.
     */
    internal fun resolveUrl(rawHref: String): String? {
        var href = decodeHtmlEntities(rawHref.trim())
        if (href.isEmpty()) return null
        if (href.startsWith("//")) href = "https:$href"
        if (href.startsWith("/")) href = "${TinyConstants.DUCKDUCKGO_BASE_URL}$href"
        val uri = parseUri(href) ?: return null
        val host = uri.host ?: return null

        if (isHostOrSubdomain(host, "duckduckgo.com")) {
            val target = queryParam(uri, "uddg")
            if (target.isNullOrEmpty()) return null
            val inner = parseUri(target) ?: return null
            if (inner.scheme == null) return null
            return if (isWeb(inner)) inner.toString() else null
        }
        if (isHostOrSubdomain(host, "bing.com") && (uri.path ?: "").startsWith("/ck/")) {
            val target = queryParam(uri, "u") ?: return null
            if (!target.startsWith("a1")) return null
            val decoded = decodeBase64Url(target.substring(2)) ?: return null
            val inner = parseUri(decoded) ?: return null
            return if (isWeb(inner)) inner.toString() else null
        }
        return if (isWeb(uri)) uri.toString() else null
    }

    private fun hrefOf(attrs: String): String? {
        val m = hrefAttr.find(attrs) ?: return null
        return m.groupValues[1].ifEmpty { m.groupValues[2].ifEmpty { m.groupValues[3] } }
            .takeIf { it.isNotEmpty() }
    }

    private fun isEngineOwnLink(uri: URI): Boolean {
        val host = (uri.host ?: "").lowercase()
        return host.contains("duckduckgo.com") || host.contains("bing.com") ||
            host.contains("microsoft.com") || host.contains("microsofttranslator.com") ||
            host.contains("go.microsoft.com")
    }

    /**
     * Reads results out of a DuckDuckGo results page. Links and snippets are
     * found separately and merged by document offset so a missing snippet
     * never steals the next result's.
     */
    fun parseDuckDuckGoHtml(html: String): List<WebSearchResult> {
        if (html.isEmpty()) return emptyList()
        val results = mutableListOf<WebSearchResult>()
        val seen = mutableSetOf<String>()
        val snippets = snippetTag.findAll(html).associate { it.range.first to text(it.groupValues[3]) }
        val snippetStarts = snippets.keys.sorted()
        val links = linkTag.findAll(html).toList()
        for (i in links.indices) {
            val link = links[i]
            val href = hrefOf(link.groupValues[1]) ?: continue
            val url = resolveUrl(href) ?: continue
            if (!seen.add(url)) continue
            val title = text(link.groupValues[2])
            if (title.isEmpty()) continue
            val until = if (i + 1 < links.size) links[i + 1].range.first else html.length
            var snippet = ""
            for (start in snippetStarts) {
                if (start < link.range.last) continue
                if (start >= until) break
                snippet = snippets[start] ?: ""
                break
            }
            results.add(WebSearchResult(title = title, url = url, snippet = snippet))
        }
        return results
    }

    /** Reads results off an engine that marks them as headings (Bing). */
    fun parseHeadingResults(html: String): List<WebSearchResult> {
        if (html.isEmpty()) return emptyList()
        val results = mutableListOf<WebSearchResult>()
        val seen = mutableSetOf<String>()
        val paragraphs = looseParagraph.findAll(html).associate { it.range.first to text(it.groupValues[1]) }
        val paragraphStarts = paragraphs.keys.sorted()
        val links = (headingLink.findAll(html).toList() + linkedHeading.findAll(html).toList())
            .sortedBy { it.range.first }
        for (i in links.indices) {
            val link = links[i]
            val href = hrefOf(link.groupValues[1]) ?: continue
            val url = resolveUrl(href) ?: continue
            val uri = parseUri(url) ?: continue
            if (isEngineOwnLink(uri)) continue
            if (!seen.add(url)) continue
            val title = text(link.groupValues[2])
            if (title.isEmpty()) continue
            val until = if (i + 1 < links.size) links[i + 1].range.first else html.length
            var snippet = ""
            for (start in paragraphStarts) {
                if (start < link.range.last) continue
                if (start >= until) break
                val t = paragraphs[start] ?: ""
                if (t.length < 20) continue
                snippet = t
                break
            }
            results.add(WebSearchResult(title = title, url = url, snippet = snippet))
        }
        return results
    }

    /**
     * Whether an engine answered with a challenge rather than results.
     * 202 is DuckDuckGo's tell. Otherwise only when zero results AND the
     * page says it is a check (a results page mentioning "captcha" in a
     * snippet is still a results page).
     */
    fun isChallengePage(status: Int?, body: String, hadResults: Boolean): Boolean {
        if (status == 202) return true
        if (hadResults) return false
        return challengeMarkers.containsMatchIn(body)
    }
}
