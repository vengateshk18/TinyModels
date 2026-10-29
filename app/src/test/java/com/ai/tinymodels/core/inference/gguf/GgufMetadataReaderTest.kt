package com.ai.tinymodels.core.inference.gguf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufMetadataReaderTest {

    /** Build a minimal GGUF v3 file with the given metadata KV pairs. */
    private fun buildGguf(
        kv: List<Pair<String, Any>>,
        version: Int = 3,
        tensorCount: Long = 0L,
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        // magic "GGUF" little-endian = 0x46554747
        writeUInt32(out, 0x46554747)
        writeUInt32(out, version)
        writeUInt64(out, tensorCount)
        writeUInt64(out, kv.size.toLong())
        for ((key, value) in kv) {
            writeString(out, key)
            when (value) {
                is String -> { writeUInt32(out, 8); writeString(out, value) }
                is Int -> { writeUInt32(out, 4); writeInt32(out, value) }
                is Long -> { writeUInt32(out, 11); writeInt64(out, value) }
                is Float -> { writeUInt32(out, 6); writeFloat32(out, value) }
                is Boolean -> { writeUInt32(out, 7); out.write(if (value) 1 else 0) }
                else -> throw IllegalArgumentException("unsupported test value type")
            }
        }
        out.flush()
        return baos.toByteArray()
    }

    private fun writeUInt32(out: DataOutputStream, v: Int) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        out.write(b)
    }
    private fun writeInt32(out: DataOutputStream, v: Int) = writeUInt32(out, v)
    private fun writeUInt64(out: DataOutputStream, v: Long) {
        val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
        out.write(b)
    }
    private fun writeInt64(out: DataOutputStream, v: Long) = writeUInt64(out, v)
    private fun writeFloat32(out: DataOutputStream, v: Float) {
        val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
        out.write(b)
    }
    private fun writeString(out: DataOutputStream, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        writeUInt64(out, bytes.size.toLong())
        out.write(bytes)
    }

    @Test
    fun `reads magic version and kv count`() {
        val bytes = buildGguf(listOf("general.architecture" to "llama"))
        val f = File.createTempFile("gguf", ".gguf").apply { writeBytes(bytes); deleteOnExit() }
        val md = GgufMetadataReader.read(f)
        assertEquals(0x46554747, md.magic)
        assertEquals(3, md.version)
        assertEquals(1L, md.kvCount)
        assertEquals("llama", md.architecture)
    }

    @Test
    fun `extracts context length from arch-prefixed key`() {
        val bytes = buildGguf(listOf(
            "general.architecture" to "llama",
            "llama.context_length" to 8192,
            "general.name" to "Llama-3.2-1B",
        ))
        val f = File.createTempFile("gguf", ".gguf").apply { writeBytes(bytes); deleteOnExit() }
        val md = GgufMetadataReader.read(f)
        assertEquals(8192, md.contextLength)
        assertEquals("Llama-3.2-1B", md.name)
        assertEquals("llama", md.architecture)
    }

    @Test
    fun `falls back to general context length`() {
        val bytes = buildGguf(listOf(
            "general.context_length" to 4096,
        ))
        val f = File.createTempFile("gguf", ".gguf").apply { writeBytes(bytes); deleteOnExit() }
        val md = GgufMetadataReader.read(f)
        assertEquals(4096, md.contextLength)
        assertNull(md.architecture)
    }

    @Test
    fun `reads int float bool string and array values`() {
        val bytes = buildGguf(listOf(
            "a.uint" to 7,
            "a.str" to "hello",
            "a.bool" to true,
            "a.float" to 1.5f,
        ))
        val f = File.createTempFile("gguf", ".gguf").apply { writeBytes(bytes); deleteOnExit() }
        val md = GgufMetadataReader.read(f)
        assertEquals(7, md.values["a.uint"])
        assertEquals("hello", md.values["a.str"])
        assertEquals(true, md.values["a.bool"])
        assertEquals(1.5f, md.values["a.float"])
    }

    @Test
    fun `reads array values when includeArrays is true`() {
        // manually append an array KV
        val baos = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(baos)
        writeUInt32(out, 0x46554747)
        writeUInt32(out, 3)
        writeUInt64(out, 0L)
        writeUInt64(out, 1L)
        writeString(out, "arr")
        writeUInt32(out, 9) // ARRAY
        writeUInt32(out, 4) // UINT32 elem type
        writeUInt64(out, 3L)
        writeUInt32(out, 1); writeUInt32(out, 2); writeUInt32(out, 3)
        out.flush()
        val f = File.createTempFile("gguf", ".gguf").apply { writeBytes(baos.toByteArray()); deleteOnExit() }
        val md = GgufMetadataReader.read(f, includeArrays = true)
        assertEquals(listOf(1, 2, 3), md.values["arr"])
    }

    @Test
    fun `skips tokenizer arrays by default and keeps parsing later keys`() {
        val baos = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(baos)
        writeUInt32(out, 0x46554747)
        writeUInt32(out, 3)
        writeUInt64(out, 0L)
        writeUInt64(out, 3L)
        // Scalar before the array.
        writeString(out, "general.architecture")
        writeUInt32(out, 8)
        writeString(out, "llama")
        // A realistic tokenizer array (50k strings) that must be skipped.
        writeString(out, "tokenizer.ggml.tokens")
        writeUInt32(out, 9) // ARRAY
        writeUInt32(out, 8) // STRING element type
        writeUInt64(out, 50_000L)
        repeat(50_000) { writeString(out, "tok$it") }
        // Scalar AFTER the array — proves the stream lands at the right offset.
        writeString(out, "general.name")
        writeUInt32(out, 8)
        writeString(out, "After-Array")
        out.flush()

        val f = File.createTempFile("gguf", ".gguf").apply { writeBytes(baos.toByteArray()); deleteOnExit() }
        val md = GgufMetadataReader.read(f)
        assertEquals("llama", md.architecture)
        assertEquals("After-Array", md.name)
        assertNull(md.values["tokenizer.ggml.tokens"])
    }

    @Test
    fun `memoizes metadata per file version`() {
        val bytes = buildGguf(listOf("general.architecture" to "llama"))
        val f = File.createTempFile("gguf", ".gguf").apply { writeBytes(bytes); deleteOnExit() }
        val first = GgufMetadataReader.read(f)
        val second = GgufMetadataReader.read(f)
        assertSame(first, second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects bad magic`() {
        val bytes = ByteArray(24)
        val f = File.createTempFile("gguf", ".gguf").apply { writeBytes(bytes); deleteOnExit() }
        GgufMetadataReader.read(f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported version`() {
        val baos = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(baos)
        writeUInt32(out, 0x46554747)
        writeUInt32(out, 99)
        writeUInt64(out, 0L); writeUInt64(out, 0L)
        out.flush()
        val f = File.createTempFile("gguf", ".gguf").apply { writeBytes(baos.toByteArray()); deleteOnExit() }
        GgufMetadataReader.read(f)
    }

    @Test
    fun `contextLength null when absent`() {
        val bytes = buildGguf(listOf("general.architecture" to "llama"))
        val f = File.createTempFile("gguf", ".gguf").apply { writeBytes(bytes); deleteOnExit() }
        val md = GgufMetadataReader.read(f)
        assertNull(md.contextLength)
    }
}