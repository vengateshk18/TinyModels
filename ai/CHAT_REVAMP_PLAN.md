# Chat Feature Revamp Plan

> **Goal:** Fix model loading progress, prevent unnecessary session creation, ensure proper model cleanup, and add inference session settings — delivered as **thin vertical slices**, each committed separately.  
> **Format:** Code-free steps only. No code snippets. Each slice is independently implementable and commit-ready.

---

## Problem Summary

### Issue 1: Model Loading Progress
- **Symptom:** When entering a chat, the model loads silently with no feedback. User sees a blank screen or frozen UI.
- **Root Cause:** `ChatViewModel.loadModel()` blocks the main thread or runs without progress callbacks. No `LoadingDialog` or progress state in UI.

### Issue 2: Unnecessary Session Creation
- **Symptom:** Every time entering model detail (even without chatting), a new empty chat session is created in the database.
- **Root Cause:** Session is created on model detail screen enter, not when user actually starts chatting.

### Issue 3: Model Not Unloaded on Exit
- **Symptom:** After exiting chat, the model stays loaded in RAM, causing memory bloat.
- **Root Cause:** `ModelManager.unloadModel()` is not called in `ChatViewModel.onCleared()` or lifecycle callbacks.

### Issue 4: No Inference Settings UI
- **Symptom:** No way to change temperature, max tokens, or context length during chat session.
- **Root Cause:** Settings are global only. No per-model or per-session UI to adjust inference parameters.

---

## Slice-Based Implementation Plan

### Slice 1: Model Loading Progress Indicator
**Files:** `ChatScreen.kt`, `ChatViewModel.kt`, `InferenceError.kt`  
**Commit:** `Implement model loading progress indicator`

**Steps:**
1. Add `modelLoadProgress` (Float) and `isLoadingModel` (Boolean) to `ChatUiState`.
2. Update `ModelManager.loadModel()` to accept an optional `onProgress: (Float) -> Unit` callback.
3. In `ChatViewModel.loadModel()`, call `onProgress` with intermediate progress values (0.0 → 1.0).
4. Create `LoadingDialog` composable with:
   - CircularProgressIndicator
   - Progress text ("Loading model... 45%")
   - Optional: Token count or model size info
5. In `ChatScreen`, show `LoadingDialog` when `isLoadingModel` is true.
6. Wire progress updates: `onProgress { progress -> _uiState.update { it.copy(modelLoadProgress = progress) } }`
7. Hide dialog when `isLoadingModel` becomes false.
8. Test with a large model (>1GB) to verify progress updates are visible.
9. Verify dialog is dismissable only by model load completion (not back button).

---

### Slice 2: Fix Unnecessary Session Creation
**Files:** `ChatViewModel.kt`, `SessionManager.kt`, `Model.kt`  
**Commit:** `Prevent automatic chat session creation`

**Steps:**
1. Add `shouldCreateSession` flag to `ChatUiState` (default: false).
2. In `ChatViewModel`, remove automatic session creation on `onModelSelected()`.
3. Add `startChat()` method in `ChatViewModel` that:
   - Checks if a session exists for the model
   - Creates session only if none exists AND user clicks "Start Chat"
   - Sets `shouldCreateSession = true` on model selection
4. Update `ModelDetailsScreen` to show "Start Chat" button only when `shouldCreateSession` is true.
5. In `SessionManager`, add `createSessionIfNotExists(modelId)` that returns existing or creates new.
6. Ensure `Model.kt` does not auto-create sessions in `ModelDetails`.
7. Test: Navigate to model detail → Verify no session created → Click "Start Chat" → Verify session created.
8. Test: Navigate away and back → Verify same session reused, not duplicated.

---

### Slice 3: Proper Model Cleanup on Exit
**Files:** `ChatViewModel.kt`, `ModelManager.kt`  
**Commit:** `Unload model when exiting chat session`

**Steps:**
1. In `ChatViewModel.onCleared()`, call `modelManager.unloadModel(currentModelId)`.
2. Add `unloadModel(modelId: String?)` method in `ModelManager` that:
   - Calls `engine.unload()` if engine is loaded
   - Clears cached model references
   - Logs unload action
