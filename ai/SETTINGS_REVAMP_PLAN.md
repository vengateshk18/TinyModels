# Settings Page Revamp Plan

> Goal: Restructure the Settings tab into clear **sections with titles**,
> add **font-size** scaling, a **device-information** section (links to a
> dedicated screen), a **clear-data** action (deletes all chat history),
> and convert the font picker from an inline radio list to a **bottom sheet**.
> All existing features (theme, dynamic color, font family, default backend)
> must remain fully functional — changes are additive and backward-compatible.

---

## 1. Current state (what exists today)

```
SettingsScreen.kt
  └─ AppearanceSection
       ├─ Theme           — SegmentedButtonRow (SYSTEM / LIGHT / DARK)
       ├─ Dynamic color   — SwitchRow
       └─ Font             — inline FontPicker (radio list of 6 FontChoice)
```

- `AppSettings` (DataStore): themeMode, useDynamicColor, fontChoice, defaultBackend
- `SettingsRepository`: setThemeMode, setDynamicColor, setFontChoice, setDefaultBackend
- `SettingsViewModel`: thin wrapper exposing `settings: StateFlow<AppSettings>`
- `DeviceViewModel` + `HomeScreen` exist in `feature/home/` — device info is shown
  on the Home tab but **not** referenced from Settings.
- `ChatDao` has `deleteChat(chatId)` and `deleteMessagesForChat(chatId)` but **no
  clearAll / deleteAll** method.
- `TinyModelsDatabase` is at version 4.
- Font size is not persisted; typography is built with fixed Material defaults.

---

## 2. Target layout

```
SettingsScreen (scrollable Column, sections separated by dividers)

┌───────────────────────────────────┐
│  Appearance                        │  ← Section title (primary color)
│  ─────────────────────────────     │
│  Theme          [System|Light|Dark]│  ← unchanged
│  Dynamic color              [  ◯] │  ← unchanged
│  Font            System    [ ⏷ ]  │  ← row → opens FontBottomSheet
│  Font size    ─────●────   1.0×   │  ← NEW: slider, 0.85×–1.30×
│                                    │
├───────────────────────────────────┤
│  Inference                         │  ← Section title
│  ─────────────────────────────     │
│  Default backend  [Auto|GPU|CPU]   │  ← moved from removed global section
│                                    │
├───────────────────────────────────┤
│  About                             │  ← Section title
│  ─────────────────────────────     │
│  Device information          [ > ] │  ← NEW: navigates to DeviceInfoScreen
│  Clear chat history          [ > ] │  ← NEW: confirmation dialog → deleteAll
│                                    │
└───────────────────────────────────┘
```

### FontBottomSheet (modal)
```
┌───────────────────────────────────┐
│  Choose font                       │
│  ●  System            Default      │
│  ○  Abel              Google Font  │
│  ○  Inter             Google Font  │
│  ○  Roboto            Google Font  │
│  ○  Pacifico          Google Font  │
│  ○  Abril Fatface     Google Font  │
│                  [   Cancel   ]    │
└───────────────────────────────────┘
```

### DeviceInfoScreen (pushed route)
- Reuses the existing `DeviceViewModel` + AI-capability card.
- Full-screen with back button.
- Shows: device name, AI capability card, memory, storage, processor,
  recommended models, browse-models button.

### Clear-data confirmation dialog
- AlertDialog: "Delete all chat history? This cannot be undone."
- Confirm → `ChatRepository.clearAllChats()` → snackbar "Chat history cleared"

---

## 3. Data / schema changes

### AppSettings (DataStore) — add `fontScale`

```kotlin
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val fontChoice: FontChoice = FontChoice.SYSTEM,
    val fontScale: Float = 1.0f,                 // NEW: 0.85–1.30, default 1.0
    val defaultBackend: BackendPreference = BackendPreference.AUTO
)
```

