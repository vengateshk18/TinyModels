# TityModels Revamp Plan

> **Goal:** Transform TityModels from a prototype into a production-grade on-device LLM chat app with clean architecture, rigorous memory management, persistent chat history, context-window control, and a modern Compose UI.

---

## 1. Current State Analysis

### What the app does today
- Browses LiteRT-LM models from HuggingFace (`litert-community` author, sorted by downloads).
- Downloads `.litertlm` model files via `ModelDownloadWorker` (WorkManager + foreground notification).
- Loads one model at a time into a `com.google.ai.edge.litertlm.Engine` (CPU backend only).
- Provides a single in-memory chat session; history is lost on model switch, tab switch, or process death.

### Critical issues found (must fix)

| # | Issue | Root cause | Impact |
|---|-------|-----------|--------|
| 1 | **Streaming text duplicated** | `ChatViewModel.appendToAssistantMessage` concatenates chunks, but `sendMessageAsync` emits **cumulative** text | Garbled/duplicated assistant replies |
| 2 | **Engine tied to ViewModel lifecycle** | `ChatEngineRepository` created in `ChatViewModel`, closed in `onCleared()` | 3–8s model reload on every config change / tab switch; leak if ViewModel not cleared |
| 3 | **No memory guardrails** | No RAM check before `engine.initialize()`, no `maxNumTokens` cap, CPU-only backend | OOM kills on 6GB devices; slow inference |
| 4 | **Unbounded context** | Single `Conversation` grows forever; no KV-cache limit, no trimming | Context overflow, battery drain, slowdown |
| 5 | **No chat persistence** | Messages held in `MutableStateFlow<List<ChatMessage>>` | All history lost on process death |
| 6 | **Multiple Activities + manual DI** | 4 Activities, `Injection` object, DB built in 2 places | Fragmented navigation, inconsistent singletons, untestable |
| 7 | **Scattered UI state** | 7 separate `StateFlow`s in `ChatViewModel`; logic in Composables | Hard to reason about state, no previewability |
| 8 | **Broad error swallowing** | `catch (e: Exception)` everywhere | OOM/native errors surface as generic "load failed" |
| 9 | **Settings is a dead tab** | Hardcoded blue/black text, only 2 links | No theme, sampler, or backend configuration |

---

## 2. Target Architecture (Clean Architecture + MVVM + MVI)

### Layer diagram

```
┌─────────────────────────────────────────────────────────────┐
│  UI Layer (Compose)                                         │
│  feature/chat, feature/models, feature/settings, theme      │
│  • Stateless composables  • ViewModels expose single UiState│
├─────────────────────────────────────────────────────────────┤
│  Domain Layer (pure Kotlin, no Android deps)                │
│  • Use cases (LoadModel, SendMessage, ObserveHistory…)      │
│  • Repository interfaces  • Domain models                   │
├─────────────────────────────────────────────────────────────┤
│  Data Layer                                                 │
│  • inference/  (LiteRT-LM Engine wrapper — ModelManager)    │
│  • db/         (Room: chats, messages, downloaded models)   │
│  • network/    (HuggingFace API, download worker)           │
│  • repository/ (interface implementations)                  │
├─────────────────────────────────────────────────────────────┤
│  DI Layer (Hilt)                                            │
└─────────────────────────────────────────────────────────────┘
```

### New package structure

