package com.ai.tinymodels.domain.model

/**
 * Size heuristics that answer "can this model plausibly run on a phone?"
 * from catalog metadata alone (repo id + sibling file names — the list API
 * carries no file sizes).
 *
 * Used to (a) hide repos whose smallest variant is clearly too large and
 * (b) badge entries we're confident about with "Mobile-ready".
 */
object MobileFit {

    /** Smallest parsed variant above this many params hides the whole repo. */
    const val MAX_PARAMS_BILLIONS = 8.0

    private const val COMFORTABLE_PARAMS_BILLIONS = 4.0
    private const val TINY_PARAMS_BILLIONS = 1.0

    // "8x7B" (MoE) — trust the product over the per-expert size.
    private val MOE_PARAMS_PATTERN =
        Regex("""(?i)(\d+(?:\.\d+)?)\s*x\s*(\d+(?:\.\d+)?)\s*b(?![a-z0-9])""")

    // "3B" / "1.5b" — a number followed by a b suffix. The lookarounds keep
    // tokens like "4bit", "Q4_K_M" or "1024" from being read as sizes.
    private val BILLIONS_PATTERN =
        Regex("""(?i)(?<![a-z0-9.])(\d+(?:\.\d+)?)\s*b(?![a-z0-9])""")

    // "270M" / "135m" — millions of params.
    private val MILLIONS_PATTERN =
        Regex("""(?i)(?<![a-z0-9.])(\d+(?:\.\d+)?)\s*m(?![a-z0-9])""")

    // Quantisation markers in repo/file names: "q4_k_m", "iq4_xs", "int8", "8bit"…
    private val QUANT_PATTERN =
        Regex("""(?i)(?<![a-z0-9])(q[2-8]|iq[1-4]|int[48]|4bit|8bit)(?![a-z0-9])""")

    /** True when we're confident this model runs comfortably on a phone. */
    fun isMobileFriendly(model: ModelSummary): Boolean {
        if (model.siblings.any { it.isMobileBundleFile() }) return true
        val smallest = smallestParamsBillions(model) ?: return false
        if (smallest > COMFORTABLE_PARAMS_BILLIONS) return false
        return smallest <= TINY_PARAMS_BILLIONS || hasQuantMarkers(model)
    }

    /** True when every size signal we can parse says "no phone will run this". */
    fun isTooLargeForMobile(model: ModelSummary): Boolean {
        val smallest = smallestParamsBillions(model) ?: return false
        return smallest > MAX_PARAMS_BILLIONS
    }

    /** Smallest parameter count (billions) found in the id or any file name. */
    internal fun smallestParamsBillions(model: ModelSummary): Double? =
        sizeTexts(model).flatMap { paramsBillionsIn(it) }.minOrNull()

    /** True when a quantisation marker appears in the repo id or a file name. */
    internal fun hasQuantMarkers(model: ModelSummary): Boolean =
        sizeTexts(model).any { QUANT_PATTERN.containsMatchIn(it) }

    /** All parameter counts (billions) parsed from one piece of text. */
    internal fun paramsBillionsIn(text: String): List<Double> {
        val moeProducts = MOE_PARAMS_PATTERN.findAll(text)
            .map { it.groupValues[1].toDouble() * it.groupValues[2].toDouble() }
            .toList()
        if (moeProducts.isNotEmpty()) return moeProducts

        val billions = BILLIONS_PATTERN.findAll(text).map { it.groupValues[1].toDouble() }
        val millions = MILLIONS_PATTERN.findAll(text).map { it.groupValues[1].toDouble() / 1000.0 }
        return billions.toList() + millions.toList()
    }

    private fun sizeTexts(model: ModelSummary): List<String> = buildList {
        add(model.modelId)
        model.siblings.forEach { add(it.substringAfterLast('/')) }
    }

    private fun String.isMobileBundleFile(): Boolean =
        endsWith(".litertlm", ignoreCase = true) ||
            endsWith(".task", ignoreCase = true) ||
            endsWith(".tflite", ignoreCase = true)
}
