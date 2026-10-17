package com.ai.tinymodels.core.inference.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Gemma-family chat template used for MediaPipe `.task` models. MediaPipe
 * sessions have no history/system-prompt API, so the full transcript is built
 * here every turn:
 *
 *  - system instruction (plus compaction digest) is folded into the FIRST
 *    user turn — the Gemma convention,
 *  - every turn is wrapped in `<start_of_turn>user|model ... <end_of_turn>`,
 *  - the prompt always ends with the open model cue.
 */
class MediaPipePromptBuilderTest {

    private fun user(text: String) = ChatTurn(ChatTurn.Role.USER, text)
    private fun model(text: String) = ChatTurn(ChatTurn.Role.ASSISTANT, text)

    @Test fun `single user turn renders the plain gemma turn`() {
        assertEquals(
            "<start_of_turn>user\nHi<end_of_turn>\n<start_of_turn>model\n",
            MediaPipePromptBuilder.build(null, null, listOf(user("Hi")))
        )
    }

    @Test fun `system instruction folds into the first user turn`() {
        assertEquals(
            "<start_of_turn>user\nYou are kind.\n\nHi<end_of_turn>\n<start_of_turn>model\n",
            MediaPipePromptBuilder.build("You are kind.", null, listOf(user("Hi")))
        )
    }

    @Test fun `compaction digest folds after the system instruction`() {
        assertEquals(
            "<start_of_turn>user\nYou are kind.\n\n" +
                "Conversation so far (summary of earlier messages):\ndigest" +
                "\n\nHi<end_of_turn>\n<start_of_turn>model\n",
            MediaPipePromptBuilder.build("You are kind.", "digest", listOf(user("Hi")))
        )
    }

    @Test fun `system folds into the FIRST user turn of a multi-turn history`() {
        assertEquals(
            "<start_of_turn>user\nsys\n\nHi<end_of_turn>\n" +
                "<start_of_turn>model\nHello!<end_of_turn>\n" +
                "<start_of_turn>user\nHow are you?<end_of_turn>\n" +
                "<start_of_turn>model\n",
            MediaPipePromptBuilder.build(
                "sys",
                null,
                listOf(user("Hi"), model("Hello!"), user("How are you?"))
            )
        )
    }

    @Test fun `multi-turn history renders user and model turns in order`() {
        assertEquals(
            "<start_of_turn>user\nHi<end_of_turn>\n" +
                "<start_of_turn>model\nHello!<end_of_turn>\n" +
                "<start_of_turn>user\nBye<end_of_turn>\n" +
                "<start_of_turn>model\n",
            MediaPipePromptBuilder.build(null, null, listOf(user("Hi"), model("Hello!"), user("Bye")))
        )
    }

    @Test fun `blank system and summary render as absent`() {
        assertEquals(
            "<start_of_turn>user\nHi<end_of_turn>\n<start_of_turn>model\n",
            MediaPipePromptBuilder.build("   ", "", listOf(user("Hi")))
        )
    }

    @Test fun `system survives even when history has no user turn`() {
        // Defensive: histories starting with an assistant turn must not drop
        // the system instruction; it gets its own user turn.
        assertEquals(
            "<start_of_turn>model\nHello!<end_of_turn>\n" +
                "<start_of_turn>user\nsys<end_of_turn>\n" +
                "<start_of_turn>model\n",
            MediaPipePromptBuilder.build("sys", null, listOf(model("Hello!")))
        )
    }

    @Test fun `prompt always ends with the open model cue`() {
        val prompt = MediaPipePromptBuilder.build(null, null, listOf(user("Hi"), model("Yo"), user("!")))
        assertEquals(true, prompt.endsWith("<start_of_turn>model\n"))
    }
}

/**
 * Per-family templates, checked against each model's real `chat_template` in its
 * HuggingFace `tokenizer_config.json`.
 *
 * These exist because the markers used to be hand-written: the assistant cue
 * shipped a non-existent `<|im_start<|channel|>>` token and Llama-3 shipped no
 * header tokens at all, so `.task` models hit EOS immediately (no reply).
 */
class MediaPipePromptBuilderFamilyTest {

    private fun user(text: String) = ChatTurn(ChatTurn.Role.USER, text)
    private fun model(text: String) = ChatTurn(ChatTurn.Role.ASSISTANT, text)

