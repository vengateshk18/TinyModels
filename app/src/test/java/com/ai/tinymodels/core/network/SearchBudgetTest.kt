package com.ai.tinymodels.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchBudgetTest {

    @Test fun `null context uses the default`() {
        assertEquals(1_500, SearchBudget.pageChars(null))
    }

    @Test fun `small context gets the floor`() {
        assertEquals(800, SearchBudget.pageChars(1_000))
        assertEquals(800, SearchBudget.pageChars(8_000))
    }

    @Test fun `medium context scales linearly`() {
        assertEquals(1_000, SearchBudget.pageChars(20_000))
    }

    @Test fun `large context is capped`() {
        assertEquals(2_500, SearchBudget.pageChars(65_536))
        assertEquals(2_500, SearchBudget.pageChars(200_000))
    }
}
