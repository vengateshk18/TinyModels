package com.ai.tinymodels.core.network.eval

import com.ai.tinymodels.core.network.WebSearchClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Live network eval. Skipped unless `-PsearchEval=1` is passed, so normal
 * unit runs stay offline and deterministic.
 *
 *   ./gradlew :app:testDebugUnitTest --tests "*LiveSearchEvalTest" \
 *     -PsearchEval=1 -Pandroid.experimental.disableJdkImageTransform=true --rerun
 *
 * The report lands in `app/build/reports/search-eval/search-eval-keyless.md`.
 * It measures the pipeline, not the model: did we find results, read a page,
 * see the expected word, and how long did it take.
 */
class LiveSearchEvalTest {

    @Test
    fun `live keyless search eval`() = runBlocking {
        assumeTrue(
            "set -PsearchEval=1 to run the live network eval",
            System.getProperty("searchEval") == "1"
        )

        val client = WebSearchClient()
        val runs = SearchEvalCorpus.CASES.mapIndexed { index, case ->
            // Be polite between requests; keyless endpoints are shared infrastructure.
            if (index > 0) delay(PAUSE_BETWEEN_CASES_MS)
            val startedAt = System.currentTimeMillis()
            try {
                val out = client.search(case.query, maxResults = 5, readTopResult = true)
                SearchEvalRun(
                    case = case,
                    results = out.results,
                    leadText = out.leadText,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    blocked = out.blocked
                )
            } catch (e: Exception) {
                SearchEvalRun(
                    case = case,
                    latencyMs = System.currentTimeMillis() - startedAt,
                    error = e.message ?: e::class.simpleName
                )
            }
        }

        val report = SearchEvalScorer.report(runs)
        val markdown = report.toMarkdown(MODE)
        val dir = File("build/reports/search-eval").apply { mkdirs() }
        File(dir, "search-eval-keyless.md").writeText(markdown)
        println(markdown)

        // The eval is a measurement; only assert that it actually ran.
        assertEquals(SearchEvalCorpus.CASES.size, report.total)
    }

    private companion object {
        const val MODE = "keyless (DuckDuckGo Lite → DuckDuckGo HTML → Bing)"
        const val PAUSE_BETWEEN_CASES_MS = 2_000L
    }
}
