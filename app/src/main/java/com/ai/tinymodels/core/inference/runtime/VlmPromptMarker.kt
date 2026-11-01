package com.ai.tinymodels.core.inference.runtime

/**
 * Builds the user-message text that the GGUF vision path expects.
 *
 * llama.cpp's multimodal tokenizer (`mtmd_tokenize`) splits the rendered
 * prompt on the projector's **media marker** and turns each occurrence into
 * an image chunk. If the marker is missing, the projection step finds no
 * bitmap slot and generation fails with "Failed to tokenize multimodal
 * input". The marker itself is model-specific (e.g. `<__media__>` for the
 * generic mtmd default), so it is always read from the loaded projector via
 * `GGMLEngine.getVlmDefaultMarker()` rather than hard-coded.
 *
 * Mirrors ToolNeuron's `InferenceCoordinator.buildMessagesJson`, which
 * prepends the marker to the last user turn when images are attached.
 */
object VlmPromptMarker {

    /**
     * @param content the user's typed text (may be blank when they only
     *   attached a photo).
     * @param marker the loaded projector's media marker; blank/null disables
     *   the prefix.
     * @param imageCount number of bitmaps sent with this turn. One marker is
     *   emitted per bitmap because mtmd pairs markers and images by order.
     */
    fun apply(content: String, marker: String?, imageCount: Int): String {
        if (marker.isNullOrBlank() || imageCount <= 0) return content
        val prefix = List(imageCount) { marker }.joinToString("\n")
        return if (content.isBlank()) prefix else "$prefix\n$content"
    }
}
