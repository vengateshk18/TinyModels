# TinyModels — Feature Opportunities

Derived from a full read of the codebase (core / domain / data / di / feature).
Each item lists **what**, **why**, and the **touch points** already present in the code.

---

## 0. Fix-first (gaps found in the current code)

These are cheap, high-impact, and block several features below.

| # | Issue | Where | Fix |
|---|-------|-------|-----|
| 0.1 | `ChatRoomScreen` title shows `uiState.chats.firstOrNull()?.title` but `observeChats()` is **never called** in `ChatViewModel` → `chats` is always empty, title is always "Chat". | `ChatViewModel.observeChats()` (dead code), `ChatRoomScreen:78` | Call `observeChats()` in `init`, or expose the active chat's title in `ChatUiState`. |
| 0.2 | Inference settings sheet is unreachable — `showInferenceSheet` is never set to `true` (no Tune button in the top bar). | `ChatRoomScreen:64,142` | Add the `Icons.Filled.Tune` action to the TopAppBar (icon is already imported). |
| 0.3 | `ContextUsage.maxTokens` never syncs with `InferenceSettings.maxContextTokens`, so the usage bar is always out of 2048. | `ChatViewModel.updateContextUsage()` | Read `maxTokens` from the chat's settings. |
| 0.4 | Resume of an interrupted download isn't real — the worker always restarts from byte 0 and `Result.retry()` re-downloads the whole file. | `ModelDownloadWorker.downloadFile()` | HTTP `Range:` header + append to a `.part` file. |
| 0.5 | Auto-select logic in `observeDownloadedModels()` has an inverted `hadNone` guard, so first-run auto-select rarely fires. | `ChatViewModel:105-119` | Simplify to "if no active model and list non-empty → select first". |
| 0.6 | `ModelDtoParser` uses `org.json` which only exists on Android — unit tests pull a separate `org.json:json` artifact. Parsing is otherwise untested for malformed payloads. | `core/network/dto` | Migrate to `kotlinx.serialization` for type safety + JVM testability. |
| 0.7 | No `ChatScreen` route — `ChatScreen.kt` (drawer + model picker) is fully written but orphaned after the bottom-nav revamp. `ModelPickerSheet` is referenced but doesn't exist in `components/`. | `feature/chat/ChatScreen.kt` | Either delete it or fold the model-picker into `ChatRoomScreen`. |
| 0.8 | Release build has `optimization { enable = false }` — no R8/shrinking. | `app/build.gradle.kts` | Enable minify + add LiteRT keep rules. |

---

## 1. Chat experience

| Feature | Value | Implementation notes |
|---------|-------|----------------------|
| **Per-chat model switching** | Today a chat is pinned to the model it was created with. | `ModelPickerSheet` + `ChatEvent.SelectModel` already exist; add `chatDao.updateChatModel`. |
| **Streaming stats (tok/s, TTFT)** | Core differentiator for an on-device LLM app — users want to compare GPU vs CPU. | `ConversationSession.send()` already tracks char deltas; record `firstTokenAt` + `completedAt` (field exists on `MessageEntity`). |
| **Copy / share / select message text** | Table-stakes chat UX, currently missing. | `MessageBubble` long-press → `ClipboardManager`. |
| **Chat search** | Scales with history growth. | `ChatDao` + FTS4 virtual table over `messages.content`. |
| **Chat export (Markdown / JSON)** | Lets users get data out; pairs with "privacy-first, local" positioning. | New `ExportChatUseCase` + `ACTION_CREATE_DOCUMENT` SAF intent. |
| **Pin / archive chats** | `ChatEntity.isArchived` **already exists** and is filtered in `observeChatSummaries()` — but nothing can set it. | Wire an `archiveChat()` into `ChatRepository` + swipe action. |
| **System-prompt presets** | "Coder", "Translator", "Summarizer" starter personas. | `InferenceSettings.systemInstruction` is already per-chat; add a preset picker in `InferenceSettingsSheet`. |
| **Sample prompts from the model card** | `ModelDetails.widgetPrompts` is parsed but **never used in the UI**. | Show as chips on an empty chat screen. |
| **Context-full warning + auto-summarize** | `isContextNearFull` is computed but never surfaced. | Banner at 80%; optional "summarize older turns" pass. |
| **Multi-turn regenerate variants** | Regenerate exists, but only replaces; keeping A/B variants is a nicer UX. | Store variants under one `messageId` group. |
| **Voice input (speech-to-text)** | Natural fit for a mobile assistant. | `SpeechRecognizer` → `ChatInputBar`. |
| **Text-to-speech playback** | Accessibility + hands-free. | Android `TextToSpeech` on assistant bubbles. |

---

## 2. Model catalog & downloads

