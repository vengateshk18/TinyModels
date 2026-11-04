package com.ai.tinymodels.feature.chat

import com.ai.tinymodels.domain.model.DownloadedModel
import com.ai.tinymodels.domain.repository.ChatRepository
import com.ai.tinymodels.domain.repository.ModelRepository
import com.ai.tinymodels.domain.repository.SettingsRepository
import com.ai.tinymodels.feature.chat.model.ChatListItem
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Feature tests for [ChatListViewModel]: renaming a chat, pinning a chat,
 * and the pinned-first ordering of the history list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModelTest {

    private val chatRepo = mockk<ChatRepository>(relaxed = true)
    private val modelRepo = mockk<ModelRepository>(relaxed = true)
    private val settingsRepo = mockk<SettingsRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { chatRepo.observeChatSummaries() } returns flowOf(emptyList())
        every { modelRepo.observeDownloadedModels() } returns flowOf(emptyList<DownloadedModel>())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ChatListViewModel(chatRepo, modelRepo, settingsRepo)

    // ---- Title sanitising ----

    @Test
    fun `sanitize trims surrounding whitespace`() {
        assertEquals("Trip ideas", sanitizeChatTitle("  Trip ideas  "))
    }

    @Test
    fun `sanitize collapses internal whitespace and newlines`() {
        assertEquals("My long title", sanitizeChatTitle("My   long\n\ntitle"))
    }

    @Test
    fun `sanitize rejects blank titles`() {
        assertNull(sanitizeChatTitle("   "))
        assertNull(sanitizeChatTitle(""))
        assertNull(sanitizeChatTitle("\n\t "))
    }

    @Test
    fun `sanitize caps the title length`() {
        val long = "a".repeat(200)
        assertEquals(MAX_CHAT_TITLE_LENGTH, sanitizeChatTitle(long)?.length)
    }

    @Test
    fun `sanitize does not leave a trailing space after truncating`() {
        // 59 a's, then a space, then more text — the cut lands on the space.
        val raw = "a".repeat(MAX_CHAT_TITLE_LENGTH - 1) + " bcd"
        assertEquals("a".repeat(MAX_CHAT_TITLE_LENGTH - 1), sanitizeChatTitle(raw))
    }

    // ---- Rename ----

    @Test
    fun `rename persists the sanitized title`() = runTest {
        vm().renameChat("chat-1", "  New   name  ")
        coVerify { chatRepo.renameChat("chat-1", "New name") }
    }

    @Test
    fun `rename ignores a blank title`() = runTest {
        vm().renameChat("chat-1", "    ")
        coVerify(exactly = 0) { chatRepo.renameChat(any(), any()) }
    }

    // ---- Pin ----

    @Test
    fun `togglePin pins an unpinned chat`() = runTest {
        vm().togglePin(item(id = "chat-1", isPinned = false))
        coVerify { chatRepo.setChatPinned("chat-1", true) }
    }

    @Test
    fun `togglePin unpins a pinned chat`() = runTest {
        vm().togglePin(item(id = "chat-1", isPinned = true))
        coVerify { chatRepo.setChatPinned("chat-1", false) }
    }

    // ---- Ordering ----

    @Test
    fun `pinned chats sort before unpinned ones`() {
        val unpinnedNewest = item(id = "a", isPinned = false, updatedAt = 300)
        val pinnedOldest = item(id = "b", isPinned = true, updatedAt = 100)
        val unpinnedOlder = item(id = "c", isPinned = false, updatedAt = 200)

        val sorted = listOf(unpinnedNewest, unpinnedOlder, pinnedOldest).sortedForDisplay()

        assertEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    // ---- Rename dialog cursor ----

    @Test
    fun `rename opens with the whole title selected, not a cursor at the start`() {
        val title = "Trip ideas"
        val selection = renameSelection(title)

        assertEquals(0, selection.start)
        assertEquals(title.length, selection.end)
        // A collapsed range would be a bare cursor — the bug being fixed.
        assertFalse(selection.collapsed)
    }

    @Test
    fun `rename selection is empty when the title is empty`() {
        val selection = renameSelection("")

        assertEquals(0, selection.start)
        assertEquals(0, selection.end)
    }

    private fun item(
        id: String,
        isPinned: Boolean,
        updatedAt: Long = 0
    ) = ChatListItem(
        id = id,
        title = "Chat $id",
        preview = null,
        updatedAt = updatedAt,
        isPinned = isPinned
    )
}
