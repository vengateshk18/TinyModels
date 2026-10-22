package com.ai.tinymodels.feature.chat.model

import androidx.annotation.StringRes
import com.ai.tinymodels.R

/**
 * Contents of the "+" menu in the chat input bar.
 *
 * Pure model (no Compose) so the menu's structure and the web-search switch
 * state are pinned by unit tests; `ChatInputBar` only renders it.
 */
sealed interface PlusMenuItem {
    /** Photo-attach entry. Disabled with reason text when model lacks vision. */
    data class AttachPhoto(
        val enabled: Boolean,
        /**
         * Resource id of the human-readable reason shown when disabled
         * (e.g. "This model can't read photos..."). Null = enabled or just busy.
         */
        @StringRes val disabledReasonRes: Int? = null
    ) : PlusMenuItem

    /**
     * Web-search toggle entry, rendered with a [androidx.compose.material3.Switch].
     * [checked] is the tool's EFFECTIVE state: the global availability gate
     * AND the per-chat opt-in must both hold for the tool to run.
     */
    data class WebSearch(val checked: Boolean, val enabled: Boolean) : PlusMenuItem
}

/**
 * Builds the "+" menu in fixed order: photo first, then web search.
 *
 * @param webSearchAvailable global availability gate (Settings switch).
 * @param webSearchEnabled per-chat opt-in.
 * @param canAttachPhoto whether a photo can be picked right now.
 * @param supportsVision whether the loaded model has a vision encoder.
 *   Text-only models show a disabled photo entry with explanatory text.
 */
fun buildPlusMenuItems(
    webSearchAvailable: Boolean,
    webSearchEnabled: Boolean,
    canAttachPhoto: Boolean,
    supportsVision: Boolean
): List<PlusMenuItem> = listOf(
    PlusMenuItem.AttachPhoto(
        enabled = supportsVision && canAttachPhoto,
        disabledReasonRes = if (supportsVision) null
        else R.string.chat_attach_photo_unavailable
    ),
    PlusMenuItem.WebSearch(
        checked = webSearchAvailable && webSearchEnabled,
        enabled = webSearchAvailable
    )
)
