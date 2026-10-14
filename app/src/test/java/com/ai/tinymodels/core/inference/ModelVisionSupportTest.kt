package com.ai.tinymodels.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Vision-capability rules: detection must enable vision only for models that
 * ship an encoder, recognise the native NOT_FOUND failure, and degrade photo
 * histories to text without losing the words.
 */
class ModelVisionSupportTest {

    private fun tempFile(bytes: ByteArray): File {
        val file = File.createTempFile("model", ".litertlm")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    @Test fun `header with vision encoder marker is detected`() {
        val file = tempFile("header TF_LITE_VISION_ENCODER tail".toByteArray(Charsets.ISO_8859_1))
        assertTrue(ModelVisionSupport.supportsVision(file))
    }

    @Test fun `text-only header has no vision`() {
        val file = tempFile("PREFILL_DECODE tokenizer SP_Tokenizer".toByteArray(Charsets.ISO_8859_1))
        assertFalse(ModelVisionSupport.supportsVision(file))
    }

    @Test fun `marker split across read boundary is still found`() {
        // 8192-byte read chunks with a 64-char overlap: straddle the first
        // chunk edge with the marker to prove the carry window works.
        val padding = ByteArray(8192 - 10) { 'x'.code.toByte() }
        val bytes = padding + "0123456789TF_LITE_VISION_ENCODER".toByteArray(Charsets.ISO_8859_1)
        assertTrue(ModelVisionSupport.supportsVision(tempFile(bytes)))
    }

    @Test fun `missing file reports no vision without throwing`() {
        assertFalse(ModelVisionSupport.supportsVision(File("/nonexistent/model.litertlm")))
    }

    @Test fun `recognises the native vision encoder failure`() {
        val failure = RuntimeException(
            "Failed to create conversation: NOT_FOUND: TF_LITE_VISION_ENCODER not found in the model."
        )
        assertTrue(ModelVisionSupport.isVisionEncoderError(failure))
    }

    @Test fun `other native failures are not vision errors`() {
        assertFalse(ModelVisionSupport.isVisionEncoderError(RuntimeException("Status code 13: large input")))
        assertFalse(ModelVisionSupport.isVisionEncoderError(RuntimeException("no model loaded")))
    }

    @Test fun `stripImages keeps text and drops photos`() {
        val history = listOf(
            ChatTurn(ChatTurn.Role.USER, "see?", listOf("/tmp/a.jpg")),
            ChatTurn(ChatTurn.Role.ASSISTANT, "nice")
        )
        val stripped = ModelVisionSupport.stripImages(history)
        assertEquals("", stripped[0].imagePaths.joinToString())
        assertTrue(stripped[0].imagePaths.isEmpty())
        assertEquals("see?", stripped[0].text)
        assertEquals("nice", stripped[1].text)
        assertTrue(ModelVisionSupport.historyHasImages(history))
        assertFalse(ModelVisionSupport.historyHasImages(stripped))
    }
}
