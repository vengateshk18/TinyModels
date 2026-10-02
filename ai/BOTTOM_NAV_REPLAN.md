# App Restructure Plan — Bottom Navigation + Per-Session Inference

> Goal: Restructure the app from a single chat-start-destination into a
> **bottom-navigation** shell with 4 tabs (Home, Chat, Models, Settings), move
> inference settings from global to **per-chat-session** (stored in Room),
> refine the font picker into a **bottom sheet**, and complete the
> **device information** plan.

---

## 1. Current architecture (what exists today)

```
MainActivity
 └─ TinyNavHost (start = chat)
     ├─ ChatScreen          ← drawer + chat + input in one screen
     ├─ ModelListScreen
     ├─ ModelDetailsScreen
     ├─ DownloadedModelsScreen
     └─ SettingsScreen      ← global: theme, font, dynamic color, backend,
                               sampler, maxContextTokens, systemInstruction
```

- Chat + history live in a single screen via ModalNavigationDrawer.
- Inference settings (backend, temperature, topK, topP, maxContextTokens,
  systemInstruction) are global in DataStore (AppSettings).
- ChatEntity stores only modelId, title, timestamps.
- ConversationSession is rebuilt from global settings each time.
- Font picker is a radio-button list inside SettingsScreen.

---

## 2. Target architecture

```
MainActivity
 └─ TinyModelsApp (Scaffold + NavigationBar)
     ├─ Home tab          — device info + AI capability + quick actions
     ├─ Chat tab         — session list + new-session; tapping → ChatRoomScreen
     │   └─ ChatRoomScreen — full chat UI (messages, input, cancel/regen/edit)
     ├─ Models tab       — tabs: Browse | Downloaded
     └─ Settings tab     — appearance (theme, dynamic color, font) + about
```

### Key changes

| Area | Before | After |
|------|--------|-------|
| Navigation | Single NavHost, chat start | Scaffold + NavigationBar (4 tabs) |
| Chat history | ModalNavigationDrawer inside ChatScreen | Dedicated Chat tab with session list |
| Chat screen | ChatScreen = drawer + messages + input | ChatRoomScreen = messages + input only |
| Inference settings | Global in DataStore (AppSettings) | Per-session in Room (ChatEntity columns) |
| Font picker | Radio list in SettingsScreen | ModalBottomSheet + current selection shown |
| Device info | None | Home tab with AI capability + device specs |

---

## 3. Slices (thin vertical, each committed separately)

### Slice N1 — Bottom navigation shell

**Goal:** Scaffold + NavigationBar with 4 tabs; each tab shows a placeholder.

- Create `feature/navigation/TinyModelsApp.kt`:
  - Scaffold(bottomBar = { NavigationBar { ... } }).
  - 4 items: Home, Chat, Models, Settings (Icons: Home, Chat, Download, Settings).
  - Track selected tab with rememberSaveable.
- Create placeholder composables: HomeScreen(), ChatTabScreen(),
  ModelsTabScreen(), SettingsTabScreen().
- Wire MainActivity to render TinyModelsApp() instead of TinyNavHost().
- ModelDetailsScreen stays as a full-screen push (still uses NavController).
  - Keep TinyNavHost for pushed routes (model details) via a nested NavHost
    or a shared navController.
- **Commit:** feat(nav): N1 — bottom navigation shell with 4 tabs

### Slice N2 — Chat tab: session list

**Goal:** Chat tab shows a list of chat sessions + FAB / New chat button.

- ChatTabScreen: shows LazyColumn of ChatListItem (title, preview, time).
  - New chat button at top or FAB.
  - Tapping a session → navigate to ChatRoomScreen(chatId).
  - Swipe-to-delete or delete icon on each row.
  - Empty state: No conversations yet — start a new chat.
- Move session-list observation from ChatViewModel to a new
  ChatListViewModel (or reuse the observeChatSummaries flow).
- Remove ModalNavigationDrawer from the old ChatScreen.
- **Commit:** feat(chat): N2 — chat tab with session list + new-chat

### Slice N3 — ChatRoomScreen: dedicated chat UI

**Goal:** Tapping a session opens a new screen with the full chat experience.

- Create feature/chat/ChatRoomScreen.kt:
  - Accept chatId: String.
  - Shows TopAppBar with chat title + back button.
  - Shows MessageList + ChatInputBar (reused from existing components).
  - Shows session-specific inference settings icon (→ bottom sheet, Slice N6).
- ChatViewModel becomes scoped to a single chatId (via SavedStateHandle).
  - onEvent dispatches operate on that chatId.
  - Remove drawer logic from ChatViewModel.
- Navigation: ChatRoomScreen(chatId) route added to TinyNavHost.
- New chat: ChatTabScreen creates a chat then navigates to
  ChatRoomScreen(newChatId).
- **Commit:** feat(chat): N3 — ChatRoomScreen for per-session chat

### Slice N4 — Per-session inference settings in Room

**Goal:** Each chat session stores its own inference settings.

- Room schema (DB v4, destructive fallback) — add columns to ChatEntity:
  - backend: String = "AUTO"
  - temperature: Double = 0.7
  - topK: Int = 40
  - topP: Double = 0.95
  - maxContextTokens: Int = 2048
  - systemInstruction: String = "You are a helpful assistant."
