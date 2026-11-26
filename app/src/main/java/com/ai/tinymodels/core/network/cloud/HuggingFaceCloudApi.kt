package com.ai.tinymodels.core.network.cloud

import com.ai.tinymodels.core.common.TinyLog
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
        private const val TAG = "CloudApi"
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
        val token = auth.current() ?: run {
            TinyLog.w(TAG, "CLOUD: streamChat missing token for model=$model")
            throw CloudApiException(
                statusCode = 401,
                message = "Add your Hugging Face token in Settings to use cloud models."
            )
        }
        TinyLog.i(TAG, "CLOUD: streamChat start model=$model messages=${messages.size} maxTokens=$maxTokens")
        val request = Request.Builder()
            .url(CHAT_URL)
            .header("Authorization", "Bearer $token")
            .post(
                buildChatBody(model, messages, temperature, topP, maxTokens)
                    .toRequestBody(JSON.toMediaType())
            )
            .build()

        client.newCall(request).execute().use { response ->
            TinyLog.i(TAG, "CLOUD: streamChat response code=${response.code} model=$model")
            if (!response.isSuccessful) {
                val body = response.body?.string()
                TinyLog.w(TAG, "CLOUD: streamChat failed code=${response.code} model=$model body=${body?.take(300)}")
                throw CloudApiException(
                    statusCode = response.code,
                    message = readableError(response.code, body)
                )
            }
            val source = response.body?.source() ?: run {
                TinyLog.w(TAG, "CLOUD: streamChat empty body model=$model")
                return@use
            }
            var deltas = 0
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                when (val chunk = CloudStreamParser.parse(line)) {
                    is CloudChunk.Delta -> {
                        deltas++
                        emit(chunk.text)
                    }
                    is CloudChunk.Failure -> {
                        TinyLog.w(TAG, "CLOUD: streamChat provider failure model=$model msg=${chunk.message}")
                        throw CloudApiException(502, chunk.message)
                    }
                    CloudChunk.Done -> {
                        TinyLog.i(TAG, "CLOUD: streamChat done model=$model deltas=$deltas")
                        return@use
                    }
                    null -> Unit
                }
            }
            TinyLog.i(TAG, "CLOUD: streamChat ended model=$model deltas=$deltas")
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(): List<CloudModel> = withContext(Dispatchers.IO) {
        val hasToken = auth.current() != null
        TinyLog.i(TAG, "CLOUD: listModels start hasToken=$hasToken url=$MODELS_URL")
        val builder = Request.Builder().url(MODELS_URL).get()
        auth.current()?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            TinyLog.i(TAG, "CLOUD: listModels response code=${response.code} bodyBytes=${body.length}")
            if (!response.isSuccessful) {
                TinyLog.w(TAG, "CLOUD: listModels failed code=${response.code} body=${body.take(300)}")
                throw CloudApiException(response.code, readableError(response.code, body))
            }
            val models = parseModels(body).take(60)
            TinyLog.i(TAG, "CLOUD: listModels parsed=${models.size} first=${models.firstOrNull()?.id}")
            models
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
