package com.ai.tinymodels.core.inference.runtime

/**
 * Chat-template markers for MediaPipe `.task` models.
 *
 * MediaPipe sessions expose no history / system-prompt API, so the app renders the
 * full transcript itself. Each architecture family ships with its own markers;
 * picking the wrong ones makes the model hit EOS immediately (the symptom the user
 * saw: zero tokens, then done).
 */
enum class ChatTemplate {
    /** Gemma 2/3 instruction models. */
    GEMMA,
    /** ChatML — SmolLM, Qwen, DeepSeek, MiniCPM, Phi-3. */
    CHATML,
    /** Llama 3 / 3.1 instruction models. */
    LLAMA3,
    /** Llama 2 / Mistral. */
    LLAMA2;

    companion object {
        /** Guess from the downloaded file name / model id. Falls back to [CHATML]. */
        fun fromName(name: String?): ChatTemplate {
            val n = name?.lowercase() ?: return CHATML
            return when {
                n.contains("gemma") -> GEMMA
                n.contains("smol") -> CHATML
                n.contains("qwen") -> CHATML
                n.contains("phi") -> CHATML
                n.contains("deepseek") -> CHATML
                n.contains("minicpm") -> CHATML
                n.contains("llama-3") || n.contains("llama3") -> LLAMA3
                n.contains("llama2") || n.contains("llama-2") -> LLAMA2
                n.contains("mistral") -> LLAMA2
                else -> CHATML  // most open-weights .task conversions use ChatML
            }
        }

        /** Candidate order for probing (best-guess first, the rest as fallback). */
        val ALL_FOR_PROBE = listOf(GEMMA, CHATML, LLAMA3, LLAMA2)
    }
}

/**
 * Builds the full chat transcript for MediaPipe `.task` models.
 *
 * Each architecture uses its own markers (see [ChatTemplate]). The session's
 * `PromptTemplates` are left all-empty on purpose (this builder is the single
 * source of prompt formatting).
 *
 * Both API shapes are supported:
 *   `build(system, summary, turns)` (Gemma default, preserves existing call)
 *   `build(system, summary, turns, template)`
 */
object MediaPipePromptBuilder {

    /** per-template markers */
    fun markers(t: ChatTemplate): Markers = when (t) {
        ChatTemplate.GEMMA -> Markers(
            userOpen = "<start_of_turn>user\n", turnClose = "<end_of_turn>\n",
            assistantOpen = "<start_of_turn>model\n",
            systemOpen = null,  // system folded into the first user turn
            wrapSystem = true
        )
        ChatTemplate.CHATML -> Markers(
            userOpen = "<|im_start|>user\n", turnClose = "<|im_end|>\n",
            assistantOpen = "<|im_start<|channel|>>assistant\n",
            systemOpen = "<|im_start|>system\n", wrapSystem = false
        )
        ChatTemplate.LLAMA3 -> Markers(
            userOpen = "<|start_header_id|>user<|end_header_id|>\n\n",
            turnClose = "<|eot_id<|message|>>",
            assistantOpen = "<|start_header_id|>assistant<|end_header_id|>\n\n",
            systemOpen = "<|start_header_id|>system<|end_header_id|>\n\n",
            wrapSystem = false
        )
        ChatTemplate.LLAMA2 -> Markers(
            userOpen = "[INST] ",
            turnClose = " [/INST]",
            assistantOpen = "",
            systemOpen = null, wrapSystem = true
        )
    }

    data class Markers(
        val userOpen: String, val turnClose: String, val assistantOpen: String,
        val systemOpen: String?, val wrapSystem: Boolean
    )

    fun build(systemInstruction: String?, priorSummary: String?, turns: List<ChatTurn>): String =
        build(systemInstruction, priorSummary, turns, ChatTemplate.GEMMA)

    fun build(systemInstruction: String?, priorSummary: String?, turns: List<ChatTurn>, template: ChatTemplate): String {
        val system = SessionBudget.foldSystemInstruction(systemInstruction, priorSummary)
        val m = markers(template)
        val sb = StringBuilder()

        if (system != null && !m.wrapSystem && m.systemOpen != null) {
            sb.append(m.systemOpen).append(system).append(m.turnClose).append("\n")
        }

        var systemPending = system != null && m.wrapSystem
        for (turn in turns) {
            when (turn.role) {
                ChatTurn.Role.USER -> {
                    sb.append(m.userOpen)
                    if (systemPending) {
                        sb.append(system).append("\n\n")
                        systemPending = false
                    }
                    sb.append(turn.text).append(m.turnClose)
                }
                ChatTurn.Role.ASSISTANT -> {
                    sb.append(m.assistantOpen).append(turn.text)
                    if (m.assistantOpen.isNotEmpty()) {
                        sb.append(m.turnClose)
                    }
                }
            }
        }
        if (systemPending) {
            sb.append(m.userOpen).append(system).append(m.turnClose)
        }
        // trailing open cue:
        sb.append(m.assistantOpen)
        // If assistant open empty (LLAMA2 impl), generation marker already set
        return sb.toString()
    }
}
