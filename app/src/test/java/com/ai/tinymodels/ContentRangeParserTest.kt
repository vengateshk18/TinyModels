package com.ai.tinymodels

import com.ai.tinymodels.domain.usecase.model.ContentRangeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Content-Range` is the only way to know that an append really continues the
 * bytes we already have. A wrong offset means a corrupt model, so the parsing
 * has to be strict and the failure explicit.
 */
class ContentRangeParserTest {

    @Test
    fun `parses a normal range`() {
        val range = ContentRangeParser.parse("bytes 250-999/1000")!!

        assertEquals(250L, range.start)
        assertEquals(999L, range.end)
        assertEquals(1000L, range.total)
    }

    @Test
    fun `parses an unknown total as zero`() {
        val range = ContentRangeParser.parse("bytes 0-99/*")!!

        assertEquals(0L, range.start)
        assertEquals(0L, range.total)
    }

    @Test
    fun `missing header is null`() {
        assertNull(ContentRangeParser.parse(null))
        assertNull(ContentRangeParser.parse(""))
    }

    @Test
    fun `malformed header is null`() {
        assertNull(ContentRangeParser.parse("bytes garbage"))
        assertNull(ContentRangeParser.parse("items 0-9/10"))
        assertNull(ContentRangeParser.parse("bytes 0-9"))
    }

    @Test
    fun `total smaller than the range is rejected`() {
        assertNull(ContentRangeParser.parse("bytes 500-100/1000"))
    }
}
