package com.ai.tinymodels.core.network.eval

import com.ai.tinymodels.domain.model.WebSearchResult

/** What kind of question a case asks; quality is judged differently per group. */
enum class SearchEvalCategory(val label: String) {
    STABLE_FACT("Stable fact"),
    CURRENT("Current / news"),
    PRODUCT("Product / price"),
    DOCS("Docs / how-to"),
    AMBIGUOUS("Ambiguous")
}

/**
 * One live eval question. [mustContainAny] is deliberately broad (an expected
 * word, not an exact sentence) so a changing web does not make the eval flaky.
 * [preferredHosts] is a bonus signal, not a hard requirement.
 */
data class SearchEvalCase(
    val id: String,
    val query: String,
    val category: SearchEvalCategory,
    val mustContainAny: List<String> = emptyList(),
    val preferredHosts: List<String> = emptyList()
)

/** What the search client returned for one [SearchEvalCase]. */
data class SearchEvalRun(
    val case: SearchEvalCase,
    val results: List<WebSearchResult> = emptyList(),
    val leadText: String = "",
    val latencyMs: Long = 0,
    val blocked: Boolean = false,
    val error: String? = null
) {
    val found: Boolean get() = results.isNotEmpty()

    val pageRead: Boolean get() = leadText.isNotBlank()

    /** Title, snippet, URL and page text, lowercased — what the model would have seen. */
    val seenText: String
        get() = buildString {
            results.forEach {
                appendLine(it.title)
                appendLine(it.snippet)
                appendLine(it.url)
            }
            appendLine(leadText)
        }.lowercase()

    /** null when the case states no expected word (e.g. "latest news"). */
    val termHit: Boolean?
        get() = if (case.mustContainAny.isEmpty()) null
        else case.mustContainAny.any { seenText.contains(it.lowercase()) }

    /** null when the case states no preferred hosts. */
    val hostHit: Boolean?
        get() = if (case.preferredHosts.isEmpty()) null
        else results.any { r ->
            val host = r.displayHost.lowercase()
            case.preferredHosts.any { h -> host == h.lowercase() || host.endsWith(".${h.lowercase()}") }
        }
}

/** One category's slice of the report. */
data class SearchEvalCategoryReport(
    val category: SearchEvalCategory,
    val total: Int,
    val found: Int,
    val pageRead: Int,
    val termHits: Int,
    val termTotal: Int,
    val hostHits: Int,
    val hostTotal: Int,
    val p50LatencyMs: Long
)

data class SearchEvalReport(
    val total: Int,
    val found: Int,
    val pageRead: Int,
    val termHits: Int,
    val termTotal: Int,
    val hostHits: Int,
    val hostTotal: Int,
    val blocked: Int,
    val errors: Int,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val byCategory: List<SearchEvalCategoryReport>,
    val runs: List<SearchEvalRun>
) {
    fun toMarkdown(mode: String, generatedAt: Long = System.currentTimeMillis()): String = buildString {
        appendLine("# Live web search eval — $mode")
        appendLine()
        appendLine("- Generated: ${java.util.Date(generatedAt)}")
        appendLine("- Mode: $mode")
        appendLine("- Cases: $total")
        appendLine("- Found results: $found/$total (${SearchEvalScorer.percent(found, total)}%)")
        appendLine("- Read a page: $pageRead/$total (${SearchEvalScorer.percent(pageRead, total)}%)")
        appendLine("- Expected-term hit: $termHits/$termTotal (${SearchEvalScorer.percent(termHits, termTotal)}%)")
        appendLine("- Preferred-host hit: $hostHits/$hostTotal (${SearchEvalScorer.percent(hostHits, hostTotal)}%)")
        appendLine("- Blocked: $blocked")
        appendLine("- Errors: $errors")
        appendLine("- Latency p50/p95: ${p50LatencyMs}ms / ${p95LatencyMs}ms")
        appendLine()
        appendLine("## By category")
        appendLine()
        appendLine("| Category | n | found | page | term | host | p50 |")
        appendLine("|---|---|---|---|---|---|---|")
        byCategory.forEach { c ->
            appendLine(
                "| ${c.category.label} | ${c.total} | ${c.found} | ${c.pageRead} | " +
                    "${c.termHits}/${c.termTotal} | ${c.hostHits}/${c.hostTotal} | ${c.p50LatencyMs}ms |"
            )
        }
        appendLine()
        appendLine("## Misses")
        appendLine()
        val misses = runs.filter { !it.found || it.termHit == false || it.error != null }
        if (misses.isEmpty()) {
            appendLine("None.")
        } else {
            misses.forEach { run ->
                val why = buildList {
                    if (!run.found) add("no results")
                    if (run.blocked) add("blocked")
                    if (run.termHit == false) add("expected word missing")
                    if (run.error != null) add("error: ${run.error}")
                }.joinToString(", ")
                appendLine("- `${run.case.id}` — \"${run.case.query}\" — $why (${run.latencyMs}ms)")
            }
        }
        appendLine()
        appendLine("## Cases")
        appendLine()
        runs.forEach { run ->
            appendLine(
                "- `${run.case.id}` [${run.case.category.label}] found=${run.found} " +
                    "page=${run.pageRead} term=${run.termHit ?: "n/a"} host=${run.hostHit ?: "n/a"} " +
                    "${run.latencyMs}ms"
            )
        }
    }
}

/** Pure aggregation for the live eval: no network, no clock, no Android. */
object SearchEvalScorer {

    fun percent(numerator: Int, denominator: Int): Int =
        if (denominator <= 0) 0 else (numerator * 100.0 / denominator).toInt()

    /** Nearest-rank percentile; 0 for an empty list. */
    fun percentile(values: List<Long>, p: Int): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    fun report(runs: List<SearchEvalRun>): SearchEvalReport {
        val termRuns = runs.filter { it.termHit != null }
        val hostRuns = runs.filter { it.hostHit != null }
        val latencies = runs.map { it.latencyMs }
        val byCategory = SearchEvalCategory.entries.mapNotNull { category ->
            val group = runs.filter { it.case.category == category }
            if (group.isEmpty()) null else {
                val term = group.filter { it.termHit != null }
                val host = group.filter { it.hostHit != null }
                SearchEvalCategoryReport(
                    category = category,
                    total = group.size,
                    found = group.count { it.found },
                    pageRead = group.count { it.pageRead },
                    termHits = term.count { it.termHit == true },
                    termTotal = term.size,
                    hostHits = host.count { it.hostHit == true },
                    hostTotal = host.size,
                    p50LatencyMs = percentile(group.map { it.latencyMs }, 50)
                )
            }
        }
        return SearchEvalReport(
            total = runs.size,
            found = runs.count { it.found },
            pageRead = runs.count { it.pageRead },
            termHits = termRuns.count { it.termHit == true },
            termTotal = termRuns.size,
            hostHits = hostRuns.count { it.hostHit == true },
            hostTotal = hostRuns.size,
            blocked = runs.count { it.blocked },
            errors = runs.count { it.error != null },
            p50LatencyMs = percentile(latencies, 50),
            p95LatencyMs = percentile(latencies, 95),
            byCategory = byCategory,
            runs = runs
        )
    }
}
