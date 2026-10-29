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
    private const val CACHE_SIZE = 8
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_STRING_LENGTH = 10_000_000L

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

    /**
     * Read metadata from [file]. Throws on I/O or format errors.
     *
     * Arrays are skipped by default. Real GGUFs embed the whole tokenizer as
     * arrays (100k+ strings / ints) which this reader never needs; parsing
     * them dominated load time (seconds on device) and allocated the entire
     * vocabulary into memory. Pass [includeArrays] = true only when the array
     * contents are actually needed.
     *
     * Results are memoized per (path, size, last-modified) so repeated calls
     * — chat entry, runtime init, file-details screen — are effectively free.
     */
    fun read(file: File, includeArrays: Boolean = false): Metadata {
        val key = CacheKey(file.absolutePath, file.length(), file.lastModified(), includeArrays)
        synchronized(cache) { cache[key] }?.let { return it }

        val metadata = parse(file, includeArrays)
        synchronized(cache) { cache[key] = metadata }
        return metadata
    }

    private fun parse(file: File, includeArrays: Boolean): Metadata {
        FileInputStream(file).use { fis ->
            // Buffered I/O: the reader makes many small reads (keys, lengths,
            // scalar values). Reading the file unbuffered was a syscall per
            // byte on device.
            val input = java.io.BufferedInputStream(fis, BUFFER_SIZE)
            val r = LittleEndianReader(input)
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
                val value = readValue(r, type, includeArrays)
                if (value !== SKIPPED) values[key] = value
            }
            return Metadata(magic, version, tensorCount, kvCount, values)
        }
    }

    /** Sentinel for values deliberately dropped (skipped arrays). */
    private val SKIPPED = Any()

    /** Read a single typed value. */
    private fun readValue(r: LittleEndianReader, type: Int, includeArrays: Boolean): Any = when (type) {
        0 -> r.readUInt8()          // UINT8
        1 -> r.readInt8()           // INT8
        2 -> r.readUInt16()         // UINT16
        3 -> r.readInt16()          // INT16
        4 -> r.readUInt32()         // UINT32
        5 -> r.readInt32()          // INT32
        6 -> r.readFloat32()        // FLOAT32
        7 -> r.readBool()           // BOOL
        8 -> r.readString()         // STRING
        9 -> if (includeArrays) readArray(r) else { skipArray(r); SKIPPED } // ARRAY
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
        repeat(n) { out += readValue(r, elemType, includeArrays = true) }
        return out
    }

    private fun skipArray(r: LittleEndianReader) {
        val elemType = r.readUInt32()
        val count = r.readUInt64()
        r.skipElements(elemType, count)
    }

    private data class CacheKey(
        val path: String,
        val size: Long,
        val lastModified: Long,
        val includeArrays: Boolean,
    )

    /** Access-ordered LRU: model files are few; metadata entries are tiny. */
    private val cache = object : LinkedHashMap<CacheKey, Metadata>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Metadata>): Boolean =
            size > CACHE_SIZE
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
            if (len < 0 || len > MAX_STRING_LENGTH) throw IllegalArgumentException("Implausible string length: $len")
            val bytes = ByteArray(len.toInt())
            readFully(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        /**
         * Skip [count] elements of [type] without materializing them. Mirrors
         * the GGUF value encoding so the stream lands exactly after the array.
         */
        fun skipElements(type: Int, count: Long) {
            if (count <= 0) return
            when (type) {
                0, 1, 7 -> skipBytes(elementBytes(count, 1))       // UINT8/INT8/BOOL
                2, 3 -> skipBytes(elementBytes(count, 2))           // UINT16/INT16
                4, 5, 6 -> skipBytes(elementBytes(count, 4))        // UINT32/INT32/FLOAT32
                10, 11, 12 -> skipBytes(elementBytes(count, 8))     // UINT64/INT64/FLOAT64
                8 -> {                                              // STRING
                    var i = 0L
                    while (i < count) {
                        val len = readUInt64()
                        if (len < 0 || len > MAX_STRING_LENGTH) {
                            throw IllegalArgumentException("Implausible string length: $len")
                        }
                        skipBytes(len)
                        i++
                    }
                }
                9 -> {                                              // nested ARRAY
                    var i = 0L
                    while (i < count) {
                        val nestedType = readUInt32()
                        val nestedCount = readUInt64()
                        skipElements(nestedType, nestedCount)
                        i++
                    }
                }
                else -> throw IllegalArgumentException("Unknown GGUF value type: $type")
            }
        }

        private fun elementBytes(count: Long, width: Int): Long {
            if (count < 0 || count > Long.MAX_VALUE / width) {
                throw IllegalArgumentException("Implausible array count: $count")
            }
            return count * width
        }

        private fun skipBytes(len: Long) {
            var remaining = len
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                // skip() is allowed to return 0; advance by reading a byte.
                if (input.read() < 0) throw java.io.EOFException("Unexpected EOF in GGUF")
                remaining--
            }
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