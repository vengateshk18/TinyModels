# Chat Feature Revamp Plan

> **Goal:** Fix model loading progress indicator, prevent unnecessary session creation, ensure
> proper model cleanup on exit, and add inference session settings — delivered as **thin vertical
> slices**, each committed separately.
> **Format:** Code-free steps only. No code snippets. Each slice is independently implementable
> and commit-ready.

---

## Problem Summary

### Issue 1: Model Loading Progress — DONE ✅
- **Symptom:** When entering a chat, the model loads silently with no feedback.
- **Root Cause:** No `LoadingDialog` or progress state in UI.
- **Status:** `ModelLoadingDialog.kt` created, `modelLoadProgress` wired in `ChatViewModel` and shown in `ChatScreen`.

### Issue 2: Unnecessary Session Creation — DONE ✅
- **Symptom:** Every time a model is selected (even browsing), a new empty chat session
  is created in the database.
- **Root Cause:** `sendMessage()` in `ChatViewModel` calls `chatRepository.createChat()`
  even before the user types anything, because `activeChatId` is always null on first entry.
  The `NewChat` drawer button also creates a chat immediately on tap — before any message is sent.
- **What should happen:** A session row must only exist in the DB once the user sends the
  **first real message**. Model selection and navigation must not write to the DB.

### Issue 3: Model Not Unloaded on Exit — DONE ✅
- **Symptom:** After leaving the chat screen the model stays resident in RAM.
- **Root Cause:** `onCleared()` in `ChatViewModel` calls `modelManager.unloadModel()` inside
  a new `viewModelScope.launch` — but `viewModelScope` is cancelled the moment `onCleared()`
  returns, so the coroutine never runs.
- **What should happen:** `unloadModel()` must be called as a blocking/synchronous call or
  via a scope that outlives `onCleared()`.

### Issue 4: No Inference Settings UI — DONE ✅
- **Symptom:** No way to change temperature, max tokens, top-k, top-p, backend, or system
  instruction per session from within the chat screen.
- **Root Cause:** `InferenceSettings` is stored per-chat in the DB but there is no dialog or
  sheet to surface or edit it in `ChatScreen`.

---

## Slice-Based Implementation Plan

---

### Slice 1: Model Loading Progress Indicator — DONE ✅
**Files:** `ChatScreen.kt`, `ChatViewModel.kt`, `ChatUi.kt`, `ModelLoadingDialog.kt`
**Commit:** `Implement model loading progress indicator`

**What was done:**
- Added `modelLoadProgress: ModelLoadProgress?` to `ChatUiState`.
- `ModelManager.loadModel()` now accepts `onProgress: ((Float, ModelLoadStage) -> Unit)?`.
- `ChatViewModel.selectModel()` passes progress updates into `_uiState`.
- Created `ModelLoadingDialog` composable with spinner, stage label, linear progress bar, and percentage.
- `ChatScreen` renders the dialog overlay whenever `modelLoadProgress != null`.

---

### Slice 2: Prevent Empty Session Creation
**Files:** `ChatViewModel.kt`
**Commit:** `Create chat session only on first message, not on model selection`

**Steps:**
1. Read `sendMessage()` in `ChatViewModel` — locate the block that calls
   `chatRepository.createChat()` when `chatId == null`.
2. Verify this block already runs lazily (only when a message is being sent) — confirm
   no `createChat()` is called at model-selection time or in `init`.
3. In `newChat()` — currently calls `chatRepository.createChat()` immediately on tap.
   Change it so tapping "New Chat" only **clears** the active chat state (`activeChatId = null`,
   `messages = emptyList()`) without writing to the DB.
4. The actual `createChat()` DB write stays only inside `sendMessage()`, triggered on first
   message send with `chatId == null`.
5. Update `ChatHistoryDrawer` "New Chat" button label/icon to reflect that it resets the
   current view, not that it creates a persisted chat.
6. Verify `observeChats()` is called after `init` and not before — sessions in the drawer
   list should only appear after a message is sent.
7. Test: Select model → Do NOT send a message → Navigate away → Open chat history → Confirm
   no empty session exists in the list.
8. Test: Select model → Send one message → Confirm a single session now appears in the list
   with the correct title.

