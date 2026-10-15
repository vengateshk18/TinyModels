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
