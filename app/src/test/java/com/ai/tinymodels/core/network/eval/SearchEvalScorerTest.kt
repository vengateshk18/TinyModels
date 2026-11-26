package com.ai.tinymodels.core.network.eval

import com.ai.tinymodels.domain.model.WebSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEvalScorerTest {

    private fun case(
        id: String = "c1",
        terms: List<String> = emptyList(),
        hosts: List<String> = emptyList(),
        category: SearchEvalCategory = SearchEvalCategory.STABLE_FACT
    ) = SearchEvalCase(
        id = id,
        query = "query $id",
        category = category,
        mustContainAny = terms,
        preferredHosts = hosts
    )

    private fun result(title: String = "t", snippet: String = "s", url: String = "https://example.com/a") =
        WebSearchResult(title = title, url = url, snippet = snippet)

    @Test
    fun `term hit looks in title snippet page text and urls`() {
        val run = SearchEvalRun(
            case = case(terms = listOf("canberra")),
            results = listOf(result(title = "Capital of Australia")),
            leadText = "Canberra is the capital."
        )
        assertEquals(true, run.termHit)
    }

    @Test
    fun `term miss when the term appears nowhere`() {
        val run = SearchEvalRun(
            case = case(terms = listOf("canberra")),
            results = listOf(result(title = "Sydney travel guide")),
            leadText = "Sydney is the largest city."
        )
        assertEquals(false, run.termHit)
    }

    @Test
    fun `term score is null when the case has no expected terms`() {
        val run = SearchEvalRun(case = case(), results = listOf(result()))
        assertNull(run.termHit)
    }

    @Test
    fun `host hit matches exact host and subdomain`() {
        val exact = SearchEvalRun(
            case = case(hosts = listOf("wikipedia.org")),
            results = listOf(result(url = "https://wikipedia.org/wiki/X"))
        )
        val subdomain = SearchEvalRun(
            case = case(hosts = listOf("wikipedia.org")),
            results = listOf(result(url = "https://en.wikipedia.org/wiki/X"))
        )
        assertEquals(true, exact.hostHit)
        assertEquals(true, subdomain.hostHit)
    }

    @Test
    fun `host hit is false for an unrelated host`() {
        val run = SearchEvalRun(
            case = case(hosts = listOf("wikipedia.org")),
            results = listOf(result(url = "https://example.com/x"))
        )
        assertEquals(false, run.hostHit)
    }

    @Test
    fun `nearest rank percentile handles p50 and p95`() {
        val values = listOf(400L, 100L, 300L, 200L)
        assertEquals(200L, SearchEvalScorer.percentile(values, 50))
        assertEquals(400L, SearchEvalScorer.percentile(values, 95))
    }

    @Test
    fun `percentile of an empty list is zero`() {
        assertEquals(0L, SearchEvalScorer.percentile(emptyList(), 50))
    }

    @Test
    fun `report counts found page term host blocked and errors`() {
        val runs = listOf(
            SearchEvalRun(
                case = case(id = "a", terms = listOf("au"), hosts = listOf("wikipedia.org")),
                results = listOf(result(title = "Gold", snippet = "symbol Au", url = "https://en.wikipedia.org/wiki/Gold")),
                leadText = "Gold is Au.",
                latencyMs = 100
            ),
            SearchEvalRun(
                case = case(id = "b", terms = listOf("missing")),
                results = emptyList(),
                latencyMs = 300,
                blocked = true
            ),
            SearchEvalRun(
                case = case(id = "c"),
                results = emptyList(),
                latencyMs = 200,
                error = "timeout"
            )
        )

        val report = SearchEvalScorer.report(runs)

        assertEquals(3, report.total)
        assertEquals(1, report.found)
        assertEquals(1, report.pageRead)
        assertEquals(1, report.termHits)
        assertEquals(2, report.termTotal)
        assertEquals(1, report.hostHits)
        assertEquals(1, report.hostTotal)
        assertEquals(1, report.blocked)
        assertEquals(1, report.errors)
        assertEquals(200L, report.p50LatencyMs)
        assertEquals(300L, report.p95LatencyMs)
    }

    @Test
    fun `report is grouped by category`() {
        val runs = listOf(
            SearchEvalRun(case = case(id = "a", category = SearchEvalCategory.CURRENT), results = listOf(result())),
            SearchEvalRun(case = case(id = "b", category = SearchEvalCategory.CURRENT), results = emptyList())
        )

        val current = SearchEvalScorer.report(runs).byCategory.single { it.category == SearchEvalCategory.CURRENT }

        assertEquals(2, current.total)
        assertEquals(1, current.found)
    }

    @Test
    fun `report text includes the headline rates and misses`() {
        val runs = listOf(
            SearchEvalRun(case = case(id = "miss", terms = listOf("nope")), results = emptyList(), latencyMs = 50)
        )
        val text = SearchEvalScorer.report(runs).toMarkdown(mode = "keyless")

        assertTrue(text.contains("keyless"))
        assertTrue(text.contains("Found results"))
        assertTrue(text.contains("miss"))
        assertFalse(text.contains("null"))
    }
}
