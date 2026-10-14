package com.ai.tinymodels.core.network

import com.ai.tinymodels.domain.model.WebSearchResult
import com.ai.tinymodels.domain.model.WebSearchResults
import com.ai.tinymodels.domain.repository.WebSearchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keyless web-search orchestrator, ported from Thinai's `WebSearch` class.
 *
 * Three HTML endpoints in fallback order (no API key, nothing to configure):
 *  1. DDG Lite (POST) — same index, fraction of the markup.
 *  2. DDG HTML (POST) — full surface.
 *  3. Bing (GET, keywords + desktop UA) — different index for when DDG
 *     answers with a challenge page instead of results.
 *
 * Returns [WebSearchResults] distinguishing empty ("nothing found") from
 * blocked (challenged everywhere) from failure (throws [WebSearchException]).
 */
@Singleton
class WebSearchClient @Inject constructor() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Overridable in tests (fake engines / frozen clock). */
    internal var endpoints: List<SearchEndpoint> = WebSearchEndpoints.DEFAULT
    internal var nowMillis: () -> Long = System::currentTimeMillis

    /**
     * Test seam: (endpoint, sentQuery) -> (httpCode, body). Defaults to real
     * HTTP; tests replace it with canned pages so no network is needed.
     */
    internal var fetcher: (SearchEndpoint, String) -> Pair<Int?, String> =
        { endpoint, sent -> httpAsk(endpoint, sent) }

    /** Engines resting after a refusal, by URL, until epoch-ms. */
    private val restingUntil = mutableMapOf<String, Long>()

    companion object {
        /** How long an engine that turned us away is left alone. */
        const val COOLDOWN_MS = 10 * 60 * 1000L
    }

    /**
     * Searches the web for [query]. All network work runs on IO.
     * Returns at most [maxResults] relevant results; empty when the web
     * genuinely has nothing to say.
     * @throws WebSearchException when no engine could be reached at all.
     */
    suspend fun search(
        query: String,
        maxResults: Int = WebSearchPrompt.MAX_WEB_RESULTS,
        readTopResult: Boolean = true
    ): WebSearchResults = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) throw WebSearchException("There is nothing to search for.")

        var failure: Exception? = null
        var reached = 0
        var blocked = 0

        val now = nowMillis()
        val awake = endpoints.filter { (restingUntil[it.url] ?: 0L) <= now }
        val asking = if (awake.isEmpty()) endpoints else awake

        for (endpoint in asking) {
            try {
                val sent = if (endpoint.keywords) WebSearchQuery.keywordQuery(trimmed) else trimmed
                val (code, body) = fetcher(endpoint, sent)
                reached++
                val parsed = try {
                    endpoint.parse(body)
                } catch (e: Exception) {
                    // One engine's layout changing must not kill the search.
                    failure = e
                    continue
                }
                if (WebSearchParser.isChallengePage(code, body, parsed.isNotEmpty())) {
                    blocked++
                    rest(endpoint)
                    continue
                }
                restingUntil.remove(endpoint.url)

                var results = WebSearchFilter.keepRelevant(parsed, trimmed)

                // Keyword engines get one retry with reordered words when
                // what came back is off the point.
                val retry = if (endpoint.keywords) WebSearchQuery.retryKeywordQuery(sent) else null
                if (retry != null && parsed.isNotEmpty() &&
                    (results.isEmpty() || !WebSearchFilter.anyCoversAll(results, trimmed))
                ) {
                    try {
                        val (_, secondBody) = fetcher(endpoint, retry)
                        val again = WebSearchFilter.keepRelevant(endpoint.parse(secondBody), trimmed)
                        if (again.isNotEmpty() &&
                            (results.isEmpty() || WebSearchFilter.anyCoversAll(again, trimmed))
                        ) {
                            results = again
                        }
                    } catch (_: Exception) {
                        // First answer still stands.
                    }
                }

                if (results.isNotEmpty()) {
                    val kept = results.take(maxResults).map { it.withQuery(trimmed) }
                    val lead = if (readTopResult && PageReader.isReadablePage(kept.first().url)) {
                        readPage(kept.first().url, trimmed)
                    } else ""
                    return@withContext WebSearchResults(
                        query = trimmed,
                        results = kept,
                        fetchedAt = nowMillis(),
                        leadText = lead
                    )
                }
            } catch (e: IOException) {
                failure = e
                if (isRefusal(e, null)) rest(endpoint)
            } catch (e: Exception) {
                failure = e
            }
        }

        if (reached == 0 && failure != null) {
            throw WebSearchException(describeFailure(failure!!))
        }
        return@withContext WebSearchResults(
            query = trimmed,
            results = emptyList(),
            fetchedAt = nowMillis(),
            blocked = reached > 0 && blocked == reached
        )
    }

    /** Returns (HTTP code or null on network failure, body or ""). Never throws for HTTP codes. */
    private fun httpAsk(endpoint: SearchEndpoint, sent: String): Pair<Int?, String> {
        val headers = WebSearchEndpoints.headersFor(endpoint)
        val request = if (endpoint.post) {
            val form = FormBody.Builder().add("q", sent).add("kl", "wt-wt").build()
            val builder = Request.Builder().url(endpoint.url).post(form)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.build()
        } else {
            val httpUrl = endpoint.url.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("q", sent)?.build()
                ?: throw IOException("Bad search URL")
            val builder = Request.Builder().url(httpUrl).get()
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.build()
        }
        client.newCall(request).execute().use { response ->
            val code = response.code
            val body = try {
                response.body?.string() ?: ""
            } catch (_: Exception) {
                ""
            }
            if (code == 403 || code == 429) restByUrl(endpoint.url)
            return code to body
        }
    }

    /** Reads prose off a page; never throws ("" on any failure). */
    internal fun readPage(url: String, query: String?): String {
        return try {
            val builder = Request.Builder().url(url).get()
                .header("User-Agent", WebSearchEndpoints.MOBILE_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return ""
                val body = response.body?.string() ?: return ""
                PageReader.extractReadableText(body, query = query)
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun rest(endpoint: SearchEndpoint) = restByUrl(endpoint.url)

    private fun restByUrl(url: String) {
        restingUntil[url] = nowMillis() + COOLDOWN_MS
    }

    /** Test-only: clear cooldowns. */
    internal fun clearCooldowns() = restingUntil.clear()

    private fun isRefusal(e: IOException, code: Int?): Boolean {
        if (e is SocketTimeoutException) return true
        return code == 403 || code == 429
    }

    internal fun describeFailure(e: Exception): String {
        if (e is UnknownHostException) return "Web search could not reach the internet."
        val msg = (e.message ?: "").lowercase()
        if (e is SocketTimeoutException || "timeout" in msg) {
            return if ("connect" in msg) "Web search could not reach the internet."
            else "Web search timed out."
        }
        if (e is IOException) return "Web search could not reach the internet."
        return "Web search failed."
    }

    // ---- Legacy JSON tiers (kept for WebSearchClientTest; no longer used) ----

    /** Tier 1 (legacy): DuckDuckGo Instant Answer API. */
    @Deprecated("Replaced by HTML fallback chain; kept for tests")
    internal fun parseDuckDuckGoResults(body: String, query: String, maxResults: Int): List<WebSearchResult> {
        val results = mutableListOf<WebSearchResult>()
        try {
            val root = JSONObject(body)
            val abstractText = root.optString("AbstractText").takeIf { it.isNotBlank() }
            val abstractUrl = root.optString("AbstractURL").takeIf { it.isNotBlank() }
            if (abstractText != null) {
                results += WebSearchResult(
                    title = root.optString("Heading").takeIf { it.isNotBlank() } ?: query,
                    url = abstractUrl ?: "https://duckduckgo.com/?q=${URLEncoder.encode(query, Charsets.UTF_8.name())}",
                    snippet = abstractText
                )
            }
            val related = root.optJSONArray("RelatedTopics") ?: JSONArray()
            for (i in 0 until related.length()) {
                if (results.size >= maxResults) break
                val topic = related.optJSONObject(i) ?: continue
                val text = topic.optString("Text").takeIf { it.isNotBlank() } ?: continue
                val url = topic.optString("FirstURL").takeIf { it.isNotBlank() } ?: continue
                val title = text.substringBefore(" - ").trim().take(80)
                val snippet = text.substringAfter(" - ", "").trim().ifBlank { text }
                results += WebSearchResult(title = title, url = url, snippet = snippet)
            }
        } catch (e: JSONException) {
            throw WebSearchException("Could not parse search results", e)
        }
        return results.take(maxResults)
    }

    /** Tier 2 (legacy): Wikipedia full-text search API. */
    @Deprecated("Replaced by HTML fallback chain; kept for tests")
    internal fun parseWikipediaResults(body: String, maxResults: Int): List<WebSearchResult> {
        try {
            val root = JSONObject(body)
            val search = root.optJSONObject("query")?.optJSONArray("search") ?: JSONArray()
            val results = mutableListOf<WebSearchResult>()
            for (i in 0 until search.length()) {
                if (results.size >= maxResults) break
                val hit = search.optJSONObject(i) ?: continue
                val title = hit.optString("title").takeIf { it.isNotBlank() } ?: continue
                val snippet = stripHtml(hit.optString("snippet")).trim()
                if (snippet.isBlank()) continue
                results += WebSearchResult(
                    title = title,
                    url = "https://en.wikipedia.org/wiki/" +
                        URLEncoder.encode(title.replace(' ', '_'), Charsets.UTF_8.name()),
                    snippet = snippet
                )
            }
            return results
        } catch (e: JSONException) {
            throw WebSearchException("Could not parse search results", e)
        }
    }

    private fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]*>"), "")
}
