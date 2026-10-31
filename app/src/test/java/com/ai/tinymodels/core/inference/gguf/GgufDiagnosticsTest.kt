package com.ai.tinymodels.core.inference.gguf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufDiagnosticsTest {

    private fun headerBytes(
        magic: Int = 0x46554747,
        version: Int = 3,
        tensorCount: Long = 291,
        kvCount: Long = 24,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(magic).array())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(version).array())
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(tensorCount).array())
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(kvCount).array())
        out.flush()
        return baos.toByteArray()
    }

    private fun tempFile(bytes: ByteArray, suffix: String = ".gguf"): File =
        File.createTempFile("gguf-diag", suffix).apply {
            writeBytes(bytes)
            deleteOnExit()
        }

    @Test
    fun `reads a valid GGUF header`() {
        val file = tempFile(headerBytes())
        val header = GgufDiagnostics.readHeader(file)

        assertTrue(header.isGguf)
        assertEquals("47 47 55 46", header.rawMagicHex)
        assertEquals(3L, header.version)
        assertEquals(291L, header.tensorCount)
        assertEquals(24L, header.kvCount)
        assertEquals(24, header.bytesRead)
        assertNull(header.ioError)
    }

    @Test
    fun `flags a file that is not GGUF`() {
        val file = tempFile("LITER".toByteArray())
        val header = GgufDiagnostics.readHeader(file)

        assertFalse(header.isGguf)
        assertEquals("4C 49 54 45", header.rawMagicHex)
        assertNull(header.version)
    }

    @Test
    fun `handles a truncated header`() {
        val file = tempFile(byteArrayOf(0x47, 0x47))
        val header = GgufDiagnostics.readHeader(file)

        assertFalse(header.isGguf)
        assertEquals(2, header.bytesRead)
        assertNull(header.version)
        assertNull(header.tensorCount)
    }

    @Test
    fun `reports an unreadable file instead of throwing`() {
        val header = GgufDiagnostics.readHeader(File("/definitely/not/here/model.gguf"))

        assertFalse(header.isGguf)
        assertNotNull(header.ioError)
    }

    @Test
    fun `detects projector file names`() {
        assertTrue(GgufDiagnostics.looksLikeProjectorName("SAGE_A1.5-MMPROJ.gguf"))
        assertTrue(GgufDiagnostics.looksLikeProjectorName("mmproj-model-f16.gguf"))
        assertTrue(GgufDiagnostics.looksLikeProjectorName("Some-Projector.gguf"))
        assertFalse(GgufDiagnostics.looksLikeProjectorName("SAGE-A1.5-Q4_K_M.gguf"))
    }

    @Test
    fun `detects the CLIP projector architecture`() {
        assertTrue(GgufDiagnostics.isProjectorArchitecture("clip"))
        assertTrue(GgufDiagnostics.isProjectorArchitecture("CLIP"))
        assertFalse(GgufDiagnostics.isProjectorArchitecture("llama"))
        assertFalse(GgufDiagnostics.isProjectorArchitecture(null))
    }

    @Test
    fun `failure reason points at mmproj misuse`() {
        val header = GgufDiagnostics.readHeader(tempFile(headerBytes()))
        val metadata = GgufMetadataReader.Metadata(
            magic = 0x46554747,
            version = 3,
            tensorCount = 291,
            kvCount = 3,
            values = mapOf("general.architecture" to "clip")
        )

        val reason = GgufDiagnostics.failureReason(header, metadata, isProjector = true)

        assertTrue(reason.contains("mmproj vision projector"))
        assertTrue(reason.contains("main"))
    }

    @Test
    fun `failure reason reports the bad magic`() {
        val header = GgufDiagnostics.readHeader(tempFile("NOPE".toByteArray()))
        val reason = GgufDiagnostics.failureReason(header, metadata = null, isProjector = false)

        assertTrue(reason.contains("not a valid GGUF"))
        assertTrue(reason.contains("4E 4F 50 45"))
    }

    @Test
    fun `failure reason reports unreadable metadata`() {
        val header = GgufDiagnostics.readHeader(tempFile(headerBytes()))
        val reason = GgufDiagnostics.failureReason(header, metadata = null, isProjector = false)

        assertTrue(reason.contains("metadata could not be parsed"))
    }
}
