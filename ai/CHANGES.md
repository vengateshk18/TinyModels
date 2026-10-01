# What Changed in TinyModels

A simple summary of the revamp: what the app used to be, what it is now, and the
changes made along the way.

---

## 1. The big picture

| | Before (legacy) | After (now) |
|---|---|---|
| **App name** | TityModels | **TinyModels** (package, app id, theme, DB) |
| **Architecture** | Ad-hoc, Activities per screen | **Clean Architecture**, single-activity Compose |
| **UI** | Multiple Activities + Views | **Jetpack Compose** (Material 3, MVI) |
| **DI** | Manual `Injection` singleton | **Hilt** (`@HiltAndroidApp`, modules) |
| **Memory** | Load models freely, risk of OOM | **`ModelManager`** — one model in RAM, guarded load |
| **Chat state** | Mutable, scattered | **Single immutable `ChatUiState`** (MVI) |
| **Downloads** | Legacy worker + manual repos | **WorkManager** unique-work + `@HiltWorker` |
| **Persistence** | Mixed | **Room v2** (chat + models) + **DataStore** (settings) |
| **Settings** | None | **Settings screen** (theme/backend/sampler/context) |

---

## 2. Final structure (Clean Architecture)

```
com.example.tinymodels/
├── core/        shared, no UI  (common utils, Room DB, inference engine, network, theme)
├── domain/      business rules (models, repository interfaces, use cases)
├── data/        implementations (repository impls, download worker)
├── di/          Hilt modules (App, Database, Network, Repository)
├── feature/     UI per feature  (chat, models, settings, navigation) — MVI
├── MainActivity.kt
└── TinyModelsApp.kt
```

**Dependency flow:** `feature → domain ← data ← core`

---

## 3. What was built, slice by slice

Each change was implemented end-to-end, verified with a build, and committed.

| # | Change | What it added |
|---|--------|---------------|
| 0+1 | **Foundation + memory/engine core** | Hilt setup, Room DB, DataStore, theme tokens, `AppResult`, `DispatcherProvider`, `MemoryUtils` (OOM-safe RAM check), `ModelManager` (single-model load/unload), `ConversationSession` (streaming + cancel + context) |
| — | **Rename** | TityModels → TinyModels across package, appId, theme, database |
| 2 | **Chat persistence** | Room v2 schema (Chat + Message entities/DAOs), `ChatRepository` |
| 3 | **Chat logic (MVI)** | `ChatViewModel` + immutable `ChatUiState`; fixed the duplicated-text streaming bug (cumulative deltas → replace, not append) |
| 4 | **Chat UI** | `ChatScreen`, message bubble, input bar, history drawer, model chip; Compose theme; single `NavHost` + `MainActivity` |
| 5 | **Models feature** | Browse/details/download; Hugging Face catalog (`HuggingFaceApi`); `DownloadModelUseCase`; `@HiltWorker` download with progress + foreground notification |
| 6 | **Manage downloads + cleanup** | Downloaded-models screen with delete (unloads first if active); **deleted the whole legacy tree** (`models/`, `chat/`, `utils/`, `ui/`) |
| 7 | **Settings** | Settings screen: theme (system/light/dark), dynamic color, backend (AUTO/GPU/CPU), temperature/topK/topP sliders, context-window size |
| 8 | **Final cleanup & verify** | Removed unused deps (`appcompat`, `material`); rewrote README; clean build + unit tests pass |

---

## 4. The most important change: memory management

This was the core goal. How it works now:

- **One model at a time** — `ModelManager` keeps exactly one model resident in RAM.
- **Guarded loading** — before loading, it checks available RAM (plus a safety buffer)
  against the model's size. If it won't fit, it fails fast with a clear error instead of
  crashing the app with an `OutOfMemoryError`.
- **Clean unload on switch/delete** — switching models or deleting the active one always
  unloads the engine first, so a file is never deleted out from under a live session.
- **Reacts to system pressure** — the app listens to `onTrimMemory` and unloads the model
  when the OS is under memory pressure.

---

## 5. Other notable fixes & decisions

- **Streaming duplication bug fixed** — LiteRT-LM emits *cumulative* text; the UI now
  **replaces** the partial message instead of appending, so tokens no longer repeat.
- **Backend fallback** — AUTO tries GPU first, falls back to CPU.
- **Context handling** — each conversation gets a bounded context window; prior turns are
  restored (sliding-window trimmed) when reopening a chat.
- **Downloads are robust** — unique-work per model, progress shown, partial files cleaned
  up on cancel/failure, completed downloads recorded in Room.
- **Theme correctness** — all colors come from `MaterialTheme.colorScheme` (no hardcoded colors).

---

## 6. Verification

```bash
./gradlew :app:assembleDebug      # BUILD SUCCESSFUL (clean build)
./gradlew :app:testDebugUnitTest  # BUILD SUCCESSFUL
```

Working tree is clean; no legacy references remain.

---

## 7. Honest limitations / next steps

- **Test coverage is thin** — infra exists (JUnit + kotlinx-coroutines-test); more unit
  tests for ViewModels/use cases are the natural next step.
- **Not yet run on a physical device** — inference and download paths are built and
  compile-clean but should be validated on real hardware (RAM behavior especially).
