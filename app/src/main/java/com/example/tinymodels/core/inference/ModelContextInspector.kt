package com.example.tinymodels.core.inference

import java.io.File

/**
 * Best-effort detector for a `.litertlm` model's compiled context window.
 *
 * There is no single universal algorithm. The container's FlatBuffer metadata
 * (`LlmMetadata.max_num_tokens`) would be the ideal source, but parsing it
 * requires the LiteRT-LM FlatBuffer schema and many community conversions
 * omit the field entirely — a raw byte-scan of the header region produces
 * false positives (tensor dimensions, offsets, etc. all look like plausible
 * token counts), so it is deliberately NOT attempted here.
 *
 * The one reliable, deliberately-encoded source is the FILE NAME: community
 * bundles conventionally include the compiled context in the file name, e.g.
 * `DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm`
 * (ekv4096 → 4096 KV positions) or `...ctx2048...`.
 *
 * Returns null when nothing could be determined — callers must fall back to
 * the user's configured value.
 */
object ModelContextInspector {

    /** Regexes tried (in order) against the file name. */
    private val FILENAME_PATTERNS = listOf(
        Regex("""(?:^|[_\-.])ekv(\d{3,6})(?:[_\-.]|$)""", RegexOption.IGNORE_CASE),
        Regex("""(?:^|[_\-.])ctx(\d{3,6})(?:[_\-.]|$)""", RegexOption.IGNORE_CASE)
    )

    /**
     * Detect the compiled context window (max tokens) for [file], or null.
     * Safe on any file: returns null rather than throwing.
     */
    fun detectContext(file: File): Int? =
        fromFilename(file.name)

    private fun fromFilename(name: String): Int? =
        FILENAME_PATTERNS.firstNotNullOfOrNull { regex ->
            regex.find(name)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.takeIf { it in 256..131_072 }
        }
}