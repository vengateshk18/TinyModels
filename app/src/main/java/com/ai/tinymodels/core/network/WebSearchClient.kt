package com.ai.tinymodels.core.network

import com.ai.tinymodels.core.common.TinyConstants
import com.ai.tinymodels.domain.model.PageContent
import com.ai.tinymodels.domain.model.WebSearchPage
import com.ai.tinymodels.domain.model.WebSearchResult
import com.ai.tinymodels.domain.model.WebSearchResults
import com.ai.tinymodels.domain.repository.WebSearchException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
class WebSearchClient @Inject constructor(
    private val configHolder: WebSearchConfigHolder
) {
    /** Test/legacy convenience constructor. */
    constructor() : this(WebSearchConfigHolder())

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Page reads have no overall cap; only read/connect timeouts. */
    private val pageClient: OkHttpClient = client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()

    /** Overridable in tests (fake engines / frozen clock). */
    internal var endpoints: List<SearchEndpoint> = WebSearchEndpoints.DEFAULT
    internal var nowMillis: () -> Long = System::currentTimeMillis

    /**
     * Test seam: (endpoint, sentQuery) -> (httpCode, body). Defaults to real
     * HTTP; tests replace it with canned pages so no network is needed.
     */
    internal var fetcher: suspend (SearchEndpoint, String, SearchFreshness) -> Pair<Int?, String> =
        { endpoint, sent, freshness -> httpAsk(endpoint, sent, freshness) }

    /** Test seam for page reads: URL -> (httpCode, body). */
    internal var pageFetcher: suspend (String) -> Pair<Int?, String> =
        { url -> httpGetPage(url) }

    /** Test seam for provider API requests. */
    internal var providerFetcher: suspend (ProviderRequest) -> Pair<Int?, String> =
        { req -> httpProvider(req) }

    private val pageCache = PageCache()

    /** Engines resting after a refusal, by URL, until epoch-ms. */
    private val restingUntil = ConcurrentHashMap<String, Long>()

    companion object {
        /** How long an engine that turned us away is left alone. */
        const val COOLDOWN_MS = 10 * 60 * 1000L

        /** Bound for a whole search request across a keyless engine. */
        const val SEARCH_TIMEOUT_SECONDS = 6L

        /** Below this, the static page is likely JS/app shell; try Jina. */
        private const val JINA_MIN_CHARS = 200
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
        readTopResult: Boolean = true,
        pageBudgetChars: Int = 1_500,
        pagesToRead: Int = 2
    ): WebSearchResults = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) throw WebSearchException("There is nothing to search for.")

        // Race every engine: first non-empty, relevant answer wins and
        // cancels the rest. A slow or challenged engine never blocks a
        // fast one, and total latency is bounded by one timeout.
        val now = nowMillis()
        val awake = endpoints.filter { (restingUntil[it.url] ?: 0L) <= now }
        val asking = if (awake.isEmpty()) endpoints else awake

        val freshness = WebSearchQuery.freshnessFor(trimmed)
        val reached = AtomicInteger(0)
        val blocked = AtomicInteger(0)
        val lastFailure = AtomicReference<Exception?>(null)

        // Configured provider: try first, fall back to keyless on any failure.
        val cfg = configHolder.config
        if (cfg.provider != com.ai.tinymodels.domain.model.SearchProvider.KEYLESS && cfg.providerReady()) {
            try {
                val providerResults = searchViaProvider(cfg, trimmed, maxResults, freshness)
                if (providerResults != null) {
                    val pages = if (readTopResult && pagesToRead > 0) {
                        readTopPages(providerResults, trimmed, pagesToRead, pageBudgetChars)
                    } else emptyList()
                    return@withContext WebSearchResults(
                        query = trimmed,
                        results = providerResults,
                        fetchedAt = nowMillis(),
                        leadText = pages.firstOrNull()?.text ?: "",
                        pages = pages
                    )
                }
            } catch (_: Exception) {
                // Fall through to keyless.
            }
        }

        val winner = channelFlow {
            asking.forEach { endpoint ->
                launch(Dispatchers.IO) {
                    attempt(endpoint, trimmed, maxResults, freshness, reached, blocked, lastFailure)
                        ?.let { send(it) }
                }
            }
        }.firstOrNull { it.results.isNotEmpty() }

        if (winner != null) {
            val kept = winner.results
            val pages = if (readTopResult && pagesToRead > 0) {
                readTopPages(kept, trimmed, pagesToRead = pagesToRead, budgetChars = pageBudgetChars)
            } else emptyList()
            return@withContext WebSearchResults(
                query = trimmed,
                results = kept,
                fetchedAt = nowMillis(),
                leadText = pages.firstOrNull()?.text ?: "",
                pages = pages
            )
        }

        val failure = lastFailure.get()
        if (reached.get() == 0 && failure != null) {
            throw WebSearchException(describeFailure(failure))
        }
        return@withContext WebSearchResults(
            query = trimmed,
            results = emptyList(),
            fetchedAt = nowMillis(),
            blocked = reached.get() > 0 && blocked.get() == reached.get()
        )
    }

    /** One engine attempt; null when it contributed nothing. */
    private suspend fun attempt(
        endpoint: SearchEndpoint,
        trimmed: String,
        maxResults: Int,
        freshness: SearchFreshness,
        reached: AtomicInteger,
        blocked: AtomicInteger,
        lastFailure: AtomicReference<Exception?>
    ): EngineAttempt? {
        return try {
            val sent = if (endpoint.keywords) WebSearchQuery.keywordQuery(trimmed) else trimmed
            val (code, body) = fetcher(endpoint, sent, freshness)
            reached.incrementAndGet()
            val parsed = try {
                endpoint.parse(body)
            } catch (e: Exception) {
                // One engine's layout changing must not kill the search.
                lastFailure.set(e)
                return null
            }
            if (WebSearchParser.isChallengePage(code, body, parsed.isNotEmpty())) {
                blocked.incrementAndGet()
                rest(endpoint)
                return null
            }
            restingUntil.remove(endpoint.url)

            var results = WebSearchFilter.rank(WebSearchFilter.keepRelevant(parsed, trimmed))

            // Keyword engines get one retry with reordered words when
            // what came back is off the point.
            val retry = if (endpoint.keywords) WebSearchQuery.retryKeywordQuery(sent) else null
            if (retry != null && parsed.isNotEmpty() &&
                (results.isEmpty() || !WebSearchFilter.anyCoversAll(results, trimmed))
            ) {
                try {
                    val (_, secondBody) = fetcher(endpoint, retry, freshness)
                    val again = WebSearchFilter.rank(WebSearchFilter.keepRelevant(endpoint.parse(secondBody), trimmed))
                    if (again.isNotEmpty() &&
                        (results.isEmpty() || WebSearchFilter.anyCoversAll(again, trimmed))
                    ) {
                        results = again
                    }
                } catch (_: Exception) {
                    // First answer still stands.
                }
            }

            EngineAttempt(
                endpoint = endpoint,
                results = results.take(maxResults).map { it.withQuery(trimmed) }
            )
        } catch (e: IOException) {
            lastFailure.set(e)
            if (isRefusal(e, null)) rest(endpoint)
            null
        } catch (e: Exception) {
            lastFailure.set(e)
            null
        }
    }

    /** Returns (HTTP code or null on network failure, body or ""). Never throws for HTTP codes. */
    private fun httpAsk(
        endpoint: SearchEndpoint,
        sent: String,
        freshness: SearchFreshness = SearchFreshness.NONE
    ): Pair<Int?, String> {
        val cfg = configHolder.config
        val acceptLanguage = SearchLocale.acceptLanguage(cfg.languageTag)
        val headers = WebSearchEndpoints.headersFor(endpoint).toMutableMap()
        headers["Accept-Language"] = acceptLanguage
        val request = if (endpoint.post) {
            val form = FormBody.Builder()
                .add("q", sent)
                .add("kl", SearchLocale.ddgKl(cfg.languageTag))
            freshness.ddgDf()?.let { form.add("df", it) }
            val builder = Request.Builder().url(endpoint.url).post(form.build())
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.build()
        } else {
            val httpUrlBuilder = endpoint.url.toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("q", sent)
                ?: throw IOException("Bad search URL")
            freshness.ddgDf()?.let { httpUrlBuilder.addQueryParameter("df", it) }
            val builder = Request.Builder().url(httpUrlBuilder.build()).get()
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

    /**
     * Reads and extracts one public page. Cached; returns null when the URL
     * is unsafe, unreadable, or the network fails.
     */
    suspend fun openPage(url: String, query: String? = null, maxChars: Int = 2_500): PageContent? {
        val trimmed = url.trim()
        if (!PageUrl.isFetchable(trimmed)) return null
        pageCache.get(trimmed)?.let { cached ->
            return PageContent(url = trimmed, text = cached.take(maxChars))
        }
        val text = readPage(trimmed, query, maxChars)
        if (text.isBlank()) return null
        return PageContent(url = trimmed, text = text)
    }

    private suspend fun readTopPages(
        results: List<WebSearchResult>,
        query: String,
        pagesToRead: Int,
        budgetChars: Int
    ): List<WebSearchPage> {
        val perPage = (budgetChars / pagesToRead.coerceAtLeast(1)).coerceAtLeast(600)
        val pages = mutableListOf<WebSearchPage>()
        for (result in results) {
            if (pages.size >= pagesToRead) break
            if (!PageReader.isReadablePage(result.url)) continue
            val text = readPage(result.url, query, maxChars = perPage)
            if (text.isBlank()) continue
            pages.add(
                WebSearchPage(
                    url = result.url,
                    title = result.title,
                    host = result.displayHost,
                    text = text
                )
            )
        }
        return pages
    }

    /** Reads prose off a page; never throws ("" on any failure). */
    internal suspend fun readPage(url: String, query: String?, maxChars: Int = 1_500): String {
        pageCache.get(url)?.let { return it.take(maxChars) }
        return try {
            val (code, body) = pageFetcher(url)
            if (code == null || code !in 200..299) return ""
            var text = PageReader.extractReadableText(body, query = query, limit = maxChars)
            if (text.length < JINA_MIN_CHARS && configHolder.config.readerFallbackEnabled && !url.startsWith("https://r.jina.ai/")) {
                val (jinaCode, jinaBody) = pageFetcher(JinaReader.urlFor(url))
                if (jinaCode != null && jinaCode in 200..299) {
                    val cleaned = JinaReader.cleanMarkdown(jinaBody, limit = maxChars)
                    if (cleaned.length > text.length) text = cleaned
                }
            }
            if (text.isNotBlank()) pageCache.put(url, text)
            text
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun httpGetPage(url: String): Pair<Int?, String> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url).get()
                .header(TinyConstants.HTTP_HEADER_USER_AGENT, WebSearchEndpoints.MOBILE_UA)
                .header(TinyConstants.HTTP_HEADER_ACCEPT, TinyConstants.HTTP_ACCEPT_HTML)
                .header("Accept-Language", SearchLocale.acceptLanguage(configHolder.config.languageTag))
            pageClient.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""
                response.code to body
            }
        } catch (_: Exception) {
            null to ""
        }
    }

    private suspend fun searchViaProvider(
        config: WebSearchConfig,
        query: String,
        maxResults: Int,
        freshness: SearchFreshness
    ): List<WebSearchResult>? = withContext(Dispatchers.IO) {
        try {
            val request = SearchProviderApi.request(config.provider, config, query, maxResults, freshness)
            val (code, body) = providerFetcher(request)
            if (code == null || code !in 200..299) return@withContext null
            val parsed = SearchProviderApi.parse(config.provider, body)
            val ranked = WebSearchFilter.rank(WebSearchFilter.keepRelevant(parsed, query))
            val kept = ranked.take(maxResults).map { it.withQuery(query) }
            kept.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun httpProvider(req: ProviderRequest): Pair<Int?, String> {
        return try {
            val builder = Request.Builder().url(req.url)
            req.headers.forEach { (k, v) -> builder.header(k, v) }
            if (req.method.equals("POST", ignoreCase = true)) {
                val mediaType = (req.headers["Content-Type"] ?: "application/json").toMediaType()
                builder.post((req.body ?: "").toRequestBody(mediaType))
            } else {
                builder.get()
            }
            client.newCall(builder.build()).execute().use { response ->
                response.code to (response.body?.string() ?: "")
            }
        } catch (_: Exception) {
            null to ""
        }
    }

    private fun rest(endpoint: SearchEndpoint) = restByUrl(endpoint.url)

    private fun restByUrl(url: String) {
        restingUntil[url] = nowMillis() + COOLDOWN_MS
    }

    /** Test-only: clear cooldowns. */
    internal fun clearCooldowns() { restingUntil.clear(); pageCache.clear() }

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
                    url = abstractUrl ?: "${TinyConstants.DUCKDUCKGO_BASE_URL}/?q=${URLEncoder.encode(query, Charsets.UTF_8.name())}",
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
                    url = TinyConstants.WIKIPEDIA_BASE_URL +
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

/** A keyless engine's successful contribution. */
private data class EngineAttempt(
    val endpoint: SearchEndpoint,
    val results: List<WebSearchResult>
)
