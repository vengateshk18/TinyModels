package com.ai.tinymodels.core.network.cloud

import com.ai.tinymodels.core.network.HuggingFaceAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hugging Face Inference Providers client.
 *
 * The router speaks the OpenAI chat-completions shape, so one small client
 * covers every partner host (Together, Groq, Cerebras, …). It reuses the
 * app's [OkHttpClient] and the user's stored [HuggingFaceAuth] token — no
 * second login and no new dependency.
 */
@Singleton
class HuggingFaceCloudApi @Inject constructor(
    private val client: OkHttpClient,
    private val auth: HuggingFaceAuth
) : CloudChatClient {

    companion object {
        const val CHAT_URL = "https://router.huggingface.co/v1/chat/completions"
        const val MODELS_URL = "https://router.huggingface.co/v1/models"
        private const val JSON = "application/json"

        /** Pure request-body builder so the wire shape is unit-tested. */
        fun buildChatBody(
            model: String,
            messages: List<CloudChatMessage>,
            temperature: Double,
            topP: Double,
            maxTokens: Int
        ): String {
            val array = JSONArray()
            messages.forEach { message ->
                array.put(
                    JSONObject()
                        .put("role", message.role)
                        .put("content", message.content)
                )
            }
            return JSONObject()
                .put("model", model)
                .put("stream", true)
                .put("max_tokens", maxTokens)
                .put("temperature", temperature)
                .put("top_p", topP)
                .put("messages", array)
                .toString()
        }

        /** Pure models-response parser. */
        fun parseModels(json: String): List<CloudModel> {
            val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
            return buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    add(
                        CloudModel(
                            id = id,
                            ownedBy = item.optString("owned_by").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
    }

    override fun streamChat(
        model: String,
        messages: List<CloudChatMessage>,
        temperature: Double,
        topP: Double,
        maxTokens: Int
    ): Flow<String> = flow {
        val token = auth.current() ?: throw CloudApiException(
            statusCode = 401,
            message = "Add your Hugging Face token in Settings to use cloud models."
        )
        val request = Request.Builder()
            .url(CHAT_URL)
            .header("Authorization", "Bearer $token")
            .post(
                buildChatBody(model, messages, temperature, topP, maxTokens)
                    .toRequestBody(JSON.toMediaType())
            )
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CloudApiException(
                    statusCode = response.code,
                    message = readableError(response.code, response.body?.string())
                )
            }
            val source = response.body?.source() ?: return@use
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when (val chunk = CloudStreamParser.parse(line)) {
                    is CloudChunk.Delta -> emit(chunk.text)
                    is CloudChunk.Failure -> throw CloudApiException(502, chunk.message)
                    CloudChunk.Done -> return@use
                    null -> Unit
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(): List<CloudModel> = withContext(Dispatchers.IO) {
        val token = auth.current() ?: throw CloudApiException(
            statusCode = 401,
            message = "Add your Hugging Face token in Settings to use cloud models."
        )
        val request = Request.Builder()
            .url(MODELS_URL)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CloudApiException(response.code, readableError(response.code, body))
            }
            parseModels(body)
        }
    }

    private fun readableError(code: Int, body: String?): String {
        val detail = body
            ?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        return when (code) {
            401, 403 -> "Hugging Face rejected the token. Check it in Settings."
            402 -> "Cloud credits are exhausted. Add billing on Hugging Face or pick another model."
            404 -> "That model is not available on the router right now."
            429 -> "Too many requests. Wait a moment and try again."
            in 500..599 -> "The cloud provider failed (HTTP $code). Try again."
            else -> detail ?: "Cloud request failed (HTTP $code)."
        }
    }
}