- New DataStore key: `floatPreferencesKey("font_scale")`
- **No Room migration needed** — this is DataStore (Preferences), which is
  schema-less and additive. Existing keys are untouched.
- The theme composable (`TinyModelsTheme`) will receive `fontScale` and apply
  it by scaling all `Typography` sizes via `.copy(fontSize = …)`.

### ChatDao / ChatRepository — add `clearAllChats`

```kotlin
// ChatDao
@Query("DELETE FROM messages")
suspend fun deleteAllMessages()

@Query("DELETE FROM chats")
suspend fun deleteAllChats()

// ChatRepository + ChatRepositoryImpl
suspend fun clearAllChats()
```

- **No DB version bump** — adding a new query method does not change the schema.
  The DB stays at version 4.

### Navigation — add `DEVICE_INFO` route

```kotlin
// Routes.kt
const val DEVICE_INFO = "device_info"
```

- Pushed route in `TinyModelsApp` → `DeviceInfoScreen(onBack = …)`.

---

## 4. Slices (thin vertical, each committed separately)

### Slice S1 — Font scale: AppSettings + Repository + Theme
**Goal:** Persist `fontScale` and apply it to typography.

Files:
- `domain/model/AppSettings.kt` — add `fontScale: Float = 1.0f`
- `domain/repository/SettingsRepository.kt` — add `setFontScale(Float)`
- `data/repository/SettingsRepositoryImpl.kt` — add `FONT_SCALE` key + mapping + setter
- `feature/settings/SettingsViewModel.kt` — add `setFontScale(Float)`
- `core/ui/theme/Type.kt` — `buildTypography(choice, fontScale)` scales every
  `fontSize` by `fontScale`
- `core/ui/theme/Theme.kt` — `TinyModelsTheme(fontScale = 1.0f, …)` passes it through
- `MainActivity.kt` — pass `settings.fontScale` to `TinyModelsTheme`

**Safety:** Additive only; default 1.0f = no visual change.
- **Commit:** `feat(settings): S1 — persist + apply fontScale in theme`

---

### Slice S2 — Font picker bottom sheet
**Goal:** Replace inline radio list with a bottom sheet.

Files:
- `feature/settings/SettingsScreen.kt`:
  - Remove `FontPicker` composable (radio list).
  - In `AppearanceSection`, add a `FontRow` showing current font name + chevron;
    tapping opens `FontBottomSheet`.
  - Add `FontBottomSheet` composable: `ModalBottomSheet` with radio rows for
    each `FontChoice`, each rendered in its own font.

**Safety:** Font selection logic unchanged; only the UI container changes.
- **Commit:** `feat(settings): S2 — font picker bottom sheet`

---

### Slice S3 — Font-size slider in Appearance section
**Goal:** Add the font-size slider below the font row.

Files:
- `feature/settings/SettingsScreen.kt`:
  - In `AppearanceSection`, after the font row, add `SliderRow` bound to
    `settings.fontScale` (range 0.85f–1.30f, steps of 0.05).
  - Label: "Font size"; value text: "%.2f×".format(fontScale).
  - `onValueChange` → `viewModel.setFontScale(it)`.

**Safety:** New row only; existing rows untouched.
- **Commit:** `feat(settings): S3 — font-size slider`

---

### Slice S4 — Inference section (default backend)
**Goal:** Surface the default-backend selector as a titled section.

Files:
- `feature/settings/SettingsScreen.kt`:
  - Add `InferenceSection(settings, viewModel)` with `SectionHeader("Inference")`.
  - SegmentedButtonRow for `BackendPreference.AUTO / GPU / CPU` bound to
    `settings.defaultBackend` → `viewModel.setDefaultBackend(it)`.
  - Add a section divider before it.

**Safety:** This selector already exists in the ViewModel/Repository; it was
removed from the UI in N5. Re-adding it is purely UI.
- **Commit:** `feat(settings): S4 — inference section with default backend`

---

