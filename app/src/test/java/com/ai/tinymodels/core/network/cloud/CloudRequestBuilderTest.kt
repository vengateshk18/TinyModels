package com.ai.tinymodels.core.network.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRequestBuilderTest {

    @Test
    fun `chat body carries model, stream flag, messages and sampling`() {
        val body = HuggingFaceCloudApi.buildChatBody(
            model = "Qwen/Qwen3-8B",
            messages = listOf(
                CloudChatMessage("system", "Be brief"),
                CloudChatMessage("user", "Hello")
            ),
            temperature = 0.7,
            topP = 0.9,
            maxTokens = 256
        )
        val json = JSONObject(body)

        assertEquals("Qwen/Qwen3-8B", json.getString("model"))
        assertTrue(json.getBoolean("stream"))
        assertEquals(256, json.getInt("max_tokens"))
        assertEquals(0.7, json.getDouble("temperature"), 0.0001)
        assertEquals(0.9, json.getDouble("top_p"), 0.0001)

        val messages = json.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("Be brief", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `models response is parsed into ids and owners`() {
        val json = """
            {"data":[
              {"id":"Qwen/Qwen3-8B","owned_by":"Qwen"},
              {"id":"meta-llama/Llama-3.3-70B-Instruct","owned_by":"meta-llama"}
            ]}
        """.trimIndent()

        val models = HuggingFaceCloudApi.parseModels(json)

        assertEquals(2, models.size)
        assertEquals("Qwen/Qwen3-8B", models[0].id)
        assertEquals("Qwen", models[0].ownedBy)
        assertEquals("meta-llama", models[1].ownedBy)
    }

    @Test
    fun `models response tolerates missing owner`() {
        val models = HuggingFaceCloudApi.parseModels("""{"data":[{"id":"org/model"}]}""")

        assertEquals(listOf(CloudModel("org/model", null)), models)
    }
}