    // HF SmolLM-135M-Instruct:
    // "{{'' + role + '\n' + content + '\n'}}" + "{{ 'assistant\n' }}"
    @Test fun `smollm plain template is role newline content newline`() {
        assertEquals(
            "system\nYou are kind.\nuser\nHi\nassistant\n",
            MediaPipePromptBuilder.build("You are kind.", null, listOf(user("Hi")), ChatTemplate.PLAIN)
        )
    }

    @Test fun `smollm plain template keeps multi turn order`() {
        assertEquals(
            "user\nHi\nassistant\nHello!\nuser\nBye\nassistant\n",
            MediaPipePromptBuilder.build(
                null, null, listOf(user("Hi"), model("Hello!"), user("Bye")), ChatTemplate.PLAIN
            )
        )
    }

    // HF Qwen2.5-Instruct (no tools branch) is the same plain layout.
    @Test fun `qwen plain template matches the no tools chat template`() {
        assertEquals(
            "user\n2+2\nassistant\n",
            MediaPipePromptBuilder.build(null, null, listOf(user("2+2")), ChatTemplate.PLAIN)
        )
    }

    // HF Qwen3 / DeepSeek / MiniCPM: <|im_start|>role ... <|im_end|>
    @Test fun `chatml uses the real im_start and im_end tokens`() {
        assertEquals(
            "<|im_start|>system\nBe brief.<|im_end|>\n" +
                "<|im_start|>user\nHi<|im_end|>\n" +
                "<|im_start|>assistant\n",
            MediaPipePromptBuilder.build("Be brief.", null, listOf(user("Hi")), ChatTemplate.CHATML)
        )
    }

    // HF Meta-Llama-3-8B-Instruct: header-id tokens + <|eot_id|>, no newline after it.
    @Test fun `llama3 uses header id and eot tokens`() {
        assertEquals(
            "<|start_header_id|>system<|end_header_id|>\n\nBe brief.<|eot_id|>" +
                "<|start_header_id|>user<|end_header_id|>\n\nHi<|eot_id|>" +
                "<|start_header_id|>assistant<|end_header_id|>\n\n",
            MediaPipePromptBuilder.build("Be brief.", null, listOf(user("Hi")), ChatTemplate.LLAMA3)
        )
    }

    @Test fun `gemma template is unchanged`() {
        assertEquals(
            "<start_of_turn>user\nHi<end_of_turn>\n<start_of_turn>model\n",
            MediaPipePromptBuilder.build(null, null, listOf(user("Hi")), ChatTemplate.GEMMA)
        )
    }

    /** Guards the regression that caused the bug: invented special tokens. */
    @Test fun `no template emits a garbled or unknown special token`() {
        val forbidden = listOf("<|im_start<|channel|>>", "<|eot_id<|message|>>")
        ChatTemplate.entries.forEach { template ->
            val markers = MediaPipePromptBuilder.markers(template)
            listOf(
                markers.userOpen, markers.turnClose,
                markers.assistantOpen, markers.systemOpen.orEmpty()
            ).forEach { marker ->
                forbidden.forEach { bad ->
                    assertEquals(
                        "template $template must not contain $bad",
                        false, marker.contains(bad)
                    )
                }
            }
        }
    }

    @Test fun `smollm file name maps to the plain template`() {
        assertEquals(
            ChatTemplate.PLAIN,
            ChatTemplate.fromName("SmolLM instruct multi prefill seq q8 ekv 1280.task")
        )
    }

    @Test fun `unknown model names fall back to the plain template`() {
        assertEquals(ChatTemplate.PLAIN, ChatTemplate.fromName(null))
        assertEquals(ChatTemplate.PLAIN, ChatTemplate.fromName("tiny-llm.task"))
    }

    @Test fun `gemma and llama names keep their own templates`() {
        assertEquals(ChatTemplate.GEMMA, ChatTemplate.fromName("gemma-2-2b-it.task"))
        assertEquals(ChatTemplate.LLAMA3, ChatTemplate.fromName("Llama-3.2-1B-Instruct.task"))
        assertEquals(ChatTemplate.LLAMA2, ChatTemplate.fromName("Llama-2-7b-chat.task"))
    }
}