- ChatDao: add update query or use upsertChat (REPLACE strategy in place).
- ChatRepository: add updateInferenceSettings(chatId, settings).
- Chat domain model: add inferenceSettings: InferenceSettings field.
  - InferenceSettings(backend, temperature, topK, topP, maxContextTokens,
    systemInstruction) with defaults.
- ChatViewModel.rebuildSession() reads from the chat's inference settings
  (not global settingsRepository.settings).
- ConversationSession.create() uses per-session sampler + maxContextTokens.
- **Commit:** feat(chat): N4 — per-session inference settings in Room

### Slice N5 — Remove inference settings from global Settings

**Goal:** Settings screen no longer has inference sliders; those are per-session.

- Remove InferenceSection from SettingsScreen.
- Keep backend as a global default for new sessions (used when creating
  a new chat); remove temperature/topK/topP/maxContextTokens/systemInstruction
  from AppSettings.
- SettingsScreen sections become:
  1. Appearance (theme, dynamic color, font picker → bottom sheet)
  2. About / device info link
  3. (Remove: Inference, Models management — those are in their own tabs now)
- **Commit:** feat(settings): N5 — remove global inference settings; per-session now

### Slice N6 — Inference settings bottom sheet (per session)

**Goal:** A bottom sheet in ChatRoomScreen to edit the current session's inference.

- Create feature/chat/components/InferenceSettingsSheet.kt:
  - ModalBottomSheet with:
    - Model selector (dropdown of downloaded models)
    - Backend (AUTO / GPU / CPU segmented)
    - Temperature slider
    - Top K slider
    - Top P slider
    - Context window (1K / 2K / 4K / 8K segmented)
    - System instruction text field
    - Save button → ChatEvent.UpdateInferenceSettings
- ChatRoomScreen: add an icon (sliders / tune) in the TopAppBar that opens
  the sheet.
- ChatEvent.UpdateInferenceSettings(settings) → ChatViewModel updates the
  chat in Room + rebuilds ConversationSession with new sampler.
- **Commit:** feat(chat): N6 — per-session inference settings bottom sheet

### Slice N7 — Font picker bottom sheet

**Goal:** Font selection moves from a radio list to a bottom sheet.

- Create feature/settings/FontPickerSheet.kt:
  - ModalBottomSheet listing each FontChoice.
  - Each row shows the font name rendered in that font (live preview).
  - Selected item has a check icon.
  - Tapping a font calls onSelect(choice) and dismisses.
- SettingsScreen Appearance section:
  - Shows current font name (e.g., Font: Abel) as a tappable row.
  - Tapping opens FontPickerSheet.
- Remove the old inline FontPicker radio list.
- **Commit:** feat(settings): N7 — font picker as modal bottom sheet

### Slice N8 — Home tab: device info + AI capability

**Goal:** Home tab shows device specs + AI capability + recommended models.

- Create feature/home/HomeScreen.kt + HomeViewModel.kt.
- See ai/device_information_plan.md (sections 1-25) for the full design.
- Components:
  1. DeviceHeader — device name + Ready for on-device AI badge.
  2. AICapabilityCard — Excellent / Good / Limited + recommended model size.
  3. MemoryCard — total RAM, available RAM, AI budget bar.
  4. StorageCard — free storage, recommended minimum.
  5. ProcessorCard — CPU cores, architecture, GPU/NPU.
  6. RecommendedModelsCard — green/yellow/red per model.
  7. TechnicalDetailsCard — expandable (ABI, API level, SoC).
- Logic layers:
  - DeviceCapabilityRepository → collects from Android APIs.
  - ModelCompatibilityEngine → maps device + model metadata → green/yellow/red.
- Quick actions: Browse models → Models tab, Start chat → Chat tab.
- **Commit:** feat(home): N8 — device info + AI capability screen

### Slice N9 — Models tab: Browse + Downloaded

**Goal:** Models tab has two sub-tabs: Browse (HuggingFace catalog) and Downloaded.

- ModelsTabScreen:
  - TabRow with 2 tabs: Browse, Downloaded.
  - Browse → existing ModelListScreen content (minus the back button).
  - Downloaded → existing DownloadedModelsScreen content (minus back button).
  - Tapping a model → navigate to ModelDetailsScreen(modelId).
- Remove the old Browse models / Downloaded models navigation rows from
  Settings (they are tabs now).
- **Commit:** feat(models): N9 — Models tab with Browse + Downloaded sub-tabs

---

## 4. Data model changes

### ChatEntity (Room, DB v4)

```kotlin
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val title: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isArchived: Boolean = false,
    // Per-session inference settings:
    val backend: String = "AUTO",
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.95,
    val maxContextTokens: Int = 2048,
    val systemInstruction: String = "You are a helpful assistant."
)
```

### Chat (domain)

```kotlin
data class Chat(
    val id: String,
    val title: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isArchived: Boolean = false,
    val inferenceSettings: InferenceSettings = InferenceSettings()
)

data class InferenceSettings(
    val backend: BackendPreference = BackendPreference.AUTO,
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.95,
    val maxContextTokens: Int = 2048,
    val systemInstruction: String = "You are a helpful assistant."
)
```