```
com.example.titymodels
├── TityModelsApp.kt               // @HiltAndroidApp, DI init, StrictMode (debug)
├── MainActivity.kt                // Single activity, NavHost
├── core
│   ├── common
│   │   ├── Result.kt              // Sealed Success/Error (replace utils/Result)
│   │   ├── MemoryUtils.kt         // Available RAM checks, low-memory callback
│   │   └── DispatcherProvider.kt  // Injectable coroutine dispatchers
│   ├── database
│   │   ├── TityModelsDatabase.kt  // v2: + ChatEntity, MessageEntity
│   │   ├── DownloadedModelDao.kt  // moved from models/local
│   │   ├── ChatDao.kt
│   │   └── entities/
│   ├── inference                  // ◄── LLM memory & context management
│   │   ├── ModelManager.kt        // Singleton engine lifecycle owner
│   │   ├── ModelLoadPolicy.kt     // RAM checks, backend selection, token caps
│   │   ├── ConversationSession.kt // Per-chat conversation + context trimming
│   │   └── InferenceException.kt  // OOM, ModelMissing, BackendUnavailable…
│   ├── network
│   │   ├── HuggingFaceApi.kt      // OkHttp/Retrofit interface
│   │   └── dto/
│   └── ui
│       ├── theme/                 // Dynamic color, dark/light, typography
│       └── components/            // Shared composables (ErrorState, LoadingState…)
├── feature
│   ├── chat
│   │   ├── ChatViewModel.kt       // MVI: single ChatUiState + ChatEvent
│   │   ├── ChatScreen.kt          // Stateless screen
│   │   ├── components/            // MessageBubble, ChatInputBar, ModelChip…
│   │   └── model/                 // UiChatMessage, ChatUiState
│   ├── models
│   │   ├── ModelListViewModel.kt
│   │   ├── ModelDetailsViewModel.kt
│   │   ├── DownloadedModelsViewModel.kt
│   │   └── screens/
│   ├── settings
│   │   ├── SettingsViewModel.kt
│   │   └── SettingsScreen.kt
│   └── navigation
│       ├── TityNavHost.kt
│       └── Routes.kt
├── data
│   ├── repository
│   │   ├── ChatRepositoryImpl.kt
│   │   ├── ModelRepositoryImpl.kt
│   │   └── SettingsRepositoryImpl.kt  // DataStore
│   └── worker
│       └── ModelDownloadWorker.kt     // Injected via Hilt WorkerFactory
├── domain
│   ├── model/                     // Chat, Message, ModelInfo, SamplerSettings
│   ├── repository/                // Interfaces
│   └── usecase/
│       ├── chat/  (LoadModelUseCase, SendMessageUseCase, ObserveChatHistory…)
│       ├── model/ (ListModelsUseCase, DownloadModelUseCase, DeleteModelUseCase…)
│       └── settings/ (GetSettingsUseCase, UpdateSettingsUseCase…)
└── di
    ├── AppModule.kt
    ├── DatabaseModule.kt
    ├── InferenceModule.kt         // @Singleton ModelManager
    └── NetworkModule.kt
```

---

## 3. Memory Management Strategy (core of the revamp)

### 3.1 Singleton `ModelManager`

A process-wide `@Singleton` (provided by Hilt) that **owns** the `Engine` and active `Conversation`, independent of any ViewModel:

```
class ModelManager @Inject constructor(
    @ApplicationContext context: Context,
    dispatchers: DispatcherProvider
) {
    private var engine: Engine? = null
    private var sessions = mutableMapOf<ChatId, ConversationSession>()

    val loadedModel: StateFlow<LoadedModel?>      // modelId + backend + loadTime
    val memoryState: StateFlow<MemoryState>       // FREE / LOADING / READY / LOW_MEMORY

    suspend fun loadModel(modelId: String, policy: ModelLoadPolicy): Result<LoadedModel>
    suspend fun unloadModel()
    fun getSession(chatId: ChatId): ConversationSession
    fun releaseSession(chatId: ChatId)
    fun onTrimMemory(level: Int)                 // from ComponentCallbacks2
}
```

**Key behaviors:**
- Only **one engine instance** exists at any time. Loading a new model closes the old engine **after** the new one initializes successfully (graceful swap).
- `unloadModel()` releases native memory immediately (calls `engine.close()`).
- Registers `ComponentCallbacks2` on the Application: on `TRIM_MEMORY_UI_HIDDEN` / `TRIM_MEMORY_MODERATE`, proactively unloads if no generation is active.
- Exposes `memoryState` so the UI can show "Low memory — model unloaded".

### 3.2 Pre-load memory guard (`ModelLoadPolicy`)

Before `engine.initialize()`:

```
data class ModelLoadPolicy(
    val requiredFreeRamBytes: Long,     // model file size × 1.3 heuristic
    val preferredBackend: Backend,      // GPU → CPU fallback
    val maxNumTokens: Int,              // KV-cache cap, default 2048
    val cacheDir: File
)
```

- Query `ActivityManager.MemoryInfo.availableMem`. If below threshold → `Result.Error(OutOfMemory)` with a user-facing message ("Close other apps and retry").
- Backend fallback chain: try `Backend.GPU()` → on `BackendUnavailable` catch, retry `Backend.CPU()`.
- `maxNumTokens` caps the KV cache so long chats cannot exhaust RAM.

### 3.3 Context-window management (`ConversationSession`)

Each chat session wraps one `Conversation`:

```
class ConversationSession(
    val chatId: ChatId,
    private val conversation: Conversation,
    private val maxContextTokens: Int
) {
    val tokenUsage: StateFlow<Int>      // estimated tokens in context
    val isContextFull: StateFlow<Boolean>

    suspend fun send(text: String): Flow<String>
    suspend fun trimHistory(strategy: TrimStrategy)  // SLIDING_WINDOW / SUMMARIZE
    fun reset()                                       // clear conversation state
}
```