### Slice S5 — Device information screen + section link
**Goal:** Add a pushed `DeviceInfoScreen` and a navigation row in Settings.

Files:
- `feature/navigation/Routes.kt` — add `DEVICE_INFO = "device_info"`.
- `feature/home/DeviceViewModel.kt` — already exists; no changes.
- `feature/home/DeviceInfoScreen.kt` — **new**: full-screen Scaffold with
  TopAppBar (back button) + scrollable Column reusing the same cards as
  `HomeScreen` (AI capability, memory, storage, processor).
- `feature/navigation/TinyModelsApp.kt` — add `composable(Routes.DEVICE_INFO)`.
- `feature/settings/SettingsScreen.kt` — add `AboutSection`:
  - `SectionHeader("About")`
  - `NavigationRow("Device information", subtitle = "See your device's AI capability", onClick = onDeviceInfo)`
  - Wire `onDeviceInfo` param through `SettingsScreen` → `TinyModelsApp`.

**Safety:** New route + screen; no changes to existing screens.
- **Commit:** `feat(settings): S5 — device information screen + settings link`

---

### Slice S6 — Clear chat history
**Goal:** Add a "Clear chat history" row with confirmation dialog.

Files:
- `core/database/ChatDao.kt` — add `deleteAllChats()` + `deleteAllMessages()`.
- `domain/repository/ChatRepository.kt` — add `clearAllChats()`.
- `data/repository/ChatRepositoryImpl.kt` — implement: call both DAO deletes.
- `feature/settings/SettingsViewModel.kt` — add `clearChatHistory()` that calls
  the repository and exposes a `clearResult: StateFlow<String?>` for snackbar.
- `feature/settings/SettingsScreen.kt` — in `AboutSection`:
  - `NavigationRow("Clear chat history", subtitle = "Delete all conversations", onClick = { showClearDialog = true })`
  - `AlertDialog` (confirm / dismiss) → `viewModel.clearChatHistory()`.
  - Snackbar: "Chat history cleared".

**Safety:**
- New DAO methods are `@Query("DELETE …")` — no schema change, no migration.
- The action is destructive but gated behind a confirmation dialog.
- Does NOT delete downloaded models (only `chats` + `messages` tables).
- **Commit:** `feat(settings): S6 — clear chat history with confirmation`

---

### Slice S7 — Final wiring + polish
**Goal:** Connect all sections in the correct order with dividers.

Files:
- `feature/settings/SettingsScreen.kt`:
  - Column order: AppearanceSection → divider → InferenceSection → divider →
    AboutSection.
  - Remove unused `onManageModels` / `onDownloadedModels` params if no longer
    referenced (or keep them inert to avoid breaking nav call site).
  - Ensure `SettingsScreen` signature includes `onDeviceInfo: () -> Unit`.
- `feature/navigation/TinyModelsApp.kt` — pass `onDeviceInfo` to `SettingsScreen`.

- **Commit:** `feat(settings): S7 — wire all sections + cleanup unused params`

---

## 5. Migration / safety summary

| Change | Migration needed? | Why |
|--------|-----------------|-----|
| `fontScale` in DataStore | **No** | Preferences DataStore is schema-less; new key is additive |
| `clearAllChats` DAO methods | **No** | New `@Query("DELETE …")` methods; no schema change |
| `DEVICE_INFO` route | **No** | New nav route; no impact on existing routes |
| Font picker → bottom sheet | **No** | Pure UI refactor; same `setFontChoice` call |
| Font-size slider | **No** | New UI row; same `setFontScale` call |
| DB version | **Stays at 4** | No schema changes at all |

**Key safety principles:**
- Every slice is independently buildable and committable.
- No existing setting is removed or renamed.
- Defaults preserve current behavior (`fontScale = 1.0f`).
- Clear-data only touches `chats` + `messages` — **not** `downloaded_models`.
- Confirmation dialog prevents accidental data loss.
