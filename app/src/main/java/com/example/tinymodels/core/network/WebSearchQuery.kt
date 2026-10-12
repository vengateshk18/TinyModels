package com.example.tinymodels.core.network

/**
 * Query pipeline ported from Thinai's `lib/web/web_search.dart`.
 *
 * Pure functions only (no network) so they are cheap to unit-test:
 *  - [needsWebSearch] — should the app search at all (app-side trigger,
 *    not dependent on a tiny model emitting a tool call).
 *  - [searchQueryFrom] — strip "search the web" instructions + resolve
 *    follow-ups ("what is its price" after "latest iphone").
 *  - [queryTerms] — meaningful words of a query (Tamil-safe via \p{M}).
 *  - [keywordQuery] / [retryKeywordQuery] — question → keywords for
 *    engines that cannot read a question (Bing).
 */
object WebSearchQuery {

    const val MAX_QUERY_LENGTH = 240

    private val askPrefix = Regex(
        """^\s*(?:(?:please|can\s+you|could\s+you|just|now|go|and|hey|ok(?:ay)?)\s+)*(?:search|google|look\s*up|lookup|browse|check)\s+(?:(?:the|on\s+the|in\s+the)\s+)?(?:web|internet|online|net|google)?\s*(?:for|about)?\s*[:,\-–]?\s*""",
        RegexOption.IGNORE_CASE
    )
    private val askSuffix = Regex(
        """[\s,.\-–]*(?:and\s+|then\s+|please\s+|just\s+)*(?:search|google|check|look\s*up|browse)\s+(?:(?:the|on\s+the|in\s+the)\s+)?(?:web|internet|online|google)\b[^.?!]*$""",
        RegexOption.IGNORE_CASE
    )
    private val askForResults = Regex(
        """[\s,.\-–]*(?:and\s+|then\s+)?(?:give|show|get|find|tell)\s+me\s+(?:the\s+|some\s+|any\s+)?(?:results?|answers?|links?|sources?)\s*[.?!]*$""",
        RegexOption.IGNORE_CASE
    )
    private val edgeTrim = Regex("""^[\s,.\-–:;]+|[\s,\-–:;]+$""")
    private val whitespace = Regex("""\s+""")

    private val pointsBack = Regex(
        """\b(it|its|it's|they|them|their|that|this|he|she|him|her|there|those)\b""",
        RegexOption.IGNORE_CASE
    )

