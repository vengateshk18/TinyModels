package com.ai.tinymodels.feature.models

import com.ai.tinymodels.domain.model.MobileFit
import com.ai.tinymodels.domain.model.ModelFormat
import com.ai.tinymodels.domain.model.ModelSummary
import com.ai.tinymodels.domain.model.isVisionProjectorFile
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Everything a browse card shows that is not a raw field of [ModelSummary]:
 * the repo owner, the runnable format, the size/quantisation of the variant a
 * phone would download, and whether the repo ships a vision projector.
 *
 * Pure and UI-free so it can be unit-tested — the card only formats the
 * values it returns.
 */
data class ModelCardFacts(
    val author: String?,
    val format: ModelFormat?,
    val parameterBillions: Double?,
    val quantization: String?,
    val contextLength: Int?,
    val hasVision: Boolean
) {
    companion object {

        /** `1.7` -> "1.7B", `0.27` -> "270M", `null` -> null. */
        fun formatParameters(billions: Double?): String? {
            if (billions == null || billions <= 0.0) return null
            if (billions >= 1.0) {
                val rounded = (billions * 10).roundToLong() / 10.0
                return if (rounded % 1.0 == 0.0) {
                    "${rounded.toLong()}B"
                } else {
                    String.format(Locale.US, "%.1fB", rounded)
                }
            }
            return "${(billions * 1000).roundToLong()}M"
        }

        /** `32768` -> "32K", `1048576` -> "1M", `null` -> null. */
        fun formatContext(tokens: Int?): String? {
            if (tokens == null || tokens <= 0) return null
            return when {
                tokens >= 1_048_576 -> scaled(tokens / 1_048_576.0, "M")
                tokens >= 1024 -> scaled(tokens / 1024.0, "K")
                else -> tokens.toString()
            }
        }

        fun of(model: ModelSummary): ModelCardFacts {
            // Vision projectors share `.gguf` but are not models — never let a
            // projector decide the repo's format or quantisation.
            val runnableFiles = model.siblings.filter { !it.isVisionProjectorFile() }
            return ModelCardFacts(
                author = model.author.takeIf { it.isNotBlank() }
                    ?: model.modelId.substringBefore('/', "").takeIf { it.isNotBlank() },
                format = primaryFormat(runnableFiles),
                parameterBillions = MobileFit.smallestParamsBillions(model),
                quantization = quantizationOf(
                    files = runnableFiles,
                    tags = model.tags,
                    repoName = model.modelId.substringAfterLast('/')
                ),
                contextLength = model.contextLength?.takeIf { it > 0 },
                hasVision = model.siblings.any { it.isVisionProjectorFile() }
            )
        }

        // ---- format ----

        private fun primaryFormat(files: List<String>): ModelFormat? =
            files.mapNotNull { it.formatOf() }
                .groupingBy { it }
                .eachCount()
                .maxWithOrNull(
                    compareBy<Map.Entry<ModelFormat, Int>> { it.value }
                        .thenByDescending { it.key.ordinal }
                )
                ?.key

        private fun String.formatOf(): ModelFormat? = when {
            endsWith(".gguf", ignoreCase = true) -> ModelFormat.GGUF
            endsWith(".litertlm", ignoreCase = true) -> ModelFormat.LITERTLM
            endsWith(".task", ignoreCase = true) -> ModelFormat.TASK
            endsWith(".tflite", ignoreCase = true) -> ModelFormat.TFLITE
            else -> null
        }

        // ---- quantisation ----

        /**
         * One quantisation token: `q4_k_m`, `iq2_xs`, `f16`, `bf16`, `nvfp4`,
         * `int4`, `8-bit`… Anchored on non-alphanumerics so `Q4` inside
         * `QAD-Q4_0` matches but `qwen3` or `270m` never do.
         */
        private val QUANT_TOKEN = Regex(
            """(?i)(?<![a-z0-9])(""" +
                """iq[1-4](?:_[a-z0-9]{1,4}){0,3}|""" +
                """q[1-8](?:_[a-z0-9]{1,4}){0,3}|""" +
                """nvfp4|mxfp4|bf16|fp16|f16|fp32|f32|fp8|f8|int[248]|[248][-_ ]?bit""" +
                """)(?![a-z0-9])"""
        )

        /**
         * Quants a phone should ship by default, most preferred first. Repos
         * with several variants advertise the one users would actually pick,
         * not whichever file the API happened to list first.
         */
        private val PREFERRED_QUANTS = listOf(
            "Q4_K_M", "Q4_K_S", "Q4_0", "IQ4_XS", "IQ4_NL",
            "Q5_K_M", "Q5_K_S", "Q5_0", "Q6_K", "Q8_0",
            "Q3_K_M", "Q3_K_S", "IQ3_XXS", "IQ3_M", "Q2_K",
            "BF16", "FP16", "FP32", "NVFP4", "MXFP4"
        )

        private fun quantizationOf(
            files: List<String>,
            tags: List<String>,
            repoName: String
        ): String? {
            // File names are authoritative; tags and the repo name cover repos
            // that omit the quant from individual file names
            // (e.g. "model.gguf" + tag "q8_0", or "…-Q4_K_M-GGUF").
            val tokens = buildList {
                files.forEach { name -> QUANT_TOKEN.findAll(name).forEach { add(normalizeQuant(it.value)) } }
                tags.forEach { tag -> QUANT_TOKEN.findAll(tag).forEach { add(normalizeQuant(it.value)) } }
                QUANT_TOKEN.findAll(repoName).forEach { add(normalizeQuant(it.value)) }
            }
            if (tokens.isEmpty()) return null
            return PREFERRED_QUANTS.firstOrNull { it in tokens } ?: tokens.first()
        }

        private fun normalizeQuant(token: String): String =
            when (val upper = token.uppercase(Locale.US).replace('-', '_')) {
                "F16" -> "FP16"
                "F32" -> "FP32"
                "F8" -> "FP8"
                "4BIT", "INT4" -> "INT4"
                "8BIT", "INT8" -> "INT8"
                else -> upper
            }

        private fun scaled(value: Double, suffix: String): String {
            val rounded = (value * 10).roundToLong() / 10.0
            return if (rounded % 1.0 == 0.0) {
                "${rounded.toLong()}$suffix"
            } else {
                String.format(Locale.US, "%.1f%s", rounded, suffix)
            }
        }
    }
}
