package com.ai.tinymodels.core.inference.runtime

import com.ai.tinymodels.core.common.DispatcherProvider
import com.ai.tinymodels.core.network.cloud.CloudChatClient
import com.ai.tinymodels.domain.model.ModelFormat
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ModelRuntime] for hosted models.
 *
 * There is no native handle and no file: [setModel] remembers the router
 * model id, and every session is a [CloudChatSession]. Keeping the same
 * runtime contract means [com.ai.tinymodels.core.inference.ModelManager],
 * the model chip, and the chat rebuild path need no special cases.
 */
@Singleton
class CloudRuntime @Inject constructor(
    private val client: CloudChatClient,
    private val dispatchers: DispatcherProvider
) : ModelRuntime {

    companion object {
        /**
         * Display/compaction budget for cloud chats. Router models accept far
         * more, but the app's context UI and compaction logic expect a finite
         * window; 32k keeps every hosted model comfortably inside its limit.
         */
        const val CLOUD_CONTEXT_TOKENS = 32_768
    }

    override val format: ModelFormat = ModelFormat.CLOUD

    override val supportsToolCalling: Boolean = false

    @Volatile
    private var modelId: String? = null

    override val isInitialized: Boolean get() = modelId != null

    /** Called by ModelManager before [initialize]; cloud models have no file to read. */
    fun setModel(id: String) {
        modelId = id.trim().takeIf { it.isNotBlank() }
    }

    /** No file to inspect: cloud models are text-only in this MVP. */
    override fun detectVisionSupport(modelFile: File): Boolean = false

    /** The id already lives in [modelId]; the request file is a placeholder. */
    override suspend fun initialize(request: RuntimeInitRequest) {
        if (modelId.isNullOrBlank()) {
            setModel(request.modelFile.name)
        }
    }

    override fun extractedMaxTokens(): Int? = CLOUD_CONTEXT_TOKENS

    override fun createSession(request: SessionRequest): ChatSession {
        val model = modelId ?: throw IllegalStateException("No cloud model selected")
        return CloudChatSession(
            client = client,
            model = model,
            request = request,
            contextTokens = CLOUD_CONTEXT_TOKENS,
            dispatcher = dispatchers.io
        )
    }

    override suspend fun close() {
        modelId = null
    }
}