    private val asksToSearch = Regex(
        """\b(search|google|look\s*up|lookup|browse)\b[^.?!]{0,30}\b(web|internet|online|google|it|this|that|for)\b""",
        RegexOption.IGNORE_CASE
    )
    private val refusesSearch = Regex(
        """\b(do\s*n[o']?t|dont|no|without|skip)\s+(search|google|look\s*up|the\s+web|internet|online)""",
        RegexOption.IGNORE_CASE
    )
    private val localTask = Regex(
        """\b(write|rewrite|draft|compose|translate|summari[sz]e|paraphrase|explain\s+(this|the\s+code|my)|refactor|debug|fix\s+(this|my)|convert|calculate|solve|simplify|correct|proofread|poem|story|essay|joke|code|function|regex|sql\s+query)\b""",
        RegexOption.IGNORE_CASE
    )
    private val wantsCurrent = Regex(
        """\b(news|headlines?|latest|newest|current(ly)?|today|tonight|yesterday|tomorrow|now|recent(ly)?|this\s+(week|month|year|morning|evening)|live|trending|breaking|trend|update[ds]?|upcoming|price|prices|cost|rate|rates|stock|shares|market|worth|score|scores|result|results|fixture|match|won|winner|championship|weather|forecast|temperature|rain|storm|release[ds]?|launch(ed|es)?|announce[ds]?|version|who\s+is|who\s+was|who's|what\s+happened|when\s+(is|was|did|does)|how\s+much\s+is|as\s+of|status\s+of|schedule|timings?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val mentionsYear = Regex("""\b(19|20)\d{2}\b""")

    internal val nonWord = Regex("""[^\p{L}\p{M}\p{N}]+""")
    private val acronym = Regex("""^(?=.*[A-Z])[A-Z0-9]{2,3}$""")
    internal val wordChar = Regex("""[\p{L}\p{M}\p{N}]""")

    private val possessive = Regex("""['’]s$""", RegexOption.IGNORE_CASE)
    private val edgePunctuation = Regex("""^[^\p{L}\p{M}\p{N}]+|[^\p{L}\p{M}\p{N}]+$""")

    /** Words too common to say anything about what a result is about. */
    internal val stopWords = setOf(
        "what", "whats", "when", "where", "which", "who", "whom", "whose", "why",
        "how", "this", "that", "these", "those", "there", "here", "with", "from",
        "into", "about", "your", "yours", "their", "they", "them", "have",
        "has", "had", "been", "being", "does", "did", "done", "will", "would",
        "should", "could", "can", "may", "might", "must", "shall", "and", "the",
        "for", "was", "were", "are", "its", "it's", "you", "me", "my", "give",
        "show", "tell", "find", "please", "just", "also", "more", "most", "some",
        "any", "all", "now", "today", "latest", "recent", "best", "top", "new",
        "yesterday", "tomorrow", "tonight", "current", "currently", "recently",
        "upcoming",
        "இன்றைய", "இன்று"
    )

    /** Words that make a question a question — dropped before sending to Bing. */
    internal val fillerWords = setOf(
        "who", "what", "whats", "what's", "when", "when's", "where", "which", "why",
        "how", "is", "are", "was", "were", "am", "be", "the", "a", "an", "of", "in",
        "on", "at", "to", "for", "by", "with", "about", "from", "do", "does", "did",
        "can", "could", "will", "would", "should", "please", "tell", "me", "give",
        "show", "find", "i", "my", "you", "your", "it", "its", "it's", "he", "she",
        "they", "them", "their", "his", "her", "him", "this", "that", "these",
        "those", "there",
        "இன்றைய", "இன்று", "என்ன", "யார்", "எப்போது", "எங்கே", "எப்படி"
    )

    internal val lowSignalVerbs = setOf("won", "win", "wins", "happened")
    internal val timeWords = setOf("today", "tonight", "yesterday", "tomorrow", "now")
    internal val recencyWords = setOf("latest", "newest", "recent")

    /**
     * Turns what the user typed into what goes to the search engine.
     * Strips app-instructions, resolves follow-ups via [previousUserTurns]
     * (oldest first, only the most recent is used), caps at 240 chars.
     */
    fun searchQueryFrom(message: String, previousUserTurns: List<String> = emptyList()): String {
        val original = message.trim().replace(whitespace, " ")
        if (original.isEmpty()) return ""
        var query = withoutAsk(original)
        if (previousUserTurns.isNotEmpty() && readsAsFollowUp(query)) {
            val carried = carriedTerms(previousUserTurns.last(), query)
            if (carried.isNotEmpty()) {
                val room = MAX_QUERY_LENGTH - query.length - 1
                val prefix = StringBuilder()
                for (word in carried) {
                    val extra = if (prefix.isEmpty()) word.length else word.length + 1
                    if (prefix.length + extra > room) break
                    if (prefix.isNotEmpty()) prefix.append(' ')
                    prefix.append(word)
                }
                if (prefix.isNotEmpty()) query = "$prefix $query"
            }
        }
        if (query.length > MAX_QUERY_LENGTH) {
            query = query.substring(0, MAX_QUERY_LENGTH).trim()
        }
        return query
    }

    /** [message] with the instructions to the app taken off either end. */
    internal fun withoutAsk(message: String): String {
        var query = message
        query = askPrefix.replaceFirst(query, "")
        query = askSuffix.replaceFirst(query, "")
        query = askForResults.replaceFirst(query, "")
        query = edgeTrim.replace(query, "").trim()
        // Everything they wrote was the instruction ("search the web").
        return query.ifEmpty { message }
    }

    fun readsAsFollowUp(query: String): Boolean {        if (pointsBack.containsMatchIn(query)) return true
        val words = query.split(" ").size
        return words <= 6 && queryTerms(query).size <= 1
    }

    /** Key words of [previous] that [query] does not already have, in typed case. */
    internal fun carriedTerms(previous: String, query: String): List<String> {
        val wanted = queryTerms(withoutAsk(previous.trim())).toSet()
        val own = queryTerms(query).toSet()
        val carried = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (raw in previous.split(nonWord)) {
            if (raw.isEmpty()) continue
            val word = raw.lowercase()
            if (word !in wanted || word in own || !seen.add(word)) continue
            carried.add(raw)
        }
        return carried
    }

    /**
     * Whether [message] wants live information. Heuristic on purpose —
     * wrong in the cheap direction (an unneeded search costs a second,
     * a skipped needed search is a confident stale answer).
     */
    fun needsWebSearch(message: String): Boolean {
        val text = message.trim()
        if (text.isEmpty()) return false
        if (refusesSearch.containsMatchIn(text)) return false
        if (asksToSearch.containsMatchIn(text)) return true
        if (localTask.containsMatchIn(text)) return false
        if (text.split(whitespace).size < 2) return false
        return wantsCurrent.containsMatchIn(text) || mentionsYear.containsMatchIn(text)
    }

    /** The words in a query that say what it is about, once each (lowercased). */
    fun queryTerms(query: String): List<String> {
        val terms = mutableListOf<String>()
        for (raw in query.split(nonWord)) {
            if (raw.isEmpty()) continue
            val word = raw.lowercase()
            val meaningful = (word.length >= 4 && word !in stopWords) ||
                acronym.matches(raw)
            if (!meaningful || word in terms) continue
            terms.add(word)
        }
        return terms
    }

    /** Whether [haystack] has [term] at the start of a word (prefix, not infix). */
    internal fun mentions(haystack: String, term: String): Boolean {
        var from = 0
        while (true) {
            val at = haystack.indexOf(term, from)
            if (at < 0) return false
            if (at == 0 || !wordChar.containsMatchIn(haystack[at - 1].toString())) return true
            from = at + 1
        }
    }

    /**
     * [query] as keywords for engines that cannot read a question (Bing).
     * Drops filler + low-signal verbs, moves time words to the end.
     */
    fun keywordQuery(query: String): String {
        val kept = mutableListOf<String>()
        val whenWords = mutableListOf<String>()
        for (raw in query.trim().split(whitespace)) {
            var word = edgePunctuation.replace(raw, "")
            word = possessive.replaceFirst(word, "")
            if (word.isEmpty()) continue
            val lower = word.lowercase()
            if (lower in fillerWords || lower in lowSignalVerbs) continue
            (if (lower in timeWords) whenWords else kept).add(word)
        }
        if (kept.isEmpty()) return query.trim()
        return (kept + whenWords).joinToString(" ")
    }

    /**
     * A second phrasing of [keywords] when the first found nothing on point.
     * Null when there is no different phrasing to try.
     */
    fun retryKeywordQuery(keywords: String): String? {
        val words = keywords.trim().split(whitespace).filter { it.isNotEmpty() }.toMutableList()
        if (words.isEmpty()) return null
        val joined = words.joinToString(" ")
        val recency = words.filter { it.lowercase() in recencyWords }
        val rest = words.filter { it.lowercase() !in recencyWords }.toMutableList()
        if (recency.isEmpty() && rest.size >= 2) {
            val first = rest[0]
            rest[0] = rest[1]
            rest[1] = first
        }
        val retry = (rest + recency).joinToString(" ")
        return if (retry == joined) null else retry
    }
}