3. In `ChatViewModel`, add `onChatExit()` callback that:
   - Triggers model unload
   - Resets `isLoadingModel`, `modelLoadProgress`, and session state
4. Update `ChatScreen` to call `viewModel.onChatExit()` when user navigates away (using lifecycle).
5. Add `ModelUnloadListener` interface in `ModelManager` to notify UI of unload completion.
6. In `ChatScreen`, show brief toast/snackbar: "Model unloaded to free memory".
7. Test: Open chat → Verify model loads → Exit chat → Check RAM usage drops → Reopen chat → Verify fresh load.
8. Verify no memory leaks: Run profiler before/after chat sessions.

---

### Slice 4: Inference Session Settings Dialog
**Files:** `InferenceSettingsDialog.kt`, `ChatScreen.kt`, `ChatViewModel.kt`, `SettingsRepository.kt`  
**Commit:** `Add inference settings configuration dialog`

**Steps:**
1. Create `InferenceSettingsDialog.kt` composable with:
   - Temperature slider (0.0–2.0, default 0.7)
   - Max tokens input (spinner or text field, default 512)
   - Context length dropdown (256, 512, 1024, 2048)
   - Reset to defaults button
   - Save and Cancel buttons
2. Add `showInferenceSettings` Boolean to `ChatUiState`.
3. In `ChatScreen`, add gear icon button next to model name to open `InferenceSettingsDialog`.
4. In `ChatViewModel`, add `openInferenceSettings()` and `saveInferenceSettings(temp, maxTokens, contextLen)` methods.
5. Persist settings per model: Add `inferenceSettings` map in `SettingsRepository` (`Map<String, InferenceSettings>`).
6. `InferenceSettings` data class:
   ```kotlin
   data class InferenceSettings(
       val temperature: Float = 0.7f,
       val maxTokens: Int = 512,
       val contextLength: Int = 1024
   )
   ```
7. When loading model, pass `inferenceSettings` to `ModelManager.loadModel()`.
8. Test: Open settings → Change values → Save → Verify model reloaded with new settings.
9. Test: Exit and re-enter chat → Verify settings persist.
10. Ensure dialog follows Material3 theme and font scale.

---

### Slice 5: Settings Persistence Per Model
**Files:** `SettingsRepository.kt`, `SettingsDataSource.kt`, `Model.kt`  
**Commit:** `Persist inference settings per model`

**Steps:**
1. In `SettingsDataSource`, add table `model_inference_settings` with columns:
   - `model_id` (TEXT, PRIMARY KEY)
   - `temperature` (REAL)
   - `max_tokens` (INTEGER)
   - `context_length` (INTEGER)
   - `updated_at` (INTEGER)
2. Add DAO methods:
   - `getInferenceSettings(modelId: String): InferenceSettings?`
   - `saveInferenceSettings(modelId: String, settings: InferenceSettings): Unit`
   - `resetInferenceSettings(modelId: String): Unit`
3. In `SettingsRepository`, update `getInferenceSettings()` to fetch from DB, fallback to defaults.
4. Add `Model.inferenceSettings: InferenceSettings` computed property that merges default + saved.
5. Migrate existing `Settings` table to new `model_inference_settings` table.
6. Test: Save settings for Model A → Verify saved in DB → Load Model A → Verify settings applied.
7. Test: Save settings for Model B → Verify independent from Model A.
8. Test: Reset settings → Verify defaults restored.

---

### Slice 6: Testing & Polish
**Files:** `ChatViewModelTest.kt`, `ModelManagerTest.kt`, `InferenceSettingsDialogTest.kt`  
**Commit:** `Add comprehensive test coverage for chat features`

**Steps:**
1. In `ChatViewModelTest`:
   - Test `loadModel()` shows progress and completes
   - Test `startChat()` creates session only when needed
   - Test `onCleared()` unloads model
   - Test `saveInferenceSettings()` persists correctly
