package com.ai.tinymodels.feature.chat.model

import com.ai.tinymodels.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The "+" menu in the chat input bar holds the photo-attach entry and the
 * web-search toggle. These pin the menu's contents and the switch state so
 * the UI cannot silently drop an entry or misreport the toggle.
 */
class ChatOptionsMenuTest {

    @Test fun `menu holds attach-photo then web-search when all usable`() {
        val items = buildPlusMenuItems(
            webSearchAvailable = true,
            webSearchEnabled = false,
            canAttachPhoto = true,
            supportsVision = true
        )
        assertEquals(
            listOf(
                PlusMenuItem.AttachPhoto(enabled = true, disabledReasonRes = null),
                PlusMenuItem.WebSearch(checked = false, enabled = true)
            ),
            items
        )
    }

    @Test fun `web-search switch reflects the opt-in state`() {
        val items = buildPlusMenuItems(
            webSearchAvailable = true,
            webSearchEnabled = true,
            canAttachPhoto = true,
            supportsVision = true
        )
        assertEquals(PlusMenuItem.WebSearch(checked = true, enabled = true), items[1])
    }

    @Test fun `web-search switch is off and disabled when globally unavailable`() {
        val items = buildPlusMenuItems(
            webSearchAvailable = false,
            webSearchEnabled = true,
            canAttachPhoto = true,
            supportsVision = true
        )
        // The tool cannot run while the global gate is off, so the switch
        // must not claim it is on.
        assertEquals(PlusMenuItem.WebSearch(checked = false, enabled = false), items[1])
    }

    @Test fun `attach-photo is disabled while it cannot be used`() {
        val items = buildPlusMenuItems(
            webSearchAvailable = true,
            webSearchEnabled = false,
            canAttachPhoto = false,
            supportsVision = true
        )
        assertEquals(PlusMenuItem.AttachPhoto(enabled = false, disabledReasonRes = null), items[0])
    }

    @Test fun `text-only model shows disabled attach-photo with explanatory text`() {
        val items = buildPlusMenuItems(
            webSearchAvailable = true,
            webSearchEnabled = false,
            canAttachPhoto = true,
            supportsVision = false
        )
        val photoItem = items[0] as PlusMenuItem.AttachPhoto
        assertEquals(false, photoItem.enabled)
        assertEquals(R.string.chat_attach_photo_unavailable, photoItem.disabledReasonRes)
        // Web search still present
        assertEquals(PlusMenuItem.WebSearch(checked = false, enabled = true), items[1])
    }

    @Test fun `text-only menu keeps the web-search switch state`() {
        val items = buildPlusMenuItems(
            webSearchAvailable = true,
            webSearchEnabled = true,
            canAttachPhoto = true,
            supportsVision = false
        )
        val photoItem = items[0] as PlusMenuItem.AttachPhoto
        assertEquals(false, photoItem.enabled)
        assertEquals(R.string.chat_attach_photo_unavailable, photoItem.disabledReasonRes)
        assertEquals(PlusMenuItem.WebSearch(checked = true, enabled = true), items[1])
    }
}