| Feature | Value | Implementation notes |
|---------|-------|----------------------|
| **Resumable + pausable downloads** | Biggest reliability win — these files are 1–8 GB. | Range requests in `ModelDownloadWorker`; persist bytes-downloaded in `ModelFileEntity`. |
| **Download queue / multi-file** | Currently one unique-work per file, no queue view. | `WorkManager.beginWith().then()` chain + a "Downloads" screen listing all `WorkInfo`s. |
| **Checksum / integrity verification** | `ModelDetails.sha` is parsed but unused; corrupt downloads currently fail at load time with a cryptic error. | SHA-256 after download, mark `FAILED` with "corrupt" reason. |
| **Wi-Fi-only / metered-network preference** | Avoid burning cellular data on a 4 GB model. | `Constraints.setRequiredNetworkType(UNMETERED)` driven by a new DataStore flag. |
| **HF token / gated-model support** | `ModelDetails.isGated` is computed but there's no auth path, so gated models 401. | Store token in `EncryptedSharedPreferences`; add `Authorization` header in `HuggingFaceApi`. |
| **"Will it run on this device?" badge** | `DeviceViewModel` already computes `AiCapability` + `recommendedMaxParams`; the model list doesn't use it. | Cross-reference file size vs `MemoryUtils.snapshot()` on each list row. |
| **Quantization filter (Q4/Q8/BF16)** | `litert-community` repos expose several variants per model; users can't filter. | Parse suffixes from `siblings`, add filter chips. |
| **Favorites / bookmarks** | Browsing 100 models without saving is painful. | New `favorites` table + star icon. |
| **Offline catalog cache** | `listModels()` is network-only — the browse tab is empty with no connection. | Cache the last response in Room with a TTL. |
| **Import a local model file** | Sideload `.litertlm` from storage, no HF required. | SAF `OpenDocument` → copy into `filesDir/models/`. |
| **Model comparison / benchmark** | Run the same prompt on two models and compare tok/s + output. | New `feature/benchmark` using the existing `ModelManager` swap logic. |

---

## 3. Inference & engine

| Feature | Value | Implementation notes |
|---------|-------|----------------------|
| **Keep-alive / idle unload policy** | Right now the engine unloads only on `onTrimMemory`; an idle timer would free RAM proactively. | Timer in `ModelManager`, user-configurable in Settings. |
| **Preload on app start** | Last-used model loads in background so the first message isn't a 10 s wait. | Store `lastModelId` in DataStore; warm-load from `TinyModelsApp`. |
| **Accurate token counting** | `estimateTokens` is `length / 4` — the context bar is a rough guess. | Use the runtime's tokenizer if exposed, else a BPE approximation. |
| **Real backend benchmark on first load** | AUTO currently just tries GPU then CPU; measuring once and remembering is better. | Short warm-up generation, persist the winner per model. |
| **Multimodal (image input)** | LiteRT-LM supports vision models; `Content` already has non-text subtypes. | Extend `ConversationSession.send()` to accept `Content.Image`. |
| **Function calling / tool use** | Turns the app from a chat toy into an assistant. | JSON-schema prompt + a small local tool registry (calculator, device info). |
| **RAG over local documents** | High-value offline use case. | On-device embeddings + simple vector store in Room (SQLite). |

---

## 4. Platform / system integration

| Feature | Value |
|---------|-------|
| **Home-screen widget** — quick-prompt box that opens the chat with text prefilled. |
| **Share-sheet target** — "Share to TinyModels" to summarize/translate selected text from any app. |
| **Quick Settings tile** — start a new chat from the notification shade. |
| **App shortcuts** — long-press launcher → recent chats. |
| **Notification reply** — continue a conversation from the notification (channel infra already exists in `DownloadNotifier`). |
| **Foreground service during generation** — long generations currently die if the process is killed. |

---

## 5. Settings, privacy & data

| Feature | Notes |
|---------|-------|
| **App lock (biometric)** | Chats can contain sensitive data; `BiometricPrompt` gate on launch. |
| **Backup / restore** | `backup_rules.xml` exists but DB export/import isn't exposed. |
| **Per-chat default overrides** | Global defaults exist (`AppSettings.defaultBackend`); add defaults for temperature/system prompt too. |
| **Storage manager** | `DownloadedModelsScreen` shows total size; add per-model breakdown + "free up space" bulk delete. |
| **Crash / error log viewer** | Local-only log of `InferenceError`s to help users self-diagnose OOM. |

---

## 6. Engineering quality

| Item | Why |
|------|-----|
| **Test coverage** | Only 2 unit tests exist (`ModelDtoParserTest`, `ModelDetailsViewModelTest`). `ChatViewModel` (591 lines, the riskiest file) has zero tests. |
| **Room schema export + real migrations** | `exportSchema = false` and `fallbackToDestructiveMigration(dropAllTables = true)` will wipe user chats on every version bump. |
| **Split `ChatViewModel`** | 591 lines mixing session lifecycle, generation, edit/regenerate, persistence. Extract `SendMessageUseCase` / `RegenerateUseCase` — the `domain/usecase/chat` package is already created but **empty**. |
| **Compose UI tests** | `ui-test-junit4` is wired up but unused. |
| **Baseline Profile** | Measurable cold-start/scroll win for a Compose-heavy app. |
| **Modularization** | `:core`, `:domain`, `:data`, `:feature-*` Gradle modules would cut incremental build time and enforce the dependency rule the README already claims. |
| **CI** | No workflow file; add build + unit-test on PR. |

---

## Suggested order

1. **Section 0** — fix the dead/unreachable code (half a day, makes existing features actually usable).
2. **Resumable downloads + checksum** — the single biggest reliability complaint for GB-sized models.
3. **Copy/share, sample prompts, context warning, tok/s stats** — cheap chat-UX wins on existing data.
4. **Offline catalog cache + "runs on this device" badge** — makes browsing useful without network.
5. **Real Room migrations + `ChatViewModel` tests** — before the user base grows and destructive migration becomes unacceptable.
6. **Bigger bets**: multimodal, RAG, tool use, benchmark mode.
