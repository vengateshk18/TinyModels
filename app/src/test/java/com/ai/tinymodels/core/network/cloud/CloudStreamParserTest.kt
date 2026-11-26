package com.ai.tinymodels.core.network.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudStreamParserTest {

    @Test
    fun `delta content is parsed from an SSE data line`() {
        val chunk = CloudStreamParser.parse(
            """data: {"choices":[{"delta":{"content":"Hi"}}]}"""
        )

        assertEquals(CloudChunk.Delta("Hi"), chunk)
    }

    @Test
    fun `done sentinel ends the stream`() {
        assertEquals(CloudChunk.Done, CloudStreamParser.parse("data: [DONE]"))
    }

    @Test
    fun `error payload becomes a failure`() {
        val chunk = CloudStreamParser.parse(
            """data: {"error":{"message":"quota exceeded"}}"""
        )

        assertEquals(CloudChunk.Failure("quota exceeded"), chunk)
    }

    @Test
    fun `blank and comment lines are ignored`() {
        assertNull(CloudStreamParser.parse(""))
        assertNull(CloudStreamParser.parse("   "))
        assertNull(CloudStreamParser.parse(": keep-alive"))
        assertNull(CloudStreamParser.parse("event: message"))
    }

    @Test
    fun `non streaming message content is also accepted`() {
        val chunk = CloudStreamParser.parse(
            """data: {"choices":[{"message":{"content":"Hello"}}]}"""
        )

        assertEquals(CloudChunk.Delta("Hello"), chunk)
    }

    @Test
    fun `empty delta content is ignored`() {
        assertNull(CloudStreamParser.parse("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(CloudStreamParser.parse("""data: {"choices":[{"delta":{"content":""}}]}"""))
    }
}
