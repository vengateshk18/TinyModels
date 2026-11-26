package com.ai.tinymodels.core.network.eval

/**
 * The live eval corpus. Kept small enough to run in a few minutes, broad
 * enough to cover the four things people actually search from a chat app:
 * stable facts, current events, prices, and how-to/docs.
 *
 * Expectations are deliberately loose. The web changes and engines differ,
 * so a case passes on an expected *word*, not an exact sentence, and
 * preferred hosts are a bonus signal.
 */
object SearchEvalCorpus {

    val CASES: List<SearchEvalCase> = listOf(
        // ---- Stable facts: answer words that do not appear in the query ----
        SearchEvalCase(
            id = "fact-capital-au",
            query = "what is the capital of australia",
            category = SearchEvalCategory.STABLE_FACT,
            mustContainAny = listOf("canberra"),
            preferredHosts = listOf("wikipedia.org", "britannica.com")
        ),
        SearchEvalCase(
            id = "fact-everest",
            query = "height of mount everest in meters",
            category = SearchEvalCategory.STABLE_FACT,
            mustContainAny = listOf("8,848", "8848", "29,032"),
            preferredHosts = listOf("wikipedia.org", "britannica.com")
        ),
        SearchEvalCase(
            id = "fact-hamlet",
            query = "who wrote the play hamlet",
            category = SearchEvalCategory.STABLE_FACT,
            mustContainAny = listOf("shakespeare"),
            preferredHosts = listOf("wikipedia.org", "britannica.com")
        ),
        SearchEvalCase(
            id = "fact-gold",
            query = "what is the chemical symbol for gold",
            category = SearchEvalCategory.STABLE_FACT,
            mustContainAny = listOf("aurum", "au is", "symbol au"),
            preferredHosts = listOf("wikipedia.org", "britannica.com")
        ),
        SearchEvalCase(
            id = "fact-404",
            query = "what does http status code 404 mean",
            category = SearchEvalCategory.STABLE_FACT,
            mustContainAny = listOf("not found"),
            preferredHosts = listOf("developer.mozilla.org", "httpstatuses.com")
        ),
        SearchEvalCase(
            id = "fact-jupiter",
            query = "what is the largest planet in the solar system",
            category = SearchEvalCategory.STABLE_FACT,
            mustContainAny = listOf("jupiter"),
            preferredHosts = listOf("wikipedia.org", "nasa.gov")
        ),
        SearchEvalCase(
            id = "fact-eiffel",
            query = "how tall is the eiffel tower",
            category = SearchEvalCategory.STABLE_FACT,
            mustContainAny = listOf("330", "1,083", "1083", "300"),
            preferredHosts = listOf("wikipedia.org", "toureiffel.paris")
        ),
        SearchEvalCase(
            id = "fact-nvidia-ceo",
            query = "who is the current ceo of nvidia",
            category = SearchEvalCategory.STABLE_FACT,
            mustContainAny = listOf("jensen"),
            preferredHosts = listOf("nvidia.com", "wikipedia.org")
        ),

        // ---- Current events: scored on "found + read a page", not correctness ----
        SearchEvalCase(
            id = "now-news-india",
            query = "latest news today india",
            category = SearchEvalCategory.CURRENT,
            preferredHosts = listOf("thehindu.com", "indianexpress.com", "ndtv.com", "bbc.com")
        ),
        SearchEvalCase(
            id = "now-weather-chennai",
            query = "today's weather in chennai",
            category = SearchEvalCategory.CURRENT,
            preferredHosts = listOf("weather.com", "accuweather.com", "imd.gov.in")
        ),
        SearchEvalCase(
            id = "now-f1",
            query = "who won the last formula 1 race",
            category = SearchEvalCategory.CURRENT,
            preferredHosts = listOf("formula1.com", "bbc.com", "espn.com")
        ),
        SearchEvalCase(
            id = "now-bitcoin",
            query = "bitcoin price today",
            category = SearchEvalCategory.CURRENT,
            preferredHosts = listOf("coindesk.com", "coingecko.com", "coinmarketcap.com")
        ),
        SearchEvalCase(
            id = "now-cricket",
            query = "india vs australia cricket score today",
            category = SearchEvalCategory.CURRENT,
            preferredHosts = listOf("espncricinfo.com", "cricbuzz.com", "icc-cricket.com")
        ),
        SearchEvalCase(
            id = "now-iphone",
            query = "latest iphone release date",
            category = SearchEvalCategory.CURRENT,
            preferredHosts = listOf("apple.com", "macrumors.com", "theverge.com")
        ),

        // ---- Product / price ----
        SearchEvalCase(
            id = "buy-s25",
            query = "samsung galaxy s25 price in india",
            category = SearchEvalCategory.PRODUCT,
            preferredHosts = listOf("samsung.com", "amazon.in", "flipkart.com")
        ),
        SearchEvalCase(
            id = "buy-headphones",
            query = "best noise cancelling headphones 2026",
            category = SearchEvalCategory.PRODUCT,
            preferredHosts = listOf("rtings.com", "whathifi.com", "theverge.com")
        ),
        SearchEvalCase(
            id = "buy-airmax",
            query = "nike air max 90 price",
            category = SearchEvalCategory.PRODUCT,
            preferredHosts = listOf("nike.com", "amazon.in", "flipkart.com")
        ),

        // ---- Docs / how-to ----
        SearchEvalCase(
            id = "doc-git-branch",
            query = "how to create a git branch and switch to it",
            category = SearchEvalCategory.DOCS,
            mustContainAny = listOf("checkout", "switch"),
            preferredHosts = listOf("git-scm.com", "stackoverflow.com")
        ),
        SearchEvalCase(
            id = "doc-compose-lazy",
            query = "android compose lazy column example",
            category = SearchEvalCategory.DOCS,
            mustContainAny = listOf("lazycolumn", "lazy list"),
            preferredHosts = listOf("developer.android.com")
        ),
        SearchEvalCase(
            id = "doc-python-reverse",
            query = "how to reverse a string in python",
            category = SearchEvalCategory.DOCS,
            mustContainAny = listOf("slice", "[::-1]"),
            preferredHosts = listOf("docs.python.org", "stackoverflow.com")
        ),
        SearchEvalCase(
            id = "doc-room-migration",
            query = "room database migration android",
            category = SearchEvalCategory.DOCS,
            mustContainAny = listOf("schema", "alter table", "migration"),
            preferredHosts = listOf("developer.android.com", "stackoverflow.com")
        ),

        // ---- Ambiguous / comparison ----
        SearchEvalCase(
            id = "amb-tcp-udp",
            query = "what is the difference between tcp and udp",
            category = SearchEvalCategory.AMBIGUOUS,
            mustContainAny = listOf("reliable"),
            preferredHosts = listOf("cloudflare.com", "wikipedia.org")
        ),
        SearchEvalCase(
            id = "amb-kotlin-coroutines",
            query = "kotlin coroutines suspend function",
            category = SearchEvalCategory.AMBIGUOUS,
            mustContainAny = listOf("asynchron"),
            preferredHosts = listOf("kotlinlang.org", "developer.android.com")
        )
    )
}
