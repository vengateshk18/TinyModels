package com.ai.tinymodels.core.inference

import com.ai.tinymodels.core.inference.runtime.ChatTurn
import com.ai.tinymodels.core.inference.runtime.ConversationSession
import com.ai.tinymodels.core.inference.runtime.SessionBudget
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multimodal rules: photos ride as image parts and cost context honestly. */
class VisionMessageTest {

    @Test fun `text-only turn keeps the plain String path`() {
        val message = with(ConversationSession.Companion) {
            ChatTurn(ChatTurn.Role.USER, "hello").toUserMessage()
        }
        assertEquals(Role.USER, message.role)
        val parts = message.contents.contents
        assertEquals(1, parts.size)
        assertTrue(parts[0] is Content.Text)
        assertEquals("hello", (parts[0] as Content.Text).text)
    }

    @Test fun `photo turn becomes image parts plus text`() {
        val message = with(ConversationSession.Companion) {
            ChatTurn(ChatTurn.Role.USER, "what is this?", listOf("/tmp/a.jpg")).toUserMessage()
        }
        assertEquals(Role.USER, message.role)
        val parts = message.contents.contents
        assertEquals(2, parts.size)
        assertTrue(parts[0] is Content.ImageFile)
        assertEquals("/tmp/a.jpg", (parts[0] as Content.ImageFile).absolutePath)
        assertTrue(parts[1] is Content.Text)
    }

    @Test fun `trimToBudget counts photo cost`() {
        val photo = ChatTurn(ChatTurn.Role.USER, "see?", listOf("/tmp/a.jpg"))
        val text = ChatTurn(ChatTurn.Role.USER, "hi") // ~1 token
        // Budget fits the text turn but not the 1024-token photo turn.
        val kept = SessionBudget.trimToBudget(listOf(photo, text), SessionBudget.IMAGE_TOKEN_COST - 1)
        assertEquals(listOf(text), kept)
    }

    @Test fun `trimToBudget keeps photo turn when budget allows`() {
        val photo = ChatTurn(ChatTurn.Role.USER, "see?", listOf("/tmp/a.jpg"))
        val kept = SessionBudget.trimToBudget(listOf(photo), SessionBudget.IMAGE_TOKEN_COST + 10)
        assertEquals(listOf(photo), kept)
    }
}
