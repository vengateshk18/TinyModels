# Chat Room Improvements Plan (Model File Picker, Smooth Loading, History Revamp)

> **Goal:** A file-level model picker sheet in the chat room, smooth (simulated) model-loading
> progress, and revamped chat-history rows showing preview + time + message count + last used
> model + delete.
>
> **Format:** Code-free slice steps. No code snippets. Each slice is independently
> implementable and committed individually.

---

## Problem Summary

### Problem 1 — Model picker shows parent models only, not individual files
- **Symptom:** In the chat room, the picker lists one row per downloaded model repo. A repo
  can contain multiple runnable `.litertlm` files (e.g. different quantizations), but only the
  **first** downloaded file is ever loaded. The user cannot choose which file to run. Also,
  the chat room top bar has no model chip — the picker is only reachable from the
  "Pick a model" empty state.
- **Root Cause:** `ModelPickerSheet` is driven by `DownloadedModel` parents only;
  `ChatEvent.SelectModel` carries only a `modelId`; `ChatViewModel.resolveModelFile()` always
  resolves `getDownloadedFileForModel()` (first file).
- **Expected:** A bottom sheet in the chat room listing **every downloaded file grouped by
  its model** (file name + size), with the active file highlighted. Selecting a file loads
  exactly that file. A tappable model chip in the chat room top bar opens the sheet anytime
  (mid-chat switching included).

### Problem 2 — Loading progress stalls at 30% then jumps to 100%
- **Symptom:** The loading dialog climbs to ~30%, freezes while the native engine
  initializes, then jumps straight to 100% / "Ready!".
- **Root Cause:** `ModelManager.loadModel()` emits only 4 discrete callbacks
  (0.1 → 0.3 → 0.5 → 1.0) around one long-blocking `initializeEngine()` call. No
  intermediate values exist.
- **Expected:** Smooth, always-moving progress. A simulated progress ticker runs alongside
  the real load, asymptotically approaching ~90% (never reaching it); real completion snaps
  to 100% with "Ready!" and the existing 400 ms hold before dismissing.

### Problem 3 — History list rows are minimal
- **Symptom:** Chat tab rows show only title + preview + time + delete icon. Message count
  and the model used are missing.
- **Root Cause:** `ChatListItem` carries only id/title/preview/updatedAt; the DAO summary
  query doesn't include a message count; `modelId` (already in `ChatSummary`) is dropped in
  the ViewModel mapping.
- **Expected:** Each row shows: last message preview (as today) + relative time + number of
  messages ("N messages") + last used model name + a proper delete icon.

---

## Slice-Based Implementation Plan

---

### Slice 1 — File-level model picker sheet in the chat room
**Files:** `ChatUi.kt`, `ChatViewModel.kt`, `ChatRoomScreen.kt`,
new `feature/chat/components/ModelFilePickerSheet.kt`
**Commit:** `Add file-level model picker sheet to chat room`

**Steps:**
1. In `ChatUi.kt`: extend `ModelChipState.Ready` with `fileName: String?`; change
   `ChatEvent.SelectModel` to carry `modelId: String, fileName: String? = null`.
