# Chat Interface Revamp Plan

> Goal: Fix the response-printing bug, add edge-to-edge insets, cancel / regenerate /
> edit-prompt, per-message timestamps, and a polished custom input bar — delivered as
> thin vertical slices, each committed separately.

---

## 1. Problem analysis (current bugs)

### 1.1 Response not printing properly
- **Root cause**: `ChatViewModel.observeMessages()` has `if (generation == GENERATING) return@collect`.
  This blocks the Room flow from updating the UI during generation. If the optimistic
  user-message append is lost (e.g. config change, or the optimistic update is applied
  *before* `observeMessages` emits), the user's own message may not appear.
- **Race**: The streaming assistant placeholder is appended optimistically, but
  `observeMessages` runs concurrently. When generation ends and the guard lifts, the
  Room flow re-emits the full list — overwriting the streaming text with the persisted
  version. If persistence hasn't flushed yet, the partial text vanishes.
- **Markdown**: Partial markdown (unclosed code fences, half-finished lists) can render
  as broken/unstyled text mid-stream.

### 1.2 No edge-to-edge handling
- `MainActivity.enableEdgeToEdge()` is called, but `ChatScreen` relies solely on
  `Scaffold`'s `padding`. The `TopAppBar` doesn't extend behind the status bar
  consistently, and the `ChatInputBar` applies `navigationBarsPadding()` + `imePadding()`
  but the message `LazyColumn` doesn't add `statusBarsPadding` when scrolled under the
  transparent top bar. Drawer content also lacks inset handling.

### 1.3 Cancel is incomplete
- `cancelGeneration()` cancels the coroutine `Job` but doesn't tell the LiteRT-LM
  `Conversation` to stop generating. The engine continues in the background until the
  next GC. Partial text is kept but the KV-cache state may be inconsistent.

### 1.4 No regeneration
- No `ChatEvent.Regenerate` exists. Re-running the last prompt requires deleting the
  last assistant message and re-sending — not implemented.

### 1.5 No editing
- No `ChatEvent.EditMessage` exists. User prompts are immutable once sent.

### 1.6 No timestamps
- `UiChatMessage.timestamp` is populated but `MessageBubble` never renders it.
  No "sent at" / "completed at" times below messages.

### 1.7 Input bar is basic
- Plain `OutlinedTextField` + send/stop `FilledIconButton`. No animated multi-line
  growth, no edit-mode visual distinction, no clear button, no disabled-state styling.

---

## 2. Proposed changes (thin vertical slices)

### Slice C1 — Fix streaming + message list race
**Goal**: Response prints correctly every time.
- Remove the `if (generation == GENERATING) return@collect` blanket guard.
- Instead, merge the streaming assistant placeholder with Room updates: always apply
  Room messages for **non-streaming** items, but preserve the in-flight streaming
  message (identified by `assistantId`) from the optimistic UI.
- Add a `streamingMessageId: String?` to `ChatUiState`; the `observeMessages` collector
  merges: `roomMessages + (streaming placeholder if active)`.
- Guard the Markdown renderer against partial input: wrap in a try/catch and fall back
  to plain `Text` if parsing fails.
- **Commit**: `fix(chat): C1 — fix streaming response + message list race`

### Slice C2 — Edge-to-edge insets across all screens
**Goal**: Content respects status bar, nav bar, and IME everywhere.
- `ChatScreen`: apply `Modifier.statusBarsPadding()` to the `TopAppBar` area (or use
  `Scaffold` with `contentWindowInsets` properly), and `imePadding()` +
  `navigationBarsPadding()` to the input bar (already partial).
- `MessageList`: add `consumeWindowInsets` + vertical content padding that accounts for
  the top bar height so messages don't hide behind the status bar.
- `ChatHistoryDrawer`: add `statusBarsPadding()` to drawer content.
- Verify `ModelDetailsScreen`, `DownloadedModelsScreen`, `SettingsScreen`,
  `ModelsBrowseScreen` also handle insets (they use Scaffold, so mostly OK — verify).
- **Commit**: `fix(chat): C2 — edge-to-edge insets for chat + drawer`

### Slice C3 — Per-message timestamps
**Goal**: Show sent/completed times below each message.
- Add `completedAt: Long?` to `UiChatMessage` (for assistant messages).
- Add `completedAt` to the `ChatMessage` domain model + Room entity + DAO (migration
  or `ALTER TABLE` additive column).
- `MessageBubble`: render a timestamp row below the bubble content:
  - User: `"Sent  14:32"`
  - Assistant: `"Sent 14:32  ·  Completed 14:33  ·  1.2s"` (duration when available).
- Use a shared `Formatters.formatTime(epochMs)` helper.
- **Commit**: `feat(chat): C3 — per-message sent/completed timestamps`

### Slice C4 — Proper cancel generation
**Goal**: Stop button halts the engine cleanly.
- Add `ConversationSession.stop()` that cancels the LiteRT-LM generation (if the API
  supports it; otherwise document the limitation and cancel the flow + mark partial).
- `ChatViewModel.cancelGeneration()`: call `session.stop()` + cancel the job + persist
  partial text as `isComplete = false`.
- UI: the Stop button already exists; ensure it's enabled and visible during generation.
- **Commit**: `feat(chat): C4 — proper cancel with engine stop + partial persistence`

