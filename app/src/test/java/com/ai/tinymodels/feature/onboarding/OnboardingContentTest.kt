package com.ai.tinymodels.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Content contract for the first-run intro: it must explain real, shipped
 * features — and never advertise something the app cannot do.
 */
class OnboardingContentTest {

    private val pages = OnboardingPages.pages

    @Test
    fun `the intro is a short tour, not a manual`() {
        assertTrue("expected 4–6 pages, got ${pages.size}", pages.size in 4..6)
    }

    @Test
    fun `every page has a real explanation`() {
        pages.forEach { page ->
            assertTrue("${page.id} needs a title", page.title.isNotBlank())
            assertTrue("${page.id} body is too short", page.body.length >= 40)
        }
    }

    @Test
    fun `pages are distinct`() {
        assertEquals(pages.size, pages.map { it.id }.toSet().size)
        assertEquals(pages.size, pages.map { it.title }.toSet().size)
        assertEquals(pages.size, pages.map { it.art }.toSet().size)
    }

    @Test
    fun `the tour starts with privacy and ends with tuning`() {
        assertEquals(OnboardingArt.PRIVACY, pages.first().art)
        assertEquals(OnboardingArt.TUNING, pages.last().art)
    }

    @Test
    fun `copy never promises unimplemented features`() {
        val banned = listOf("gguf", "llama.cpp", "cloud sync", "subscription")
        pages.forEach { page ->
            val text = "${page.title} ${page.body}".lowercase()
            banned.forEach { term ->
                assertTrue("'$term' is not shipped and must not be promised", term !in text)
            }
        }
    }
}
