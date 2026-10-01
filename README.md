# TinyModels

An on-device AI chat Android app. Download small LLMs (LiteRT-LM `.litertlm` / `.task`) and chat fully offline, with careful memory management so only one model is resident in RAM at a time.

## Architecture

Clean Architecture, single-activity, Jetpack Compose, Hilt DI, Kotlin Coroutines/Flow.

```
app/src/main/java/com/example/tinymodels/
├── core/                      # Shared building blocks (pure, no Android UI)
│   ├── common/                #   AppResult, DispatcherProvider, MemoryUtils (OOM-safe checks)
│   ├── database/              #   Room: TinyModelsDatabase, DAOs, entities (chat + downloaded models)
│   ├── inference/             #   ModelManager (load/unload, memory guard, single-model)
│   │                          #   ConversationSession (streaming generation, cancel, context)
│   ├── network/               #   HuggingFaceApi (OkHttp), ModelDtoParser
│   └── ui/theme/              #   Design tokens: Color, Type, Theme (light/dark/dynamic)
│
├── domain/                    # Business logic, framework-agnostic
│   ├── model/                 #   Model, Chat, AppSettings, InferenceConfig, ...
│   ├── repository/            #   Interfaces: ChatRepository, ModelRepository, SettingsRepository
│   └── usecase/               #   DownloadModelUseCase, ...
│
├── data/                      # Implementations of domain contracts
│   ├── repository/            #   ChatRepositoryImpl, ModelRepositoryImpl, SettingsRepositoryImpl
│   └── worker/                #   ModelDownloadWorker (@HiltWorker, foreground, progress)
│
├── di/                        # Hilt modules: App, Database, Network, Repository
│
├── feature/                   # UI, one package per feature (MVI)
│   ├── chat/                  #   ChatScreen, ChatViewModel, components/ (Bubble, InputBar, Drawer, Chip)
│   ├── models/                #   Model list / details / downloaded-management screens + ViewModels
│   ├── settings/              #   SettingsScreen, SettingsViewModel
│   └── navigation/            #   Routes, TinyNavHost (single NavHost)
│
├── MainActivity.kt            # Single activity, hosts TinyNavHost
└── TinyModelsApp.kt           # @HiltAndroidApp + WorkManager Configuration.Provider
```

### Dependency flow
`feature → domain ← data ← core`. UI depends only on domain interfaces; data implements them; DI wires it together.

## Key behaviors

- **Memory management** — `ModelManager` loads exactly one model at a time. It checks available RAM (plus a buffer) against the model size before loading and throws `OutOfMemoryException` early instead of crashing. Switching or deleting the active model unloads it first. The app also reacts to `onTrimMemory` by unloading under pressure.
- **Chat** — MVI: a single immutable `ChatUiState` drives the screen. History is persisted in Room; each conversation gets its own `ConversationSession` with bounded context (`maxContextTokens`).
- **Downloads** — WorkManager unique-work per model with progress, a foreground notification, and partial-file cleanup on cancel/failure. Completed downloads are recorded in Room and shown under "Downloaded models".
- **Catalog** — models are browsed from the Hugging Face `litert-community` catalog via `HuggingFaceApi` (network-only, size from `x-linked-size`).
- **Settings** — theme (system/light/dark), dynamic color, inference backend (AUTO/GPU/CPU), sampler (temperature/topK/topP) and context-window size, persisted in DataStore.

## Build & test

```bash
./gradlew :app:assembleDebug     # build
./gradlew :app:testDebugUnitTest # unit tests
```

Requires Android Studio (Hedgehog+), a device/emulator with enough free RAM for the chosen model, and network access on first run to fetch the model catalog.