### Slice C5 — Regenerate last response
**Goal**: Re-run the last user prompt to get a fresh assistant reply.
- Add `ChatEvent.Regenerate` to MVI.
- `ChatViewModel.onRegenerate()`:
  1. Find the last user message in the active chat.
  2. Delete the last assistant message (if any) from Room.
  3. Remove the last assistant turn from the `ConversationSession` history (if possible;
  otherwise rebuild the session from trimmed history).
  4. Re-run `runGeneration()` with the same user prompt.
- UI: add a "Regenerate" icon button below the last assistant message (or in the input
  bar when idle and last message is assistant).
- **Commit**: `feat(chat): C5 — regenerate last response`

### Slice C6 — Edit and re-send a prompt
**Goal**: Tap a user message to edit it, replacing everything after it.
- Add `ChatEvent.EditMessage(messageId, newText)` to MVI.
- `ChatViewModel.onEditMessage()`:
  1. Find the user message by id.
  2. Delete it + all messages after it from Room.
  3. Rebuild the `ConversationSession` from the remaining history.
  4. Send the edited text as a new prompt.
- UI: user messages become tappable (not just long-press copy). Tapping opens an edit
  sheet/dialog with the original text in a text field + Save/Cancel.
- Show an "edited" indicator on the message.
- **Commit**: `feat(chat): C6 — edit and re-send a prompt`

### Slice C7 — Polished custom input bar
**Goal**: A beautiful, functional composer.
- Replace `OutlinedTextField` with a custom surface:
  - Rounded container card with `surfaceContainerHigh` color.
  - Multi-line text that grows up to 6 lines, then scrolls internally.
  - Animated height transition when growing.
  - Trailing row: Send (filled, primary) / Stop (filled, error) / and when editing, a
    "Cancel edit" text button.
  - Leading: a character/token count (`123 chars · ~31 tokens`) in muted color.
  - Disabled state: dimmed + "Load a model to chat" placeholder.
- When `canSend == false` and not generating: show a subtle hint row above the bar
  ("Pick a model" / "Loading model…").
- **Commit**: `feat(chat): C7 — polished custom input bar with animated growth`

### Slice C8 — Chat ViewModel unit tests
**Goal**: Cover the new flows.
- `ChatViewModelTest`: send message (stream mock), cancel, regenerate, edit, timestamp
  persistence.
- Use fake `ChatRepository` + mockk for `ModelManager` / `SettingsRepository`.
- **Commit**: `test(chat): C8 — ViewModel tests for cancel/regenerate/edit/timestamps`

---

## 3. Data model changes

### ChatMessage (domain + Room)
```kotlin
data class ChatMessage(
    val id: String,
    val chatId: String,
    val role: Role,
    val content: String,
    val tokenCount: Int,
    val createdAt: Long,         // when the message was sent/created
    val completedAt: Long?,      // NEW: when generation finished (assistant only)
    val isComplete: Boolean = true,
    val isEdited: Boolean = false // NEW: user message was edited
)
```
- Room: additive migration (add `completedAt INTEGER` + `isEdited INTEGER DEFAULT 0`).

### UiChatMessage
```kotlin
data class UiChatMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val isStreaming: Boolean = false,
    val timestamp: Long = 0L,       // sent time
    val completedAt: Long? = null,   // completed time (assistant)
    val isEdited: Boolean = false    // edited indicator (user)
)
```

---

## 4. UI component changes

| Component | Change |
|----------|--------|
| `MessageBubble` | + timestamp row below content, + regenerate button (last assistant), + tap-to-edit (user), + edit indicator, + safe Markdown fallback |
| `ChatInputBar` | Full rewrite: custom container, animated multi-line, token count, edit-mode, disabled hint |
| `ChatScreen` | Inset handling, streaming merge, empty-state polish |
| `ChatHistoryDrawer` | `statusBarsPadding` |

---

## 5. New MVI events
```kotlin
sealed interface ChatEvent {
    // existing
    data class SelectModel(val modelId: String) : ChatEvent
    data class SendMessage(val text: String) : ChatEvent
    data object CancelGeneration : ChatEvent
    data object NewChat : ChatEvent
    data class OpenChat(val chatId: String) : ChatEvent
    data class DeleteChat(val chatId: String) : ChatEvent
    data object DismissError : ChatEvent
    // new
    data object Regenerate : ChatEvent
    data class EditMessage(val messageId: String, val newText: String) : ChatEvent
}
```

---

## 6. Execution order & dependencies

```
C1 (fix streaming) ──► C3 (timestamps) ──► C5 (regenerate)
                  └──► C2 (insets)         └──► C6 (edit)
                  └──► C4 (cancel)         └──► C7 (input bar)
                                            └──► C8 (tests)
```

C1 first (fixes the core bug). C2/C3/C4 independent. C5/C6 depend on C1+C3.
C7 depends on C6 (edit mode in bar). C8 last.

---

## 7. Verification

Each slice:
```bash
./gradlew :app:assembleDebug          # BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest      # green (C8 adds new tests)
```

Manual checklist (on device):
- [ ] Send a prompt → response streams correctly, no duplication, no missing text.
- [ ] Content extends behind status/nav bars without overlap.
- [ ] Stop button halts generation, partial text retained.
- [ ] Regenerate re-runs the last prompt.
- [ ] Edit a user message → replaces downstream messages.
- [ ] Timestamps visible below each message.
- [ ] Input bar grows smoothly, shows token count, edit mode works.