- Estimate tokens ≈ `chars / 4`. Track cumulative usage.
- When usage exceeds 80% of `maxNumTokens`, auto-trim oldest user/assistant pairs (sliding window) and notify the UI to show a "Context trimmed" chip.
- `reset()` creates a fresh `Conversation` from the engine without reloading the model (fast).

### 3.4 Background behavior

| Scenario | Action |
|----------|--------|
| App backgrounded, no active generation | Keep engine loaded 30s, then unload (configurable) |
| App backgrounded during generation | Continue in foreground service OR cancel + persist partial message |
| `onTrimMemory(MODERATE+)` | Unload engine immediately |
| Process death | Chat history survives in Room; engine reloads on next open with "Restoring model…" state |

---

## 4. Chat Interface Revamp

### 4.1 MVI state model

Replace 7 scattered `StateFlow`s with one immutable `ChatUiState`:

```
data class ChatUiState(
    val chats: List<ChatSummary>,            // drawer/history list
    val activeChatId: ChatId?,
    val messages: List<UiChatMessage>,       // current chat only
    val model: ModelChipState,               // NOT_SELECTED / LOADING / READY / ERROR
    val generation: GenerationState,         // IDLE / STREAMING / CANCELLABLE
    val contextUsage: ContextUsage,          // usedTokens / maxTokens / isTrimmed
    val error: ChatError? = null             // sealed: OOM, ModelMissing, GenerationFailed
)
```

Events: `ChatEvent.SelectModel`, `ChatEvent.SendMessage`, `ChatEvent.CancelGeneration`, `ChatEvent.NewChat`, `ChatEvent.DeleteChat`, `ChatEvent.ClearContext`.

### 4.2 UI/UX improvements

| Current | Revamped |
|---------|----------|
| Dropdown model selector | Top app bar chip showing active model + bottom-sheet model picker with RAM requirement badges |
| Plain text bubbles | Markdown-aware bubbles (code blocks, lists), copy-on-long-press, streaming cursor animation |
| No cancel button | Send button becomes Stop button during generation; partial reply kept |
| Scroll-to-bottom on every emit | Smart auto-scroll: only if user is already at bottom; "Scroll to latest" FAB otherwise |
| No timestamps | Relative timestamps + date separators |
| No empty-state guidance | Suggested prompts / "New chat" CTA |
| 4 Activities | Single `MainActivity` + Navigation-Compose; chat is the start destination |

### 4.3 Chat history screen

- Navigation drawer (or bottom-sheet on small screens) listing all chats, grouped by date (Today / Yesterday / Last 7 days).
- Swipe-to-delete with undo snackbar.
- Auto-generated chat titles (first 40 chars of first user message, or model-generated summary later).

---

## 5. Data & Persistence Design

### Room schema v2

```
DownloadedModelEntity  (existing — unchanged)
ChatEntity(
    chatId: String PK,
    title: String,
    modelId: String FK,
    createdAt: Long,
    updatedAt: Long,
    isArchived: Boolean
)
MessageEntity(
    messageId: String PK,
    chatId: String FK index,
    role: USER/ASSISTANT/SYSTEM,
    content: String,
    tokenCount: Int,
    createdAt: Long,
    isComplete: Boolean          // false if generation was interrupted
)
```

- Migration 1→2 with fallback destructive only for debug builds.
- `ChatDao` exposes `Flow<List<ChatWithLastMessage>>` for the history list.

### DataStore (Settings)

```
data class AppSettings(
    val theme: ThemeMode,            // SYSTEM / LIGHT / DARK
    val useDynamicColor: Boolean,
    val preferredBackend: BackendPref, // AUTO / GPU / CPU
    val temperature: Double,
    val topK: Int, val topP: Double,
    val maxContextTokens: Int,       // 1024 / 2048 / 4096
    val autoUnloadMinutes: Int,      // 0 = never
    val hapticFeedback: Boolean
)
```

---

## 6. Dependency Injection — Hilt migration

Replace `Injection.kt` and manual `ViewModelProvider.Factory` classes with Hilt:

- `@HiltAndroidApp` on `TityModelsApp`; `@AndroidEntryPoint` on `MainActivity` and `ModelDownloadWorker` (via `HiltWorkerFactory`).
- Modules: `AppModule` (dispatchers, context), `DatabaseModule` (Room + DAOs), `NetworkModule` (OkHttp client singleton), `InferenceModule` (`@Singleton ModelManager`).
- ViewModels use `@HiltViewModel` with constructor injection — delete all `*ViewModelFactory.kt` files.
- Benefits: single DB instance, testability (fake repositories), `ModelManager` survives all ViewModels.

---

## 7. Performance Optimizations

