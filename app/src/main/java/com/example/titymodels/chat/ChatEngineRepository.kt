package com.example.titymodels.chat

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Owns the lifecycle of a single LiteRT-LM [Engine] + [Conversation] pair.
 * Only one model can be loaded at a time; loading a new model closes the previous one.
 */
class ChatEngineRepository {
    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** Loads [modelFile] on a background thread and starts a fresh conversation. */
    suspend fun loadModel(modelFile: File, cacheDir: File) {
        withContext(Dispatchers.IO) {
            close()
            val newEngine = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDir.absolutePath
                )
            )
            newEngine.initialize()
            engine = newEngine
            conversation = newEngine.createConversation()
        }
    }

    /** Streams the assistant's reply to [message] chunk by chunk as plain text. */
    fun sendMessage(message: String): Flow<String> {
        val activeConversation = conversation
            ?: throw IllegalStateException("Model is not loaded yet")
        return activeConversation.sendMessageAsync(message).map { chunk ->
            chunk.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
        }
    }

    fun close() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
    }
}
