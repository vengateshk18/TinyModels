package com.ai.tinymodels.core.inference

import com.ai.tinymodels.core.inference.gguf.GgufMetadataReader
import java.io.File

object ModelContextInspector {

    /** Regexes tried (in order) against the file name. Allows an optional
     *  separator (space or -, _, .) before the value, e.g. "ekv 1280". */
    private val FILENAME_PATTERNS = listOf(
        Regex("""ekv\s{0,5}(\d{3,6})""", RegexOption.IGNORE_CASE),
        Regex("""ctx\s{0,5}(\d{3,6})""", RegexOption.IGNORE_CASE)
    )

    fun detectContext(file: File): Int? {
        // GGUF files declare their true context length in metadata — prefer it.
        if (file.name.endsWith(".gguf", ignoreCase = true)) {
            runCatching { GgufMetadataReader.read(file).contextLength }
                .getOrNull()
                ?.takeIf { it in 256..131_072 }
                ?.let { return it }
        }
        return fromFilename(file.name)
    }

    private fun fromFilename(name: String): Int? =
        FILENAME_PATTERNS.firstNotNullOfOrNull { regex ->
            regex.find(name)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it in 256..131_072 }
        }
}