| Area | Change |
|------|--------|
| Model loading | Warm engine with empty prefill after initialize; show granular load progress ("Initializing…", "Warming up…") |
| Inference | GPU backend with CPU fallback; expose `SamplerConfig` in Settings (temperature/topK/topP as `Double` per SDK) |
| Streaming UI | `sendMessageAsync` emits cumulative text — **replace, not append** (fixes duplication bug); throttle UI emissions to ~60ms via `conflate` + `sample` |
| List rendering | Stable `key(messageId)` in LazyColumn; `derivedStateOf` for scroll position; avoid recomposition of unchanged bubbles via `@Immutable` UiChatMessage |
| Downloads | Keep WorkManager; inject worker with Hilt; add resume-on-reconnect; validate file size after download; delete partials |
| Database | `MessageEntity` paging via Paging-3 for very long chats; index on `chatId` |
| Memory | Baseline Profile + Macrobenchmark for cold-start and TTFT (time-to-first-token) metrics |

---

## 8. Error Handling & Resilience

Sealed error taxonomy:

```
sealed class InferenceError {
    object OutOfMemory : InferenceError()
    object ModelFileMissing : InferenceError()
    object BackendUnavailable : InferenceError()
    object ContextOverflow : InferenceError()
    data class GenerationFailed(val cause: String) : InferenceError()
}
```

- UI shows actionable messages: OOM → "Close other apps"; ModelMissing → "Re-download model".
- Generation cancellation: `Job` tracked in `ChatViewModel`; Stop button cancels flow, persists partial message with `isComplete=false`.
- Crashlytics/Logcat integration behind a `Logger` interface (no PII in logs).

---

## 9. Testing Strategy

| Layer | Tests |
|-------|-------|
| Domain | Pure JUnit: use cases, context trimming logic, token estimation |
| Data | Room in-memory DB tests; repository tests with fake DAOs; MockWebServer for HF API |
| Inference | Fake `ModelManager` interface for ViewModel tests; Robolectric for memory policy |
| UI | Compose screenshot tests (Paparazzi) for chat bubbles; `createComposeRule` for send/stop flows |
| Integration | Macrobenchmark: cold start, model load time, TTFT on reference device |

---

## 10. Implementation Phases

> Each phase is independently shippable. Estimated for one developer.

### Phase 0 — Foundation (0.5 day)
- Add Hilt, DataStore, Navigation-Compose dependencies; bump `compileSdk` if needed.
- Create `TityModelsApp`, move `Injection` initialization into Hilt modules.

### Phase 1 — Memory & Engine Core (2 days)
- Implement `ModelManager` singleton with load/unload, backend fallback, RAM guard.
- Implement `ModelLoadPolicy` + `MemoryUtils`.
- Wire `ComponentCallbacks2` trimming.
- **Fix cumulative-streaming duplication bug.**

### Phase 2 — Chat Persistence & Context (2 days)
- Room v2 schema + migration; `ChatRepositoryImpl`.
- `ConversationSession` with token tracking and sliding-window trim.
- New-chat / delete-chat / restore-chat use cases.

### Phase 3 — UI Revamp (2.5 days)
- Single-activity Navigation-Compose skeleton.
- New `ChatScreen` with MVI state, model picker bottom-sheet, markdown bubbles, stop button, smart scroll.
- Chat history drawer.
- New theme with dynamic color + dark mode.

### Phase 4 — Settings & Polish (1 day)
- Settings screen backed by DataStore (theme, backend, sampler, context size, auto-unload).
- Downloaded-models management screen refresh (storage used, delete confirmation, unload model if active).
- Error states, empty states, loading skeletons.

### Phase 5 — Hardening & Release (1 day)
- Baseline Profile, R8 optimization enablement.
- Unit + UI test pass, memory profiling on 6GB and 12GB devices.
- Analytics-free release build; signed APK/AAB.

**Total: ~9 developer-days.**

---

## 11. Open Questions for Discussion

1. **Multi-modal support?** LiteRT-LM supports image input (`Content.ImageBytes`) for Gemma 4 models. In scope or defer?
2. **Chat titles:** auto-generate from first message locally, or call the loaded model to summarize (costs one inference)?
3. **Context trimming strategy:** sliding-window only, or also offer "summarize old turns" (requires extra inference pass)?
4. **Keep model always loaded** vs **auto-unload timer** — what's the desired default UX?
5. **Minimum device bar:** enforce 6GB RAM minimum at install/download time, or allow CPU-only low-end with warnings?
6. **Export/share chats** (Markdown file) — nice-to-have?
7. **Rebrand?** "TityModels" package is `com.example.titymodels` — worth a proper applicationId before release?

---

*Generated after full codebase review on 2026-10-01. Ready for discussion.*
