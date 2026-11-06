package com.ai.tinymodels.core.inference

import com.ai.tinymodels.domain.model.SamplerSettings

/**
 * The fixed benchmark protocol.
 *
 * Everything here is deliberately frozen so that results from different models,
 * devices, and app versions stay comparable:
 *
 *  - **Greedy sampling** (temperature 0, top-k 1) makes every run reproducible —
 *    the same weights on the same hardware produce the same text.
 *  - **Three prompts** span the two regimes that matter on-device: short
 *    prompts stress time-to-first-token, long prompts stress prefill throughput.
 *  - **One warm-up run** per prompt is discarded: the first pass pays for
 *    lazy graph allocation, kernel JIT, and cache warm-up.
 *  - **Five measured runs** per prompt give a usable median and p95.
 *
 * Changing any constant invalidates comparisons with previously reported runs,
 * so this object is the only place they are defined.
 */
object BenchmarkProtocol {

    /**
     * Greedy decode. Temperature 0 removes sampling noise, so a difference
     * between two runs reflects the device, not the RNG.
     */
    val SAMPLER = SamplerSettings(temperature = 0.0, topK = 1, topP = 1.0)

    /** Runs discarded before measuring — one per prompt. */
    const val WARM_UP_RUNS = 1

    /** Measured runs per prompt. */
    const val MEASURED_RUNS = 5

    /** Output cap per run; keeps every prompt in the decode-bound regime. */
    const val MAX_OUTPUT_TOKENS = 192

    /** How long the sustained (thermal) phase runs for. */
    const val SUSTAIN_DURATION_MS = 20_000L

    /**
     * The prompt suite, ordered prefill-light → prefill-heavy.
     *
     * Each entry is self-contained (no history, no system prompt), so the
     * prompt tokens are the only prefill work.
     */
    val PROMPTS = listOf(
        PromptSpec(
            id = "short",
            label = "Short",
            text = "Describe the sky in one sentence."
        ),
        PromptSpec(
            id = "medium",
            label = "Medium",
            text = "Summarize the following in three sentences: " +
                "The industrial revolution began in Britain in the late eighteenth century " +
                "and spread across Europe and North America over the following hundred years. " +
                "Steam power, new iron production techniques, and the factory system transformed " +
                "economies that had previously relied on agriculture and handcrafts. Cities grew " +
                "rapidly as workers moved from rural areas, and new social classes emerged. " +
                "Railways and steamships connected distant markets, while advances in communication " +
                "such as the telegraph accelerated trade. Living conditions for many workers were " +
                "difficult, with long hours, child labor, and crowded housing, but wages and " +
                "literacy gradually improved over the following decades."
        ),
        PromptSpec(
            id = "long",
            label = "Long",
            text = "Read the passage below and answer the question at the end in two sentences.\n\n" +
                "Coral reefs occupy less than one percent of the ocean floor yet support roughly " +
                "a quarter of all marine species. The relationship between coral polyps and the " +
                "single-celled algae called zooxanthellae that live inside their tissues is the " +
                "foundation of that abundance. The algae photosynthesise and pass sugars to the " +
                "coral; the coral provides shelter and a steady supply of nitrogen and phosphorus " +
                "from its waste. When water temperatures rise even one or two degrees above the " +
                "summer maximum, the partnership breaks down. The coral expels the algae, losing " +
                "both its colour and its main energy source, a condition known as bleaching. " +
                "A bleached colony can survive for a few weeks and may recover if conditions " +
                "return to normal, but prolonged stress usually kills it. Recovery of a whole " +
                "reef takes a decade or more, because the branching corals that build the " +
                "structure grow only a few centimetres a year. Scientists studying the Great " +
                "Barrier Reef have recorded repeated bleaching events since the late nineteen " +
                "nineties, with the gaps between them shrinking from roughly a decade to only " +
                "a few years. Reef managers respond with shade cloths, coral nurseries, and the " +
                "transplanting of heat-tolerant fragments, but all of these measures operate at " +
                "a scale far smaller than the reef itself. The deeper problem is that reefs sit " +
                "at the intersection of two pressures: global warming, which raises the water " +
                "temperature, and local stresses such as runoff, overfishing, and destructive " +
                "fishing practices, which reduce the reef's ability to recover between events. " +
                "Removing the local stresses buys time; it cannot substitute for cutting " +
                "greenhouse gas emissions.\n\n" +
                "Question: why do scientists describe local reef protection as necessary but " +
                "not sufficient?"
        )
    )

    /** True for runs that exist only to warm the engine up. */
    fun isWarmUp(runIndex: Int): Boolean = runIndex < WARM_UP_RUNS

    /** Total runs across every prompt, warm-ups included. */
    fun totalRuns(): Int = PROMPTS.size * (WARM_UP_RUNS + MEASURED_RUNS)
}
