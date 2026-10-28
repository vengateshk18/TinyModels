package com.ai.tinymodels.core.inference.gguf

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin reader for the GGUF file format (v2/v3).
 *
 * GGUF stores model metadata (architecture, context length, quantization,
 * vocab size, …) in a typed key/value block right after the header. This
 * reader extracts that metadata without loading the (often multi-GB) tensor
 * data, so the app can discover a model's max context length (and other
 * limits) directly from the file — something the MediaPipe `.task` path
 * could not do reliably.
 *
 * All multi-byte integers are little-endian per the GGUF spec.
 */
object GgufMetadataReader {

    private const val MAGIC = 0x46554747 // "GGUF" little-endian
    private const val GGUF_VERSION_2 = 2
    private const val GGUF_VERSION_3 = 3

    data class Metadata(
        val magic: Int,
        val version: Int,
        val tensorCount: Long,
        val kvCount: Long,
        val values: Map<String, Any>,
    ) {
        /** Architecture string (e.g. "llama", "qwen2", "gemma"). */
        val architecture: String? get() = values["general.architecture"] as? String

        /** Max context length (tokens) declared by the model. */
        val contextLength: Int? get() {
            val arch = architecture
            val candidates = listOfNotNull(
                arch?.let { "$it.context_length" },
                "general.context_length",
                "llama.context_length",
                "context_length",
            )
            for (k in candidates) {
                (values[k] as? Number)?.toInt()?.let { if (it > 0) return it }
            }
            return null
        }

        /** Embedding dimension. */
        val embeddingLength: Int? get() {
            val arch = architecture
            val candidates = listOfNotNull(arch?.let { "$it.embedding_length" }, "general.embedding_length")
            for (k in candidates) (values[k] as? Number)?.toInt()?.let { if (it > 0) return it }
            return null
        }

        /** Number of transformer blocks/layers. */
        val blockCount: Int? get() {
            val arch = architecture
            val candidates = listOfNotNull(arch?.let { "$it.block_count" }, "general.block_count")
            for (k in candidates) (values[k] as? Number)?.toInt()?.let { if (it > 0) return it }
            return null
        }

        /** Model name if present. */
        val name: String? get() = values["general.name"] as? String

        /** Quantization version (if recorded). */
        val quantizationVersion: Int? get() = (values["general.quantization_version"] as? Number)?.toInt()

        /** File type / quantization label (e.g. "Q4_K_M" sometimes stored). */
        val fileType: Int? get() = (values["general.file_type"] as? Number)?.toInt()

        /** Vocab size if recorded. */
        val vocabSize: Int? get() {
            val arch = architecture
            val candidates = listOfNotNull(arch?.let { "$it.vocab_size" }, "tokenizer.ggml.model")
            // vocab size is usually derived from tokenizer; try explicit keys
            for (k in listOfNotNull(arch?.let { "$it.vocab_size" })) {
                (values[k] as? Number)?.toInt()?.let { if (it > 0) return it }
            }
            return null
        }
    }

    /** Read metadata from [file]. Throws on I/O or format errors. */
    fun read(file: File): Metadata {
        FileInputStream(file).use { fis ->
            val r = LittleEndianReader(fis)
            val magic = r.readUInt32()
            if (magic != MAGIC) throw IllegalArgumentException("Not a GGUF file (bad magic: 0x${magic.toString(16)})")
            val version = r.readUInt32()
            if (version != GGUF_VERSION_2 && version != GGUF_VERSION_3) {
                throw IllegalArgumentException("Unsupported GGUF version: $version")
            }
            val tensorCount = r.readUInt64()
            val kvCount = r.readUInt64()

            val values = LinkedHashMap<String, Any>(kvCount.toInt().coerceAtMost(10_000))
            repeat(kvCount.toInt()) {
                val key = r.readString()
                val type = r.readUInt32()
                val value = readValue(r, type)
                values[key] = value
            }
            return Metadata(magic, version, tensorCount, kvCount, values)
        }
    }

    /** Read a single typed value. */
    private fun readValue(r: LittleEndianReader, type: Int): Any = when (type) {
        0 -> r.readUInt8()          // UINT8
        1 -> r.readInt8()           // INT8
        2 -> r.readUInt16()         // UINT16
        3 -> r.readInt16()          // INT16
        4 -> r.readUInt32()         // UINT32
        5 -> r.readInt32()          // INT32
        6 -> r.readFloat32()        // FLOAT32
        7 -> r.readBool()           // BOOL
        8 -> r.readString()         // STRING
        9 -> readArray(r)           // ARRAY
        10 -> r.readUInt64()        // UINT64
        11 -> r.readInt64()         // INT64
        12 -> r.readFloat64()       // FLOAT64
        else -> throw IllegalArgumentException("Unknown GGUF value type: $type")
    }

    private fun readArray(r: LittleEndianReader): List<Any> {
        val elemType = r.readUInt32()
        val count = r.readUInt64()
        val n = count.toInt().coerceAtMost(1_000_000)
        val out = ArrayList<Any>(n)
        repeat(n) { out += readValue(r, elemType.toInt()) }
        return out
    }

    /** Little-endian primitive reader over an InputStream. */
    private class LittleEndianReader(private val input: java.io.InputStream) {
        private val buf4 = ByteArray(4)
        private val buf8 = ByteArray(8)
        private val buf1 = ByteArray(1)

        fun readUInt8(): Int {
            readFully(buf1)
            return buf1[0].toInt() and 0xFF
        }
        fun readInt8(): Byte {
            readFully(buf1)
            return buf1[0]
        }
        fun readUInt16(): Int {
            readFully(buf4, 0, 2)
            return (buf4[0].toInt() and 0xFF) or ((buf4[1].toInt() and 0xFF) shl 8)
        }
        fun readInt16(): Short = readUInt16().toShort()
        fun readUInt32(): Int {
            readFully(buf4)
            val bb = ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN)
            return bb.int
        }
        fun readInt32(): Int = readUInt32()
        fun readUInt64(): Long {
            readFully(buf8)
            val bb = ByteBuffer.wrap(buf8).order(ByteOrder.LITTLE_ENDIAN)
            return bb.long
        }
        fun readInt64(): Long = readUInt64()
        fun readFloat32(): Float {
            readFully(buf4)
            return ByteBuffer.wrap(buf4).order(ByteOrder.LITTLE_ENDIAN).float
        }
        fun readFloat64(): Double {
            readFully(buf8)
            return ByteBuffer.wrap(buf8).order(ByteOrder.LITTLE_ENDIAN).double
        }
        fun readBool(): Boolean = readUInt8() != 0
        fun readString(): String {
            val len = readUInt64()
            if (len < 0 || len > 10_000_000) throw IllegalArgumentException("Implausible string length: $len")
            val bytes = ByteArray(len.toInt())
            readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }
        private fun readFully(b: ByteArray, off: Int = 0, len: Int = b.size) {
            var read = 0
            while (read < len) {
                val n = input.read(b, off + read, len - read)
                if (n < 0) throw java.io.EOFException("Unexpected EOF in GGUF")
                read += n
            }
        }
    }
}