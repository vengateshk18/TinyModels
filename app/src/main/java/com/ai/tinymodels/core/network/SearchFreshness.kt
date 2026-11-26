package com.ai.tinymodels.core.network

enum class SearchFreshness {
    NONE, DAY, WEEK, MONTH, YEAR;

    /** DuckDuckGo `df` form value. */
    fun ddgDf(): String? = when (this) {
        NONE -> null
        DAY -> "d"
        WEEK -> "w"
        MONTH -> "m"
        YEAR -> "y"
    }

    /** Brave `freshness` query value. */
    fun braveFreshness(): String? = when (this) {
        NONE -> null
        DAY -> "pd"
        WEEK -> "pw"
        MONTH -> "pm"
        YEAR -> "py"
    }

    /** Tavily `days` parameter. */
    fun tavilyDays(): Int? = when (this) {
        NONE -> null
        DAY -> 1
        WEEK -> 7
        MONTH -> 30
        YEAR -> 365
    }
}
