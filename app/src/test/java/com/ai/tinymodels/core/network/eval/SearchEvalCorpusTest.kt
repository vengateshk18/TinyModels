package com.ai.tinymodels.core.network.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEvalCorpusTest {

    @Test
    fun `case ids are unique`() {
        val ids = SearchEvalCorpus.CASES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every case has a query`() {
        assertTrue(SearchEvalCorpus.CASES.all { it.query.isNotBlank() })
    }

    @Test
    fun `the corpus is big enough to be a signal`() {
        assertTrue("corpus has ${SearchEvalCorpus.CASES.size} cases", SearchEvalCorpus.CASES.size >= 20)
    }

    @Test
    fun `every category is represented`() {
        SearchEvalCategory.entries.forEach { category ->
            val n = SearchEvalCorpus.CASES.count { it.category == category }
            assertTrue("category $category has $n cases", n >= 2)
        }
    }

    @Test
    fun `stable facts declare an answer word`() {
        val facts = SearchEvalCorpus.CASES.filter { it.category == SearchEvalCategory.STABLE_FACT }
        assertTrue(facts.all { it.mustContainAny.isNotEmpty() })
    }
}