---

### Slice 3: Properly Unload Model on Chat Exit
**Files:** `ChatViewModel.kt`, `ModelManager.kt`
**Commit:** `Unload model synchronously when leaving chat`

**Steps:**
1. In `ModelManager`, add a non-suspend `unloadModelSync()` method that acquires the mutex
   in a blocking fashion and closes the engine — suitable to call from `onCleared()`.
   Alternatively, use a dedicated `ApplicationScope` (injected) that survives ViewModel
   destruction, so a coroutine launched there will complete after `onCleared()` returns.
2. Remove the `viewModelScope.launch { modelManager.unloadModel() }` call from
   `ChatViewModel.onCleared()` — replace it with a call that uses the long-lived scope.
3. Inject an `@ApplicationScope CoroutineScope` into `ChatViewModel` via Hilt. Launch
   `unloadModel()` on that scope inside `onCleared()`.
4. Confirm `unloadModel()` emits `EngineState.Idle` so any other observer reacts correctly.
5. Add a brief `Snackbar` or `Toast` in `ChatScreen` via `LaunchedEffect` on
   `engineState == Idle` to show "Model unloaded" — optional, keep it subtle.
6. Test: Open chat with a model → Confirm model loads → Press back → Use Android memory
   profiler or log to verify `Engine.close()` is called and RAM drops.
7. Test: Return to chat → Confirm model reloads fresh.

---

### Slice 4: Inference Settings Dialog
**Files:** `InferenceSettingsSheet.kt` (new), `ChatScreen.kt`, `ChatViewModel.kt`,
`ChatUi.kt`
**Commit:** `Add per-session inference settings sheet`

**Steps:**
1. Add `showInferenceSettings: Boolean = false` to `ChatUiState`.
2. Add two events to `ChatEvent`: `OpenInferenceSettings` and `CloseInferenceSettings`.
3. In `ChatViewModel.onEvent()`, handle both events to toggle `showInferenceSettings`.
4. Create `InferenceSettingsSheet.kt` as a `ModalBottomSheet` composable with:
   - Temperature slider (range 0.0–2.0, step 0.05, default 0.7) with value label.
   - Max context tokens dropdown or segmented button (512 / 1024 / 2048 / 4096).
   - Top-K numeric field (default 40).
   - Top-P slider (range 0.0–1.0, step 0.05, default 0.95).
   - Backend dropdown (AUTO / CPU / GPU).
   - System instruction text field (multi-line, optional).
   - Save button and Discard button.
   - All labels use `MaterialTheme.typography`; all colours from `MaterialTheme.colorScheme`.
5. In `ChatScreen` top bar `actions`, add a settings `IconButton` (tune icon) next to the
   existing settings icon — wire it to `ChatEvent.OpenInferenceSettings`.
   Show the icon only when `model is ModelChipState.Ready`.
6. Show `InferenceSettingsSheet` when `uiState.showInferenceSettings` is true.
7. On Save: dispatch `ChatEvent.UpdateInferenceSettings(settings)` — already handled in
   `ChatViewModel.updateInferenceSettings()`.
8. On Discard: dispatch `ChatEvent.CloseInferenceSettings`.
9. Pre-populate sheet fields from `uiState.inferenceSettings` (already in `ChatUiState`).
10. Verify the sheet title says "Session settings" and has a subtitle "Changes apply to
    this chat only."
11. Test: Open sheet → change temperature → Save → Send a message → Confirm the new
    temperature is used (check logs or response behaviour).
12. Test: Reopen chat → Confirm settings persisted (they are per-chat in DB via
    `chatRepository.updateInferenceSettings()`).

---

### Slice 5: Settings Persistence Verification
**Files:** `SettingsRepository.kt`, `ChatRepositoryImpl.kt`
**Commit:** `Verify and harden per-chat inference settings persistence`

**Steps:**
1. Confirm `Chat` domain model contains all `InferenceSettings` fields and they are
   stored as columns in the Room entity `ChatEntity`.
2. Confirm `updateInferenceSettings()` in `ChatRepositoryImpl` writes all fields to DB.
3. Confirm `getChat()` and `observeMessages()` correctly restore `inferenceSettings` when
   reopening a session.
