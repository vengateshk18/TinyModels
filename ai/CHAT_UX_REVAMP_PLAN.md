# Chat UX Revamp Plan

> **Goal:** Improve the end-to-end chat experience — model loading feedback, default model
> auto-selection, in-chat model switching, correct model handoff from model detail, and a
> fully-expanding inference settings sheet.
>
> **Format:** Code-free slice steps. No code snippets. Each slice is independently
> implementable and committed individually.

---

## Problem Summary

### Problem 1 — Model loading progress is not shown when opening a history session
- **Symptom:** Tapping a previous chat session loads the model silently. No progress bar,
  no feedback. The UI appears frozen until the model is ready.
- **Root Cause:** `openChatInternal()` calls `selectModel()` which updates
  `modelLoadProgress` in state — but because the observer runs on a background coroutine
  the composable may not receive intermediate updates before the load completes.
- **Expected:** A loading dialog with animated progress bar and stage labels should appear
  as soon as model loading begins, regardless of how fast the model loads. Even if the
  model loads instantly, the progress should animate to 100% before dismissing.

### Problem 2 — No default model is auto-loaded
- **Symptom:** Every time the user opens the Chat screen, `model` is `NotSelected`. They
  must manually tap the model chip and pick a model before chatting.
- **Root Cause:** `ChatViewModel.init` never calls `selectModel()` automatically.
- **Expected:** On entering the Chat screen, the ViewModel should automatically load the
  last-used model (tracked by a timestamp in the DB or preferences) or, if no last-used
  model exists, the most recently downloaded model.

### Problem 3 — No way to switch models mid-chat
- **Symptom:** Once a model is loaded, the user cannot switch to a different downloaded
  model without navigating away and back.
- **Root Cause:** The `ModelPickerSheet` exists in `ChatScreen` but is only shown when
  `model is ModelChipState.NotSelected`. When a model is already loaded, the chip is not
  tappable to switch.
- **Expected:** The model chip in the top bar should always be tappable. Tapping it opens
  `ModelPickerSheet` showing all downloaded models. Switching mid-chat unloads the current
  model, loads the new one, and rebuilds the conversation session.

### Problem 4 — Model is loaded in model detail screen, not in chat room
- **Symptom:** When the user taps "Start Chat" from the model detail screen, the model
  begins loading immediately in the model detail ViewModel. By the time the user arrives
  in the chat room, the model may already be loaded (or mid-load) without the user seeing
  proper progress.
- **Root Cause:** `DownloadedFileDetailViewModel.onStartChat()` calls
  `modelManager.loadModel()` before navigating to the chat screen.
- **Expected:** Model detail should only navigate to the chat room, passing the preferred
  model ID as a nav argument. All model loading must happen exclusively inside the chat
  room's `ChatViewModel`. This ensures the loading progress dialog is always visible
  and the correct lifecycle is respected.

### Problem 5 — Model preference not passed from model detail to chat room
- **Symptom:** When arriving in chat room from model detail, no model is pre-selected.
  The user still has to manually pick a model even though they just came from a specific
  model's detail page.
- **Root Cause:** Navigation to the chat room from model detail does not carry the
  preferred model ID. `ChatViewModel.init` ignores nav arguments for model preference.
- **Expected:** Model detail passes the `modelId` as a nav argument to the chat room.
  `ChatViewModel` reads this argument on init and uses it as the preferred model to load
  automatically, ahead of any last-used fallback.

### Problem 6 — Inference settings sheet opens only halfway
- **Symptom:** Tapping the Tune icon opens the `InferenceSettingsSheet` but it stops
  at roughly 50% screen height. The user has to manually drag it to full height.
- **Root Cause:** `rememberModalBottomSheetState()` is created without
  `skipPartiallyExpanded = true`, so the sheet stops at the partially-expanded anchor
  before reaching full height.
- **Expected:** The sheet should open to full height immediately when triggered. The user
  should never need to drag it up manually.

---

## Slice-Based Implementation Plan

---

### Slice 1 — Animate loading progress to 100% before dismissing
**Files:** `ChatViewModel.kt`, `ModelManager.kt`, `ModelLoadingDialog.kt`
**Commit:** `Animate model loading progress to 100 before dismissing dialog`

**Steps:**
1. In `ModelManager.loadModel()`, ensure the final `onProgress` callback is always called
   with `progress = 1.0f` and `stage = READY` before the method returns success.
2. In `ChatViewModel.selectModel()`, after receiving `AppResult.Success`, do not
   immediately set `modelLoadProgress = null`. Instead set it to `progress = 1.0f` and
   `stage = READY` first, then delay 400 ms before clearing it.
3. This guarantees the user always sees the progress bar reach 100% with the "Ready" label
   before the dialog disappears — even if the hardware loaded the model in under a second.
4. In `ModelLoadingDialog.kt`, confirm the `CircularProgressIndicator` uses determinate
   mode once `progress >= 0.05f` and shows the percentage label correctly.
