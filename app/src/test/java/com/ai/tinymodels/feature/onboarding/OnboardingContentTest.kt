package com.ai.tinymodels.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Content contract for the first-run intro: it must explain real, shipped
 * features — and never oversell them.
 *
 * The copy lives in `app/src/main/res/values/strings_onboarding.xml`; unit
 * tests run with the app module as cwd, so this test reads the English
 * resource file directly instead of asserting on Android types.
 */
class OnboardingContentTest {

    private fun loadEnglish(): Map<String, String> {
        val file = File("src/main/res/values/strings_onboarding.xml")
        assertTrue("strings_onboarding.xml not found at ${file.absolutePath}", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.trim()
        }
    }

    private data class ExpectedPage(
        val id: String,
        val titleKey: String,
        val bodyKey: String,
        val art: OnboardingArt
    )

    private val expected = listOf(
        ExpectedPage("privacy", "onboarding_privacy_title", "onboarding_privacy_body", OnboardingArt.PRIVACY),
        ExpectedPage("download", "onboarding_download_title", "onboarding_download_body", OnboardingArt.DOWNLOAD),
        ExpectedPage("chat", "onboarding_chat_title", "onboarding_chat_body", OnboardingArt.CHAT),
        ExpectedPage("personalize", "onboarding_personalize_title", "onboarding_personalize_body", OnboardingArt.PERSONALIZE),
        ExpectedPage("ready", "onboarding_ready_title", "onboarding_ready_body", OnboardingArt.READY)
    )

    @Test
    fun `the tour is short and matches the expected pages in order`() {
        assertTrue("expected 4–6 pages, got ${OnboardingPages.pages.size}", OnboardingPages.pages.size in 4..6)
        assertEquals(
            "pages must be ${expected.map { it.id }} in order",
            expected.map { it.id },
            OnboardingPages.pages.map { it.id }
        )
    }

    @Test
    fun `every page maps to its resource keys`() {
        OnboardingPages.pages.zip(expected).forEach { (page, e) ->
            assertEquals(e.id, page.id)
            assertEquals(e.art, page.art)
        }
    }

    @Test
    fun `every page has a real explanation in the English copy`() {
        val res = loadEnglish()
        OnboardingPages.pages.zip(expected).forEach { (page, e) ->
            val title = res[e.titleKey]
            val body = res[e.bodyKey]
            assertTrue("${page.id} needs a title", !title.isNullOrBlank())
            assertTrue("${page.id} body is too short", (body?.length ?: 0) >= 40)
        }
    }

    @Test
    fun `pages are distinct`() {
        val pages = OnboardingPages.pages
        assertEquals(pages.size, pages.map { it.id }.toSet().size)
        assertEquals(pages.size, pages.map { it.art }.toSet().size)
    }

    @Test
    fun `the tour starts with privacy and includes personalization and readiness`() {
        assertEquals(OnboardingArt.PRIVACY, OnboardingPages.pages.first().art)
        assertEquals(OnboardingArt.PERSONALIZE, OnboardingPages.pages[3].art)
        assertEquals(OnboardingArt.READY, OnboardingPages.pages.last().art)
    }

    @Test
    fun `copy covers language theme and widget in the updated tour`() {
        val res = loadEnglish()
        val all = res.values.joinToString(" ").lowercase()
        assertTrue("language should be explained", "language" in all)
        assertTrue("theme/colors should be explained", "theme" in all || "color" in all)
        assertTrue("widget should be explained", "widget" in all)
    }

    @Test
    fun `copy never promises unimplemented features`() {
        val banned = listOf("gguf", "llama.cpp", "cloud sync", "subscription")
        val res = loadEnglish()
        banned.forEach { term ->
            res.values.forEach { text ->
                assertTrue(
                    "'$term' is not shipped and must not be promised: $text",
                    term !in text.lowercase()
                )
            }
        }
    }
}