2. In `ModelManagerTest`:
   - Test `loadModel()` with progress callback
   - Test `unloadModel()` releases resources
   - Test settings are applied correctly
3. Create `InferenceSettingsDialogTest`:
   - Test UI renders correctly
   - Test save/cancel actions
   - Test persistence round-trip
4. Add UI tests (Espresso) for:
   - Progress dialog visibility
   - Session creation flow
   - Settings dialog interaction
5. Run all tests: `./gradlew :app:testDebugUnitTest`
6. Run UI tests on emulator: `./gradlew :app:connectedAndroidTest`
7. Verify all tests pass before merging.
8. Update `README.md` with new features and usage.

---

## Execution Order & Dependencies

```
Slice 1 (Progress) ──► Slice 2 (Session Fix) ──► Slice 3 (Cleanup)
                    └──► Slice 4 (Settings) ──► Slice 5 (Persistence)
                                              └──► Slice 6 (Testing)
```

- **Slice 1** is foundational (fixes UX blocker).
- **Slice 2** and **Slice 3** are independent but related to lifecycle.
- **Slice 4** depends on **Slice 1** (settings dialog shown during load).
- **Slice 5** depends on **Slice 4** (persists what Slice 4 creates).
- **Slice 6** is last (tests all slices).

---

## Verification Checklist

After each slice:

### Slice 1
- [ ] Progress dialog appears during model load
- [ ] Progress updates smoothly (0% → 100%)
- [ ] Dialog dismisses only on completion
- [ ] No UI freeze during load

### Slice 2
- [ ] No session created on model detail enter
- [ ] Session created only when "Start Chat" clicked
- [ ] Same session reused on re-entry

### Slice 3
- [ ] Model unloads on chat exit
- [ ] RAM usage drops after unload
- [ ] Toast shown on unload
- [ ] No memory leaks in profiler

### Slice 4
- [ ] Settings dialog opens from gear icon
- [ ] All controls work (slider, input, dropdown)
- [ ] Save applies new settings immediately
- [ ] Cancel discards changes

### Slice 5
- [ ] Settings persist across app restarts
- [ ] Per-model settings are independent
- [ ] Reset restores defaults

### Slice 6
- [ ] All unit tests pass
- [ ] All UI tests pass
- [ ] No regressions in existing features

---

## Theme & Font Guidelines

All UI changes must follow:
- **Material3 theme** (use `MaterialTheme.colorScheme`)
- **Typography** (use `MaterialTheme.typography`)
- **Font scale support** (all text uses `fontSize` from `Typography`)
- **Edge-to-edge** (use `statusBarsPadding`, `navigationBarsPadding`, `imePadding`)
- **Accessibility** (minimum touch target 48dp, contrast ≥ 4.5:1)

---

## Risk Analysis

| Risk | Mitigation |
|------|------------|
| Progress callback blocks main thread | Run progress updates on `Dispatchers.Default` and `collectAsState()` on main |
| Session creation race condition | Use `mutex` in `SessionManager` to serialize session creation |
| Model unload crashes | Wrap `unloadModel()` in try/catch, log errors |
| Settings migration data loss | Use additive migration (new table), keep old settings as fallback |
| Dialog too large on small screens | Make `InferenceSettingsDialog` scrollable and use `minWidth(320.dp)` |

---

## Commit Messages (Ready to Use)

```bash
# Slice 1
git commit -m "Implement model loading progress indicator"

# Slice 2
git commit -m "Prevent automatic chat session creation"

# Slice 3
git commit -m "Unload model when exiting chat session"

# Slice 4
git commit -m "Add inference settings configuration dialog"

# Slice 5
git commit -m "Persist inference settings per model"

# Slice 6
git commit -m "Add comprehensive test coverage for chat features"
```

---

## Next Steps

1. Review this plan with the team (optional).
2. Start with **Slice 1** (model loading progress).
3. Implement each slice in order, committing after each.
4. Run tests after every slice.
5. Merge to `main` only after all slices are complete and tested.

---

**Plan Created:** 2024-01-20  
**Version:** 1.0  
**Author:** AI Assistant  
**Status:** Ready for Implementation