5. Ensure the stage label reads "Ready!" when `stage == READY` instead of "Almost ready…"
   to give positive confirmation.
6. Test: Load a small model — confirm the dialog appears, animates to 100%, shows "Ready!",
   then disappears. Confirm the chat input bar becomes enabled immediately after.

---

### Slice 2 — Auto-load last used or last downloaded model on chat entry
**Files:** `ChatViewModel.kt`, `SettingsRepository.kt`, `ModelRepository.kt`
**Commit:** `Auto-load last used model when entering chat screen`

**Steps:**
1. In `SettingsRepository`, add a method `getLastUsedModelId(): String?` that reads a
   stored preference key. Add `setLastUsedModelId(modelId: String)` to update it.
2. In `ModelRepository`, add `getLastDownloadedModel(): DownloadedModel?` — returns the
   model with the most recent download timestamp.
3. In `ChatViewModel.init`, after `observeChats()`, `observeDownloadedModels()`, and
   `observeEngineState()` are started, launch a coroutine that:
   - Skips auto-load if `modelManager.loadedModelId != null` (model already in RAM).
   - Skips auto-load if `chatId != null && chatId != "new"` (opening an existing chat
     will load the correct model via `openChatInternal`).
   - Reads `settingsRepository.getLastUsedModelId()`.
   - If found and the model is still downloaded, calls `selectModel(lastUsedModelId)`.
   - If not found or no longer downloaded, calls `selectModel()` with the ID of the
     most recently downloaded model.
   - If no models are downloaded at all, does nothing (empty state shown).
4. In `ChatViewModel.selectModel()`, on successful load, call
   `settingsRepository.setLastUsedModelId(modelId)` to persist the choice.
5. Test: Fresh install, one model downloaded → open chat → confirm model loads
   automatically without user interaction.
6. Test: Use model A → close app → reopen → confirm model A loads automatically.
7. Test: Delete model A → reopen chat → confirm model B (last downloaded) loads.
8. Test: No models downloaded → confirm "No models yet" empty state is shown.

---

### Slice 3 — Make model chip always tappable to switch models
**Files:** `ChatScreen.kt`, `ChatViewModel.kt`, `ModelChip.kt`
**Commit:** `Allow switching models mid-chat from the model chip`

**Steps:**
1. In `ChatScreen.kt`, the `ModelChip` in the top bar `title` slot currently calls
   `showModelPicker = true`. Verify this already triggers regardless of the current
   `ModelChipState`. If `showModelPicker` is only opened when `NotSelected`, fix it
   to always open on tap.
2. In `ModelPickerSheet`, add a visual indicator (checkmark or highlight) on the
   currently active model row so the user can see which model is loaded.
3. When the user selects a different model in `ModelPickerSheet`:
   - Dispatch `ChatEvent.SelectModel(newModelId)`.
   - In `ChatViewModel.selectModel()`, if `newModelId == modelManager.loadedModelId`,
     do nothing and return early.
   - Otherwise, unload the current model first (call `modelManager.unloadModel()`
     inside the coroutine before loading the new one).
   - Then load the new model with progress updates.
   - After the new model loads, if there is an active chat, call `rebuildSession()` with
     the existing `activeChatId` so conversation history is replayed into the new model.
4. In `ModelChip.kt`, ensure the chip shows a subtle visual affordance (e.g. a small
   swap/chevron icon) to signal it is always tappable, not just when no model is selected.
5. Test: Load model A → tap chip → picker opens with model A highlighted → select
   model B → confirm loading dialog appears → confirm model B loads and chat resumes.
6. Test: Switching models mid-chat with messages → confirm history is preserved and
   replayed into the new model session.

---

### Slice 4 — Remove model loading from model detail, pass preferred model ID to chat
**Files:** `DownloadedFileDetailViewModel.kt`, `DownloadedFileDetailScreen.kt`,
`NavGraph.kt` (or wherever navigation is defined)
**Commit:** `Move model loading out of model detail into chat room`

**Steps:**
1. In `DownloadedFileDetailViewModel.kt`, find `onStartChat()` (or equivalent) — the
   method that currently calls `modelManager.loadModel()`.
2. Remove the `modelManager.loadModel()` call entirely from this ViewModel.
3. Change `onStartChat()` to simply return the `modelId` and navigate to the chat room,
   passing the `modelId` as a nav argument (e.g. `preferredModelId`).
4. In the navigation graph, add `preferredModelId` as an optional string nav argument on
   the chat room destination. Default value: `null`.
5. In `ChatViewModel`, read `preferredModelId` from `savedStateHandle` in `init`.
   If present and not null, use it as the highest-priority model to load — ahead of
   `lastUsedModelId` and the most-recently-downloaded fallback from Slice 2.
6. The auto-load logic from Slice 2 should cascade:
   - `preferredModelId` (from nav arg) → highest priority
   - `lastUsedModelId` (from settings) → second priority
   - Most recently downloaded model → fallback
