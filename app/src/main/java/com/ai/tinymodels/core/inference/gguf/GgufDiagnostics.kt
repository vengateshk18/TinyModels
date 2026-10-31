package com.ai.tinymodels.core.inference.gguf

import com.ai.tinymodels.domain.model.isVisionProjectorFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cheap diagnostics for a `.gguf` file: reads the 24-byte header directly and
 * recognises multimodal projectors (mmproj). Used for logging and for
 * actionable load-failure messages.
 *
 * Never throws: a missing, truncated or corrupt file still produces a
 * description so the failure can be diagnosed from logcat alone.
 */
internal object GgufDiagnostics {

    const val GGUF_MAGIC = "GGUF"

    /** GGUF header is magic(4) + version(4) + tensorCount(8) + kvCount(8). */
    private const val HEADER_BYTES = 24

    data class Header(
        val bytesRead: Int,
        val isGguf: Boolean,
        val rawMagicHex: String,
        val version: Long?,
        val tensorCount: Long?,
        val kvCount: Long?,
        val ioError: String? = null,
    )

    fun readHeader(file: File): Header {
        val buffer = ByteArray(HEADER_BYTES)
        val read = try {
            file.inputStream().use { input ->
                var total = 0
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
                total
            }
        } catch (t: Throwable) {
            return Header(
                bytesRead = -1,
                isGguf = false,
                rawMagicHex = "io-error",
                version = null,
                tensorCount = null,
                kvCount = null,
                ioError = t.message ?: t.javaClass.simpleName,
            )
        }

        val isGguf = read >= 4 && String(buffer, 0, 4, Charsets.US_ASCII) == GGUF_MAGIC
        return Header(
            bytesRead = read,
            isGguf = isGguf,
            rawMagicHex = buffer.toHex(0, minOf(read, 4)),
            version = if (read >= 8) leUInt32(buffer, 4) else null,
            tensorCount = if (read >= 16) leUInt64(buffer, 8) else null,
            kvCount = if (read >= 24) leUInt64(buffer, 16) else null,
        )
    }

    /**
     * `mmproj` / `projector` in the file name is how llama.cpp distributes a
     * vision projector next to the main model.
     */
    fun looksLikeProjectorName(name: String): Boolean = name.isVisionProjectorFile()

    /** llama.cpp stores CLIP vision projectors under the `clip` architecture. */
    fun isProjectorArchitecture(architecture: String?): Boolean =
        architecture?.equals("clip", ignoreCase = true) == true

    /** One-line description for logcat. */
    fun describe(file: File): String {
        val header = readHeader(file)
        return buildString {
            append("name=").append(file.name)
            append(" size=").append(file.length())
            append(" path=").append(file.absolutePath)
            append(" magic=").append(if (header.isGguf) GGUF_MAGIC else "NOT-GGUF")
            append(" raw=").append(header.rawMagicHex)
            header.ioError?.let { append(" ioError=").append(it) }
            header.version?.let { append(" version=").append(it) }
            header.tensorCount?.let { append(" tensors=").append(it) }
            header.kvCount?.let { append(" kv=").append(it) }
        }
    }

    /**
     * User- and log-facing reason for a failed native load. Kept pure so it can
     * be unit-tested without the native library.
     */
    fun failureReason(
        header: Header,
        metadata: GgufMetadataReader.Metadata?,
        isProjector: Boolean,
    ): String = buildString {
        if (!header.isGguf) {
            append("File is not a valid GGUF (")
            header.ioError?.let { append("read error: ").append(it).append("; ") }
            append("first bytes: ")
            append(header.rawMagicHex.ifBlank { "empty" })
            append("; expected 47 47 55 46 = \"GGUF\"). ")
        } else {
            append("GGUF header ok (v").append(header.version ?: "?")
            header.tensorCount?.let { append(", ").append(it).append(" tensors") }
            append("). ")
        }
        if (isProjector) {
            append("This looks like an mmproj vision projector, which cannot run as a language model. ")
            append("Load the main *.gguf and keep the mmproj beside it for vision.")
        } else if (metadata == null) {
            append("Model metadata could not be parsed; the file may be incomplete or corrupt.")
        } else {
            append("Architecture=").append(metadata.architecture ?: "unknown")
            metadata.contextLength?.let { append(", ctx=").append(it) }
            append(". Check the llama.cpp/ggml native logs for the exact loader error.")
        }
    }

    private fun ByteArray.toHex(from: Int, to: Int): String =
        (from until to).joinToString(" ") { "%02X".format(this[it].toInt() and 0xFF) }

    private fun leUInt32(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL

    private fun leUInt64(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).long
}
