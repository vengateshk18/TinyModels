package com.ai.tinymodels.core.inference.runtime

/**
 * Chat-template markers for MediaPipe `.task` models.
 *
 * MediaPipe sessions expose no history / system-prompt API, so the app renders the
 * full transcript itself. Each architecture family ships with its own markers;
 * picking the wrong ones makes the model hit EOS immediately (the symptom the user
 * saw: zero tokens, then done).
 *
 * Every marker string below was transcribed from that model's real `chat_template`
 * in its HuggingFace `tokenizer_config.json`. Do NOT hand-write these: an invented
 * special token is not an unknown word to the model, it is a garbled byte sequence
 * that derails generation (that is how a `<|im_start<|channel|>>` prefix ended up
 * in front of SmolLM's assistant cue and made every reply come back empty).
 */
enum class ChatTemplate {
    /** Gemma 2/3 instruction models. */
    GEMMA,
    /** Plain `role\ncontent\n` — SmolLM, Qwen2.5. */
    PLAIN,
    /** ChatML `<|im_start|>` / `<|im_end|>` — Qwen3, DeepSeek, MiniCPM. */
    CHATML,
    /** Llama 3 / 3.1 instruction models. */
    LLAMA3,
    /** Llama 2 / Mistral. */
    LLAMA2;

    companion object {
        /**
         * Guess from the downloaded file name / model id. Falls back to [PLAIN].
         *
         * Verified templates:
         *  - SmolLM / SmolLM2-Instruct, Qwen2.5-Instruct → [PLAIN]
         *  - Gemma-it → [GEMMA], Llama-3-Instruct → [LLAMA3], Llama-2-chat → [LLAMA2]
         *
         * Phi-3 is deliberately absent: it uses `<|user|>` / `<|end|>` / `<|assistant|>`,
         * which is neither [PLAIN] nor [CHATML], so it needs its own template before
         * it can be listed rather than a silent guess here.
         */
        fun fromName(name: String?): ChatTemplate {
            val n = name?.lowercase() ?: return PLAIN
            return when {
                n.contains("gemma") -> GEMMA
                n.contains("llama-3") || n.contains("llama3") -> LLAMA3
                n.contains("llama2") || n.contains("llama-2") || n.contains("mistral") -> LLAMA2
                n.contains("deepseek") || n.contains("minicpm") -> CHATML
                // SmolLM, Qwen2.5 and most open-weights .task conversions use the
                // plain role/content layout, which is also the safest default.
                else -> PLAIN
            }
        }

        /** Candidate order for probing (best-guess first, the rest as fallback). */
        val ALL_FOR_PROBE = listOf(PLAIN, GEMMA, CHATML, LLAMA3, LLAMA2)
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
        // {{ role }}\n{{ content }}\n per message, then {{ 'assistant\n' }}.
        ChatTemplate.PLAIN -> Markers(
            userOpen = "user\n", turnClose = "\n",
            assistantOpen = "assistant\n",
            systemOpen = "system\n", wrapSystem = false
        )
        ChatTemplate.CHATML -> Markers(
            userOpen = "<|im_start|>user\n", turnClose = "<|im_end|>\n",
            assistantOpen = "<|im_start|>assistant\n",
            systemOpen = "<|im_start|>system\n", wrapSystem = false
        )
        // Llama 3 puts no newline after <|eot_id|> — the next header follows directly.
        ChatTemplate.LLAMA3 -> Markers(
            userOpen = "<|start_header_id|>user<|end_header_id|>\n\n",
            turnClose = "<|eot_id|>",
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
            sb.append(m.systemOpen).append(system).append(m.turnClose)
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