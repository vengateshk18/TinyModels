package com.example.tinymodels.core.inference.tools

import android.util.Log
import com.example.tinymodels.domain.repository.WebSearchException
import com.example.tinymodels.domain.repository.WebSearchRepository
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The model-callable `web_search` tool. Registered with the conversation via
 * `tool(WebSearchToolSet(...))` when (and only when) web search is enabled
 * for the chat — LiteRT-LM's `automaticToolCalling` then executes this method
 * whenever the model emits a matching tool call, and feeds the returned text
 * back into the generation loop.
 *
 * Backed by the keyless HTML fallback chain (DDG Lite → DDG HTML → Bing),
 * so it answers live queries (news, prices, scores, weather, releases) —
 * not just encyclopedia entities. Returns compact plain text plus the top
 * page's excerpt, and degrades gracefully ("Search unavailable…",
 * "blocked…", "no results…") so the model answers from its own knowledge
 * instead of crashing the turn.
 */
@Singleton
class WebSearchToolSet @Inject constructor(
    private val webSearchRepository: WebSearchRepository
) : ToolSet {

    @Tool(
        "Search the web for current or factual information the assistant does not know — " +
            "news, prices, scores, weather, releases, people, places, concepts. " +
            "Input: a short search query. Returns a numbered list of results with title, snippet " +
            "and URL, plus an excerpt from the top page."
    )
    fun webSearch(query: String): String {
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "Model called web_search: query=\"$query\"")
        if (query.isBlank()) {
            Log.w(TAG, "web_search called with an empty query")
            return "Search query was empty."
        }
        return try {
            // The repository switches to IO internally; the tool itself may
            // be invoked on the runtime's executor thread.
            val search = kotlinx.coroutines.runBlocking {
                webSearchRepository.search(query.trim(), maxResults = 5)
            }
            val elapsedMs = System.currentTimeMillis() - startedAt
            val results = search.results
            if (results.isEmpty()) {
                if (search.blocked) {
                    Log.w(TAG, "web_search blocked for \"$query\" in ${elapsedMs}ms")
                    "Web search was blocked by the search engines for \"$query\". Answer from your own knowledge and say the live search was blocked."
                } else {
                    Log.w(TAG, "web_search returned 0 results for \"$query\" in ${elapsedMs}ms")
                    "No results found for \"$query\". Answer from your own knowledge and say you could not verify with a web search."
                }
            } else {
                Log.i(
                    TAG,
                    "web_search returned ${results.size} results for \"$query\" in ${elapsedMs}ms: " +
                        results.joinToString(" | ") { it.url }
                )
                val toolText = buildString {
                    appendLine("Web search results for \"${search.query}\":")
                    results.forEachIndexed { index, result ->
                        appendLine(result.toToolText(index + 1))
                    }
                    if (search.leadText.isNotEmpty()) {
                        appendLine()
                        appendLine("Excerpt from ${results.first().displayHost}:")
                        appendLine(search.leadText.take(800))
                    }
                }
                // The exact tool payload the runtime feeds back into the
                // generation loop — logged so the journey filter shows what
                // the model actually receives from the tool.
                Log.i(TAG, "JOURNEY: web_search tool result fed back to model (${toolText.length} chars):\n$toolText")
                toolText
            }
        } catch (e: WebSearchException) {
            Log.e(TAG, "web_search failed for \"$query\": ${e.message}", e)
            "Search unavailable (${e.message}). Answer from your own knowledge and say you could not verify with a web search."
        } catch (e: Exception) {
            Log.e(TAG, "web_search failed unexpectedly for \"$query\"", e)
            "Search unavailable. Answer from your own knowledge and say you could not verify with a web search."
        }
    }

    private companion object {
        // Same tag as ChatViewModel's JOURNEY logs so one logcat filter
        // (adb logcat -s TinyModelsJourney) shows the entire turn.
        private const val TAG = "TinyModelsJourney"
    }
}
