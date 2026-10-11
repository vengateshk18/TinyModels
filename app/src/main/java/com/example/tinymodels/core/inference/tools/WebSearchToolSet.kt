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
 * The method returns compact plain text (title — snippet (url)) so a small
 * on-device model gets useful context without HTML noise, and degrades
 * gracefully on failure ("Search unavailable…") so the model can answer from
 * its own knowledge instead of crashing the turn.
 */
@Singleton
class WebSearchToolSet @Inject constructor(
    private val webSearchRepository: WebSearchRepository
) : ToolSet {

    @Tool("Search the web for current or factual information the assistant does not know. Input: a short search query. Returns a numbered list of results with title, snippet and URL.")
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
            val results = kotlinx.coroutines.runBlocking {
                webSearchRepository.search(query.trim(), maxResults = 5)
            }
            val elapsedMs = System.currentTimeMillis() - startedAt
            if (results.isEmpty()) {
                Log.w(TAG, "web_search returned 0 results for \"$query\" in ${elapsedMs}ms")
                "No results found for \"$query\". Answer from your own knowledge and say you could not verify with a web search."
            } else {
                Log.i(
                    TAG,
                    "web_search returned ${results.size} results for \"$query\" in ${elapsedMs}ms: " +
                        results.joinToString(" | ") { it.url }
                )
                buildString {
                    appendLine("Web search results for \"$query\":")
                    results.forEachIndexed { index, result ->
                        appendLine(result.toToolText(index + 1))
                    }
                }
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
        private const val TAG = "WebSearchTool"
    }
}
