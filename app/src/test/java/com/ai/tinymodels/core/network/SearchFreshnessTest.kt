package com.ai.tinymodels.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFreshnessTest {

    @Test fun `detects day recency`() {
        assertEquals(SearchFreshness.DAY, WebSearchQuery.freshnessFor("news today india"))
        assertEquals(SearchFreshness.DAY, WebSearchQuery.freshnessFor("gold price live"))
    }

    @Test fun `detects week and month`() {
        assertEquals(SearchFreshness.WEEK, WebSearchQuery.freshnessFor("what happened this week in tech"))
        assertEquals(SearchFreshness.MONTH, WebSearchQuery.freshnessFor("latest iphone release"))
    }

    @Test fun `detects year`() {
        assertEquals(SearchFreshness.YEAR, WebSearchQuery.freshnessFor("best laptops this year"))
    }

    @Test fun `defaults to none`() {
        assertEquals(SearchFreshness.NONE, WebSearchQuery.freshnessFor("how to reverse a string in python"))
    }
}
