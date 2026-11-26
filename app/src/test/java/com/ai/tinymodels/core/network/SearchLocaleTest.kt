package com.ai.tinymodels.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchLocaleTest {

    @Test fun `maps common language tags to ddg kl`() {
        assertEquals("us-en", SearchLocale.ddgKl("en-US"))
        assertEquals("uk-en", SearchLocale.ddgKl("en-GB"))
        assertEquals("in-en", SearchLocale.ddgKl("en-IN"))
        assertEquals("de-de", SearchLocale.ddgKl("de-DE"))
    }

    @Test fun `unknown tags fall back to wt-wt`() {
        assertEquals("wt-wt", SearchLocale.ddgKl("ta-IN"))
        assertEquals("wt-wt", SearchLocale.ddgKl(""))
    }

    @Test fun `accept language includes a quality fallback`() {
        assertEquals("en-US,en;q=0.9", SearchLocale.acceptLanguage("en-US"))
    }
}
