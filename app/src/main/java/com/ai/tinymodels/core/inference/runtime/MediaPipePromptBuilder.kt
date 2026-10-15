package com.ai.tinymodels.core.inference.runtime

/**
 * Builds the full chat transcript for MediaPipe `.task` models.
 *
 * MediaPipe's session API has no history or system-prompt entry point, so the
 * app is stateless per turn: every send renders the complete transcript here.
 * The template is the Gemma-family one (the bulk of available `.task` LLM
 * bundles are Gemma conversions):
 *
 * ```
 * <start_of_turn>user
 * {system instruction, folded into the FIRST user turn}
 *
 * {user message}<end_of_turn>
 * <start_of_turn>model
 * {assistant reply}<end_of_turn>
 * ...
 * <start_of_turn>model      <- open cue, generation continues from here
 * ```
 *
 * The session is additionally created with ALL-EMPTY `PromptTemplates`, so
 * MediaPipe itself adds no wrapping — this builder is the single source of
 * prompt formatting.
 */
object MediaPipePromptBuilder {

    const val TURN_START_USER = "<start_of_turn>user\n"
    const val TURN_START_MODEL = "<start_of_turn>model\n"
    const val TURN_END = "<end_of_turn>\n"

    /**
     * Renders [turns] (the full transcript, ending with the newest user turn).
     * [systemInstruction] + [priorSummary] are folded into the first user
     * turn, per the Gemma convention; if no user turn exists they get their
     * own user turn so they can never be dropped.
     */
    fun build(
        systemInstruction: String?,
        priorSummary: String?,
        turns: List<ChatTurn>
    ): String {
        val system = SessionBudget.foldSystemInstruction(systemInstruction, priorSummary)
        val sb = StringBuilder()
        var systemPending = system != null
        for (turn in turns) {
            when (turn.role) {
                ChatTurn.Role.USER -> {
                    sb.append(TURN_START_USER)
                    if (systemPending) {
                        sb.append(system).append("\n\n")
                        systemPending = false
                    }
                    sb.append(turn.text).append(TURN_END)
                }
                ChatTurn.Role.ASSISTANT -> {
                    sb.append(TURN_START_MODEL).append(turn.text).append(TURN_END)
                }
            }
        }
        if (systemPending) {
            sb.append(TURN_START_USER).append(system).append(TURN_END)
        }
        sb.append(TURN_START_MODEL)
        return sb.toString()
    }
}
