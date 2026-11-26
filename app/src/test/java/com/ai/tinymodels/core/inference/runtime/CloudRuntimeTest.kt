package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.network.cloud.CloudChatClient
import com.ai.tinymodels.core.network.cloud.CloudChatMessage
import com.ai.tinymodels.core.network.cloud.CloudModel
import com.ai.tinymodels.domain.model.ModelFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRuntimeTest {

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private class FakeClient : CloudChatClient {
        var model: String? = null

        override fun streamChat(
            model: String,
            messages: List<CloudChatMessage>,
            temperature: Double,
            topP: Double,
            maxTokens: Int
        ): Flow<String> {
            this.model = model
            return flowOf("cloud reply")
        }

        override suspend fun listModels(): List<CloudModel> = emptyList()
    }

    @Test
    fun `cloud runtime reports the cloud format and no tools or vision`() {
        val runtime = CloudRuntime(FakeClient(), dispatchers)

        assertEquals(ModelFormat.CLOUD, runtime.format)
        assertFalse(runtime.supportsToolCalling)
        assertFalse(runtime.detectVisionSupport(java.io.File("x")))
        assertFalse(runtime.isInitialized)
    }

    @Test
    fun `session streams through the client for the selected model`() = runTest {
        val client = FakeClient()
        val runtime = CloudRuntime(client, dispatchers)

        runtime.setModel("Qwen/Qwen3-8B")
        val session = runtime.createSession(SessionRequest(maxContextTokens = 2048))

        assertTrue(runtime.isInitialized)
        assertEquals(listOf("cloud reply"), session.send("Hi").toList())
        assertEquals("Qwen/Qwen3-8B", client.model)
    }

    @Test
    fun `close clears the selected model`() = runTest {
        val runtime = CloudRuntime(FakeClient(), dispatchers)
        runtime.setModel("Qwen/Qwen3-8B")

        runtime.close()

        assertFalse(runtime.isInitialized)
    }
}
