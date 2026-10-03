package com.example.tinymodels.feature.home

import com.example.tinymodels.feature.home.model.RecommendedModel

/**
 * Curated catalog of `.litertlm` models that fit the mobile 2.5 GB cap.
 *
 * All entries are UNGATED — they download anonymously, with no Hugging Face
 * account, token, or license click-through. (Every official Gemma checkpoint
 * is gated by Google's Gemma license, so Gemma was dropped from the catalog.)
 *
 * Deliberately avoided:
 *  - All Gemma checkpoints (270M … E4B) — gated; anonymous downloads get 401.
 *  - `litert-community/Qwen2.5-0.5B-Instruct` — ships only .task/.tflite,
 *    no .litertlm container (this runtime loads .litertlm only).
 *  - `litert-community/DeepSeek-R1-Distill-Qwen-7B` — exceeds the 2.5 GB cap.
 */
object RecommendedModels {

    val SMALL = RecommendedModel(
        tier = "Small",
        modelId = "gbpeck/Qwen3-0.6B-litertlm-jinja",
        fileName = "Qwen3-0.6B.litertlm",
        sizeBytes = 613_442_992L,
        displayName = "Qwen3 0.6B",
        hfUrl = "https://huggingface.co/gbpeck/Qwen3-0.6B-litertlm-jinja"
    )

    val MEDIUM = RecommendedModel(
        tier = "Medium",
        modelId = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
        fileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
        sizeBytes = 1_833_451_520L,
        displayName = "DeepSeek R1 1.5B",
        hfUrl = "https://huggingface.co/litert-community/DeepSeek-R1-Distill-Qwen-1.5B"
    )

    val HIGH = RecommendedModel(
        tier = "High",
        modelId = "paulsp94/Qwen3.5-2B-LiteRT-LM",
        fileName = "qwen35_2b.litertlm",
        sizeBytes = 1_901_762_208L,
        displayName = "Qwen3.5 2B",
        hfUrl = "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM"
    )

    /** All tiers, ordered Small → High. */
    val all: List<RecommendedModel> = listOf(SMALL, MEDIUM, HIGH)

    /**
     * Picks the best tier for this device:
     *  1. RAM tier: ≥ 6 GB AI-RAM → High; ≥ 2.5 GB → Medium; else Small.
     *  2. Storage guard: if free storage < size × 1.1, drop one tier and retry.
     */
    fun pickForDevice(availableRamForAiMb: Long, freeStorageBytes: Long): RecommendedModel {
        val ramTier = when {
            availableRamForAiMb >= 6000 -> HIGH
            availableRamForAiMb >= 2500 -> MEDIUM
            else -> SMALL
        }
        // Storage guard — fall down the tiers until the model fits.
        var candidate = ramTier
        while (candidate !== SMALL && freeStorageBytes < candidate.sizeBytes * 1.1) {
            candidate = all[maxOf(0, all.indexOf(candidate) - 1)]
        }
        return candidate
    }
}
