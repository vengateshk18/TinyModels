# TinyModels 📱🤖

**Run large language models entirely on your Android phone — fully offline, fully private.**

TinyModels lets you browse, download, and chat with small LLMs (Gemma and friends) directly on your device. No server, no account, no data leaving your phone. Powered by Google's **LiteRT-LM** on-device runtime.

---

## ✨ What it does

| Feature | Details |
|---|---|
| 🔍 **Model catalog** | Browse the Hugging Face `litert-community` catalog — search, filter, and inspect models with per-file details (size, quantization, context window). |
| ⬇️ **Downloads** | Resumable downloads via WorkManager with a foreground notification, progress, cancel, and per-file tracking. Device-capacity checks warn you before downloading a model that won't fit in RAM. |
| 💬 **On-device chat** | Streaming, token-by-token generation with markdown-rendered bubbles. Multiple persisted conversations (Room DB), regenerate/edit any message, per-chat sampler settings. |
| 🧠 **Automatic context management** | Detects each model's true context window, tracks live token usage (circular % indicator in the app bar), and **auto-compacts at 90% usage** — older turns are summarized into a digest so long chats never overflow. Manual "Compact" button + metrics sheet included. |
| ⚡ **Benchmarks** | Built-in prompt benchmark measuring tokens/sec of the loaded model, with history. |
| 📊 **Home dashboard** | Usage stats: chats created, models tried, tokens generated, average generation speed. |
| 🎨 **Settings** | Theme (system/light/dark + dynamic color), font choice & scale, default backend, Hugging Face token, clear-data. |

## 🛠️ Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3, edge-to-edge)
- **MVI** architecture — single immutable `UiState` per screen
- **Hilt** dependency injection
- **Room** (v7) — chats, messages, downloaded models & files
- **DataStore** — app settings
- **WorkManager** — model downloads
- **Coroutines/Flow** — all native/DB work off the main thread; native access serialized via mutex (LiteRT-LM is not thread-safe)
- **LiteRT-LM 0.17.1** — the on-device inference engine (CPU/GPU/Auto backend)

## 🏗️ Architecture

Clean Architecture, single-activity, UI → domain ← data, DI-wired:

```
app/src/main/java/com/example/tinymodels/
├── core/                      # Shared building blocks
│   ├── common/                #   AppResult, DispatcherProvider, MemoryUtils (OOM-safe checks)
│   ├── database/              #   Room: TinyModelsDatabase, DAOs, entities, migrations
│   ├── inference/             #   ModelManager (single-model memory guard),
│   │                          #   ConversationSession (streaming, compaction),
│   │                          #   ModelContextInspector (context-window detection)
│   ├── network/               #   HuggingFaceApi (OkHttp), DTOs
│   └── ui/theme/              #   Design tokens: light/dark/dynamic themes
│
├── domain/                    # Framework-agnostic business logic
│   ├── model/                 #   Model, Chat, AppSettings, InferenceSettings, ...
│   ├── repository/            #   Interfaces: Chat/Model/SettingsRepository
│   └── usecase/               #   Use cases
│
├── data/                      # Implementations
│   ├── repository/            #   Chat/Model/SettingsRepositoryImpl
│   └── worker/                #   ModelDownloadWorker (foreground, progress)
│
├── di/                        # Hilt modules
├── feature/                   # UI, one package per feature (MVI)
│   ├── chat/                  #   Chat room, history drawer, settings sheets
│   ├── models/                #   Catalog, details, downloaded management
│   ├── benchmark/             #   Benchmark screen
│   ├── home/                  #   Dashboard + device info
│   ├── settings/              #   App settings
│   └── navigation/            #   Single NavHost
│
├── MainActivity.kt            # Single activity (theme applied pre-composition — no flash)
└── TinyModelsApp.kt           # @HiltAndroidApp + WorkManager config
```

## 🔑 Key behaviors

- **Memory safety** — exactly **one model in RAM at a time**. `ModelManager` checks free RAM (plus buffer) before loading, unloads on `onTrimMemory`, and unloads before switching/deleting the active model.
- **Context compaction** — the context budget is **automatic** (detected from the model file, safe 2048 default). At 90% usage, older turns are summarized into a digest (folded into the system prompt) and the session is rebuilt — the KV cache shrinks and usage drops. Editing a summarized turn invalidates the digest.
- **Overflow-proof generation** — 25% of the budget is reserved for the reply, so "Status code 13: Task failed with large input" errors are prevented by design.
- **Per-chat inference settings** — temperature, top-K, top-P, backend, and system instruction are stored per conversation; changes rebuild the session instantly.

## 📱 Specs

- minSdk 26 (Android 8.0) · targetSdk 37
- arm64-v8a native libs · R8-minified release APK ≈ **24 MB**
- All inference **offline & private** — nothing leaves the device

## 🔨 Build & test

```bash
./gradlew :app:assembleDebug      # debug build
./gradlew :app:assembleRelease     # minified release (~24 MB)
./gradlew :app:testDebugUnitTest   # unit tests
```

Requires Android Studio, a device/emulator with enough free RAM for the chosen model, and network access on first run to fetch the catalog (downloads and chat then work fully offline).

## 📄 License

See the repository for license details.