7. In `DownloadedFileDetailScreen.kt`, update the "Start Chat" button to trigger the
   updated `onStartChat()` that navigates without loading.
8. Test: Tap "Start Chat" on model A's detail page → arrive in chat room → confirm model A
   loads inside the chat room with the progress dialog visible.
9. Test: Tap "Start Chat" on model B while model A is already in RAM → confirm model A is
   unloaded and model B loads in the chat room.
10. Test: Navigate from model detail → back press immediately → confirm no model was loaded
    and no RAM was consumed.

---

### Slice 5 — Fix inference settings sheet to open fully expanded
**Files:** `InferenceSettingsSheet.kt`
**Commit:** `Open inference settings sheet fully expanded by default`

**Steps:**
1. In `InferenceSettingsSheet.kt`, find the `rememberModalBottomSheetState()` call.
2. Pass `skipPartiallyExpanded = true` to `rememberModalBottomSheetState()` so the sheet
   skips the half-expanded anchor and opens directly to full height.
3. Verify `ModalBottomSheet` has `modifier = Modifier.fillMaxWidth()` on its content
   `Column`.
4. Add `modifier = Modifier.fillMaxHeight(0.9f)` on the `ModalBottomSheet` itself (or
   `wrapContentHeight()` with a `verticalScroll`) so that on small screens the sheet is
   scrollable and never clips content.
5. Confirm `navigationBarsPadding()` is still applied at the bottom of the content column
   so the Save/Discard buttons are not hidden behind the gesture bar.
6. Test: Open the settings sheet on a phone with a gesture navigation bar — confirm the
   sheet opens to full height immediately, all controls are visible, and the Save button
   is accessible without scrolling on standard screen sizes.
7. Test: On a small-screen device (< 5") — confirm the content is scrollable and no
   controls are unreachable.

---

## Execution Order

```
Slice 1 (animate progress to 100%)
    │
    ▼
Slice 2 (auto-load last used / last downloaded model)
    │
    ▼
Slice 3 (always-tappable model chip for switching)
    │
    ▼
Slice 4 (move model loading out of model detail)
    │
    ▼
Slice 5 (fix settings sheet full expansion)
```

Slices 1 and 5 are fully independent — they can be done in any order.
Slices 2, 3, and 4 build on each other conceptually but are independently committable.
Slice 4 depends on Slice 2's auto-load priority cascade being in place.

---

## Verification Checklist

### Slice 1 — Progress animates to 100%
- [ ] Dialog appears immediately on model load start
- [ ] Progress bar reaches 100% and shows "Ready!" before dismissing
- [ ] Dialog visible for at least 400 ms even on fast hardware
- [ ] Input bar enabled immediately after dialog dismisses

### Slice 2 — Auto-load default model
- [ ] Last used model loads automatically on chat entry (no user tap required)
- [ ] Falls back to most recently downloaded model if last used is unavailable
- [ ] Shows empty state if no models downloaded
- [ ] `lastUsedModelId` updated in preferences after each successful load

### Slice 3 — Model switching mid-chat
- [ ] Model chip is tappable when a model is already loaded
- [ ] Picker highlights currently active model
- [ ] Switching triggers unload → load sequence with progress dialog
- [ ] Chat history is preserved and replayed after model switch

### Slice 4 — Model loading moved to chat room
- [ ] "Start Chat" from model detail does NOT call `loadModel()`
- [ ] Preferred model ID passed as nav argument to chat room
- [ ] Loading dialog appears in chat room, not in model detail
- [ ] Back-pressing from model detail before chat loads does not leave a model in RAM

### Slice 5 — Settings sheet full expansion
- [ ] Sheet opens to full height immediately — no manual drag required
- [ ] All controls visible without scrolling on standard screen sizes
- [ ] Scrollable on small screens
- [ ] Save/Discard buttons not hidden behind gesture bar

---

## Theme & Font Rules

All changes must follow existing project conventions:
- All colours from `MaterialTheme.colorScheme` only — no hardcoded hex values.
- All text styles from `MaterialTheme.typography` only.
- Minimum touch target: 48 dp for all interactive elements.
- Support font scale up to 200% — no clipped text.
- Use `imePadding()` on the `ChatInputBar` parent.
- Use `navigationBarsPadding()` on all bottom-anchored sheets and dialogs.
- Edge-to-edge: `WindowInsets` respected throughout.

---

## Commit Messages

```
Slice 1  — Animate model loading progress to 100 before dismissing dialog
Slice 2  — Auto-load last used model when entering chat screen
Slice 3  — Allow switching models mid-chat from the model chip
Slice 4  — Move model loading out of model detail into chat room
Slice 5  — Open inference settings sheet fully expanded by default
```

---

**Plan version:** 1.0
**Created:** fresh plan for Chat UX improvements
**Status:** All slices pending implementation