### AppSettings (global, simplified)

```kotlin
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val fontChoice: FontChoice = FontChoice.SYSTEM,
    val defaultBackend: BackendPreference = BackendPreference.AUTO
    // Removed: sampler, maxContextTokens, systemInstruction (now per-session)
)
```

---

## 5. Navigation structure

```
TinyModelsApp (Scaffold + NavigationBar)
├─ Home tab      → HomeScreen()
├─ Chat tab      → ChatTabScreen()
│                 └─ ChatRoomScreen(chatId)     [pushed, full-screen]
├─ Models tab    → ModelsTabScreen()
│                 ├─ Browse sub-tab → ModelListScreen content
│                 └─ Downloaded sub-tab → DownloadedModelsScreen content
│                 └─ ModelDetailsScreen(modelId)  [pushed, full-screen]
└─ Settings tab  → SettingsScreen()
                  └─ FontPickerSheet              [modal bottom sheet]
```

- NavController is shared: bottom bar tabs use it to switch, and pushed
  routes (ChatRoom, ModelDetails) use navigate() / popBackStack().
- Tapping a bottom nav item navigates to the tab route with
  launchSingleTop = true + restoreState = true.

---

## 6. Font picker bottom sheet design

```
SettingsScreen
├─ Appearance
│  ├─ Theme: [System] [Light] [Dark]
│  ├─ Dynamic color: [switch]
│  └─ Font: Abel  >          ← tappable row, shows current selection
│
└─ (tap) → FontPickerSheet (ModalBottomSheet)
            ├─ ● System        Default
            ├─ ○ Abel          Google Font
            ├─ ○ Inter         Google Font
            ├─ ○ Roboto        Google Font
            ├─ ○ Pacifico      Google Font
            ├─ ○ Abril Fatface Google Font
            └─ (tap) → onSelect(choice) → dismiss
```

Each font name in the sheet is rendered in that font as a live preview.

---

## 7. Per-session inference flow

```
ChatTabScreen
├─ [New chat] → createChat(modelId, defaultBackend) → ChatRoomScreen(newId)
└─ [Tap session] → ChatRoomScreen(chatId)
                   ├─ TopAppBar: title + [⚙ inference] icon
                   │   └─ (tap) → InferenceSettingsSheet
                   │       ├─ Model: [dropdown of downloaded models]
                   │       ├─ Backend: AUTO | GPU | CPU
                   │       ├─ Temperature: slider 0.70
                   │       ├─ Top K: slider 40
                   │       ├─ Top P: slider 0.95
                   │       ├─ Context: 1K | 2K | 4K | 8K
                   │       ├─ System instruction: [text field]
                   │       └─ [Save] → ChatEvent.UpdateInferenceSettings
                   ├─ MessageList
                   └─ ChatInputBar
```

When inference settings change:
1. ChatViewModel updates ChatEntity in Room.
2. ConversationSession is rebuilt with the new sampler + maxContextTokens.
3. If the model changed, ModelManager.loadModel() is called first.
4. History is restored from Room messages (sliding-window trimmed).

---

## 8. Implementation order + commit messages

| Slice | Commit message |
|-------|---------------|
| N1 | feat(nav): N1 — bottom navigation shell with 4 tabs |
| N2 | feat(chat): N2 — chat tab with session list + new-chat |
| N3 | feat(chat): N3 — ChatRoomScreen for per-session chat |
| N4 | feat(chat): N4 — per-session inference settings in Room |
| N5 | feat(settings): N5 — remove global inference; per-session now |
| N6 | feat(chat): N6 — per-session inference settings bottom sheet |
| N7 | feat(settings): N7 — font picker as modal bottom sheet |
| N8 | feat(home): N8 — device info + AI capability screen |
| N9 | feat(models): N9 — Models tab with Browse + Downloaded sub-tabs |

---

## 9. Migration notes

- DB v3 to v4: Additive columns on chats table (backend, temperature,
  topK, topP, maxContextTokens, systemInstruction). Since we use
  fallbackToDestructiveMigration(dropAllTables = true) (pre-release app),
  existing chats will be wiped — acceptable.
- AppSettings DataStore: Remove keys for sampler/temperature/topK/topP/
  maxContextTokens/systemInstruction. Keep defaultBackend. Old keys in
  DataStore are ignored on read (safe).
- ChatViewModel: Currently reads global settings for session rebuild —
  switch to reading from ChatEntity.inferenceSettings.

---

## 10. Honest limitations / next steps

- N8 (device info) is the largest slice — it requires Android system APIs
  (ActivityManager.MemoryInfo, StatFs, Build, PackageManager) and a
  compatibility engine. Start with the 6 MVP items from
  device_information_plan.md section 25.
- Model metadata (parameter count, quantization, estimated RAM) is not yet
  in the DownloadedModel entity — it may need to be parsed from the
  HuggingFace API or model filename. This is a prerequisite for the
  compatibility engine.
- Home tab quick actions depend on the bottom nav being wired (N1).
