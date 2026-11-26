package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.core.network.cloud.CloudChatClient
import com.ai.tinymodels.core.network.cloud.CloudChatMessage
import com.ai.tinymodels.core.network.cloud.CloudModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudChatSessionTest {

    private class FakeClient : CloudChatClient {
        var lastModel: String? = null
        var lastMessages: List<CloudChatMessage> = emptyList()
        var lastMaxTokens: Int = -1

        override fun streamChat(
            model: String,
            messages: List<CloudChatMessage>,
            temperature: Double,
            topP: Double,
            maxTokens: Int
        ): Flow<String> {
            lastModel = model
            lastMessages = messages
            lastMaxTokens = maxTokens
            return flowOf("Hel", "lo")
        }

        override suspend fun listModels(): List<CloudModel> = emptyList()
    }

    @Test
    fun `send emits cumulative text`() = runTest {
        val session = CloudChatSession(
            client = FakeClient(),
            model = "Qwen/Qwen3-8B",
            request = SessionRequest(maxContextTokens = 4096),
            contextTokens = 4096
        )

        val emissions = session.send("Hi").toList()

        assertEquals(listOf("Hel", "Hello"), emissions)
    }

    @Test
    fun `send includes system prompt, history and the new user turn`() = runTest {
        val client = FakeClient()
        val session = CloudChatSession(
            client = client,
            model = "Qwen/Qwen3-8B",
            request = SessionRequest(
                maxContextTokens = 4096,
                systemInstruction = "Be brief",
                history = listOf(
                    ChatTurn(ChatTurn.Role.USER, "Earlier question"),
                    ChatTurn(ChatTurn.Role.ASSISTANT, "Earlier answer")
                )
            ),
            contextTokens = 4096
        )

        session.send("New question").toList()

        assertEquals("Qwen/Qwen3-8B", client.lastModel)
        assertEquals(
            listOf(
                CloudChatMessage("system", "Be brief"),
                CloudChatMessage("user", "Earlier question"),
                CloudChatMessage("assistant", "Earlier answer"),
                CloudChatMessage("user", "New question")
            ),
            client.lastMessages
        )
    }

    @Test
    fun `send caps output tokens to a provider friendly value`() = runTest {
        val client = FakeClient()
        val session = CloudChatSession(
            client = client,
            model = "Qwen/Qwen3-8B",
            request = SessionRequest(maxContextTokens = 32_768),
            contextTokens = 32_768
        )

        session.send("Hi").toList()

        assertEquals(CloudChatSession.MAX_OUTPUT_TOKENS, client.lastMaxTokens)
    }

    @Test
    fun `send counts tokens against the context budget`() = runTest {
        val session = CloudChatSession(
            client = FakeClient(),
            model = "Qwen/Qwen3-8B",
            request = SessionRequest(maxContextTokens = 4096),
            contextTokens = 4096
        )

        session.send("Hello there").toList()

        assertEquals(true, session.estimatedTokensInContext > 0)
        assertEquals(false, session.isContextNearFull)
    }
}