2. In `ChatViewModel.kt`:
   - Add `downloadedFiles: StateFlow<List<DownloadedModelFile>>` from
     `modelRepository.observeDownloadedFiles()` (filter to `status == DOWNLOADED` if needed).
   - Track `activeFileName` alongside `activeModel`; surface it on the chip
     (`observeEngineState` maps `Ready` using the ViewModel's own `activeFileName`, since
     `ModelManager` doesn't know the file).
   - `selectModel(modelId, fileName?)`: resolve the exact file — if `fileName != null` use
     `modelRepository.getModelFile(modelId, fileName)`, else the first downloaded file;
     build the `File` from `File(model.localPath)` + file name (same pattern as today's
     `resolveModelFile`).
   - On success, store `activeModel` + `activeFileName`; session rebuild flow unchanged.
3. New `ModelFilePickerSheet.kt`:
   - Inputs: models (parents), files (children), active `(modelId, fileName)`,
     `onSelect(modelId, fileName)`, `onDismiss`.
   - Group files by `modelId`; per model: header row (model short name + pipeline tag),
     then one row per file: file name (short), size via `Formatters.formatBytes`,
     active-file checkmark (`CheckCircle`, primary tint).
   - `ModalBottomSheet` with `contentWindowInsets = { WindowInsets(0) }` and the same inset
     strategy as `InferenceSettingsSheet` (bottom-anchored, no `fillMaxHeight` fraction,
     safe-area padding on content).
   - Empty state inside the sheet when there are no downloaded files.
4. In `ChatRoomScreen.kt`:
   - TopAppBar `title` becomes a Column: chat title + `ModelChip` (tapping it opens the
     sheet; chip shows active model/file or "Select model").
   - Replace the `ModelPickerSheet` usage with `ModelFilePickerSheet`, passing parents +
     files + active ids; `onSelect` dispatches `ChatEvent.SelectModel(modelId, fileName)`.
   - The "Pick a model" empty-state "Choose" button opens the same sheet.
   - Legacy `ChatScreen`/`ModelPickerSheet` are untouched (not in the nav graph).
5. Test: model with 2 downloaded files → both appear under the model header; select
   file B → engine loads file B (chip shows its name); active row shows the checkmark;
   switching files mid-chat preserves history (session rebuild).

---

### Slice 2 — Smooth simulated loading progress
**Files:** `ChatViewModel.kt` (primary), `ModelLoadingDialog.kt` (verify only)
**Commit:** `Smooth simulated model-loading progress`

**Steps:**
1. In `selectModel()`, before `loadModel()`, launch a `progressTickerJob` in
   `viewModelScope`:
   - Start fake progress at `0.05f`; every ~150 ms: `fake += (0.9f - fake) * 0.06f` —
     asymptotic, so it never reaches 90% while the real load runs.
   - Derive the stage from thresholds: `< 0.35` → INITIALIZING, otherwise LOADING_WEIGHTS.
   - Update `modelLoadProgress` monotonically.
2. Merge real `onProgress` callbacks as `max(fakeSoFar, realProgress)` so real milestones
   never regress the bar.
3. On success: cancel the ticker → existing 100% + READY + 400 ms hold → dismiss.
4. On error: cancel the ticker → clear progress + error snackbar (existing behavior).
5. Guard: cancel any previous ticker when `selectModel` starts (covers rapid model/file
   switching).
6. `ModelLoadingDialog` needs no functional change — fake progress starts at 5%, so the
   determinate bar kicks in immediately.
7. Test: load any model → progress moves continuously (no stall at 30%); reaches 100% with
   "Ready!"; error path clears the dialog.

---

### Slice 3 — Revamped history list rows
**Files:** `ChatDao.kt`, `Chat.kt` (domain), `ChatRepositoryImpl.kt`, `ChatUi.kt`,
`ChatListViewModel.kt`, `ChatViewModel.kt`, `ChatTabScreen.kt`
(optionally `ChatHistoryDrawer.kt`)
**Commit:** `Revamp chat history rows with model, message count, time, delete`

**Steps:**
1. `ChatDao.observeChatSummaries()`: add
   `(SELECT COUNT(*) FROM messages m WHERE m.chatId = c.chatId) AS messageCount` to the
   projection; add the field to `ChatSummaryRow`.
2. Domain `ChatSummary`: add `messageCount: Int = 0`; map it in `ChatRepositoryImpl`.
3. `ChatListItem`: add `modelId: String? = null` and `messageCount: Int = 0` (defaults
   keep other call sites compiling).
4. Map the new fields in both `ChatListViewModel.toListItem()` and
   `ChatViewModel.observeChats()`.
5. Revamp `ChatTabScreen.ChatSessionRow`:
   - Column: title (`bodyLarge`, 1 line) → preview (`bodySmall`, onSurfaceVariant, up to
     2 lines) → metadata row (`labelSmall`, onSurfaceVariant): relative time •
     "N messages" • model short name (`modelId.substringAfterLast('/')`) with a small
     `Memory` icon.
   - Trailing: delete `IconButton` (`DeleteOutline`, contentDescription "Delete chat",
     48 dp touch target).
   - Keep the `HorizontalDivider` between rows.
6. Optional: apply the same metadata to `ChatHistoryDrawer` rows (it shares `ChatListItem`).
7. Test: chat with 5 messages shows "5 messages"; model name matches the chat's model;
   time is relative; delete removes the row.

---

## Execution Order

```
Slice 1 (file-level picker sheet)  ── both touch selectModel(), do back-to-back
    │
    ▼
Slice 2 (simulated progress)
    │
    ▼
Slice 3 (history rows — fully independent, DB + UI only)
```

Slice 3 is fully independent — it can be done in any order.
Slices 1 and 2 both touch `ChatViewModel.selectModel()` — implement back-to-back.

---

## Verification Checklist

### Slice 1 — File picker
- [ ] Sheet lists every downloaded file grouped by model, with sizes
- [ ] Selecting a file loads exactly that file (chip shows file/model)
- [ ] Active file highlighted; chip in top bar opens the sheet anytime
- [ ] Mid-chat switch preserves history (session rebuild)

### Slice 2 — Smooth progress
- [ ] Progress moves continuously — no stall at 30%, no jump to 100%
- [ ] Reaches 100% with "Ready!", holds ~400 ms, then dismisses
- [ ] Error path clears the dialog; rapid re-selection doesn't leak tickers

### Slice 3 — History rows
- [ ] Row shows preview + relative time + "N messages" + model name
- [ ] Delete icon visible, 48 dp target, removes the chat
- [ ] List stays ordered by most recently active

---

## Theme & Font Rules

All changes must follow existing project conventions:
- All colours from `MaterialTheme.colorScheme` only — no hardcoded hex values.
- All text styles from `MaterialTheme.typography` only.
- Minimum touch target: 48 dp for all interactive elements.
- Support font scale up to 200% — no clipped text.
- Bottom sheets: bottom-anchored (no `fillMaxHeight` fraction), safe-area insets via the
  existing `WindowInsetsHelper` pattern, `navigationBarsPadding` on content.
- Edge-to-edge: `WindowInsets` respected throughout.

---

## Commit Messages

```
Slice 1  — Add file-level model picker sheet to chat room
Slice 2  — Smooth simulated model-loading progress
Slice 3  — Revamp chat history rows with model, message count, time, delete
```

---

**Plan version:** 1.0
**Created:** fresh plan for chat room improvements
**Status:** All slices pending implementation