4. Add a fallback: if any field is missing/null in the DB row (e.g. old sessions before this
   feature), use defaults from `InferenceSettings()`.
5. Write a unit test in `ChatRepositoryTest`: create chat → update settings → retrieve chat
   → assert all settings fields match.
6. Test edge case: app killed mid-chat → reopen → settings restored.

---

### Slice 6: Polish and Regression Testing
**Files:** All chat feature files
**Commit:** `Chat feature polish: accessibility, edge cases, regression checks`

**Steps:**
1. Verify `ModelLoadingDialog` is not dismissible via back-press or tap-outside —
   confirm `onDismissRequest = {}` is set.
2. Verify that when the model is loading (`isLoadingModel = true`), the `ChatInputBar`
   is disabled so users cannot attempt to send before the model is ready.
3. Verify "No models downloaded" empty state navigates correctly to the Models tab.
4. Verify the drawer chat list updates in real-time when a new session is created
   (first message sent).
5. Verify deleting the active chat clears `activeChatId` and resets the message list.
6. Ensure all touch targets ≥ 48 dp (check the settings icon button in top bar).
7. Check font scale at 200% — all text in `ModelLoadingDialog` and `InferenceSettingsSheet`
   must remain readable.
8. Verify `ChatScreen` has edge-to-edge support (`imePadding`, `navigationBarsPadding`).
9. Run all existing unit tests: `./gradlew :app:testDebugUnitTest`.

---

## Execution Order

```
Slice 1 ✅ (done)
    │
    ▼
Slice 2 (no empty sessions)
    │
    ▼
Slice 3 (unload on exit)
    │
    ▼
Slice 4 (inference settings dialog)
    │
    ▼
Slice 5 (persistence verification)
    │
    ▼
Slice 6 (polish + testing)
```

Slices 2 and 3 are independent and can be done in either order.
Slice 4 depends on the `ChatUiState` and event infra being stable (Slices 1–3).
Slice 5 depends on Slice 4.
Slice 6 is last.

---

## Verification Checklist

### Slice 1 ✅
- [x] Progress dialog appears during model load
- [x] Progress updates smoothly (0% → 100%)
- [x] Dialog dismisses only on completion
- [x] No UI freeze during load

### Slice 2
- [ ] No DB row created when model is selected
- [ ] No DB row created when "New Chat" is tapped before any message
- [ ] DB row created exactly once when first message is sent
- [ ] Existing session reused when navigating back to same chat

### Slice 3
- [ ] `Engine.close()` called when navigating away from chat
- [ ] `EngineState.Idle` observed after exit
- [ ] RAM usage visibly drops in profiler after exit
- [ ] Model reloads cleanly on re-entry

### Slice 4
- [ ] Settings sheet opens from tune icon
- [ ] All controls render correctly at all font scales
- [ ] Save updates `inferenceSettings` in `ChatUiState`
- [ ] Discard leaves settings unchanged
- [ ] Sheet follows Material3 theme

### Slice 5
- [ ] Settings survive app restart
- [ ] Missing fields default to `InferenceSettings()` values
- [ ] Unit test passes

### Slice 6
- [ ] No regressions in existing chat, download, or model-browse flows
- [ ] All unit tests pass
- [ ] Edge-to-edge layout correct

---

## Theme & Font Rules

All new UI must:
- Use `MaterialTheme.colorScheme` for all colours.
- Use `MaterialTheme.typography` for all text styles.
- Support font scale up to 200% without text being clipped.
- Use `imePadding()` and `navigationBarsPadding()` on bottom-anchored composables.
- Minimum touch target: 48 dp.
- Contrast ratio: ≥ 4.5:1 for body text.

---

## Commit Messages

```
Slice 1  — Implement model loading progress indicator          ✅ done
Slice 2  — Create chat session only on first message
Slice 3  — Unload model synchronously when leaving chat
Slice 4  — Add per-session inference settings sheet
Slice 5  — Verify and harden per-chat inference settings persistence
Slice 6  — Chat feature polish and regression checks
```

---

**Plan version:** 3.0  
**Last updated:** after full implementation pass  
**Status:** Slices 1–4 complete. Slice 5 (persistence verification) and Slice 6 (polish) remain.
