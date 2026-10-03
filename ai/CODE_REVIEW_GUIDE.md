# TinyModels — Code Guide & Feature Review Playbook

This document has two parts:

1. **Part 1 — How the code works**: an explanation of the architecture, each layer,
   and the flows that matter (chat generation, downloads, settings, persistence).
2. **Part 2 — How to review the next feature**: a repeatable process and checklist
   for reviewing any new change before it is merged, tuned to this codebase's
   actual failure patterns.

It is written for someone who will pick up a change (their own or someone else's)
and needs to understand what "correct" looks like here.

---

## Part 1 — Explaining the code

### 1.1 What the app is

TinyModels is an **on-device LLM chat app**. The user browses a catalog of small
models on Hugging Face (`litert-community`), downloads a model file, and chats with
it fully offline. LiteRT-LM (`com.google.ai.edge.litertlm`) is the inference engine.

The hard constraint that shapes the whole design: **a phone can hold only one small
model in RAM at a time**. Everything else — engine lifecycle, downloads, navigation —
is built around protecting that.

### 1.2 Architecture in one picture

```
┌──────────────────────── feature/ (UI, MVI) ────────────────────────┐
│  home · chat · models · settings · navigation                      │
│  each feature = Screen(s) + ViewModel holding ONE immutable        │
│  UiState exposed as StateFlow                                      │
└───────────────┬────────────────────────────────────────────────────┘
                │ depends on interfaces only
┌───────────────▼──────────── domain/ ───────────────────────────────┐
│  model/       plain data types (Model, Chat, AppSettings, …)       │
│  repository/  interfaces (ChatRepository, ModelRepository, …)      │
│  usecase/     DownloadModelUseCase, DownloadManager                │
└───────────────▲───────────────────────────▲────────────────────────┘
                │ implements                │ implemented by
┌───────────────┴───────────┐  ┌────────────┴───────── data/ ────────┐
│         di/ (Hilt)        │  │  repository impls · ModelDownload   │
│  App · Database · Network │  │  Worker (WorkManager, @HiltWorker)  │
│  · Repository modules     │  └────────────────────────────────────┘
└───────────────┬───────────┘
                │
┌───────────────▼──────────────── core/ (shared, no UI) ─────────────┐
│  common/     AppResult · DispatcherProvider · MemoryUtils ·        │
│              SnackbarManager · DownloadNotifier · StorageUtils     │
│  database/   Room (entities, DAOs, Migrations)                     │
│  inference/  ModelManager (engine) · ConversationSession (stream)  │
│  network/    HuggingFaceApi (OkHttp) · ModelDtoParser              │
│  ui/         Formatters · theme (Color/Type/Theme)                 │
└────────────────────────────────────────────────────────────────────┘
```

**Dependency flow:** `feature → domain ← data ← core`. The UI layer never
imports OkHttp, Room, or LiteRT-LM directly — it talks to repository interfaces
and to `ModelManager`/`ConversationSession` (which are core, not data).

**Entry points:** `TinyModelsApp` (`@HiltAndroidApp` + WorkManager config) →
`MainActivity` (single activity, sets edge-to-edge + theme) → `TinyModelsApp()`
composable in `feature/navigation` (bottom-bar `Scaffold` + one `NavHost`).

### 1.3 Layer-by-layer

#### `core/common` — result & plumbing types
- `AppResult<T>` — `Success`/`Error` wrapper; errors surface as `AppError`.
  Rule: a function that can fail returns `AppResult`, it does not throw across
  layer boundaries.
- `DispatcherProvider` — injectable dispatchers so tests can substitute test
  dispatchers.
- `MemoryUtils` / `StorageUtils` — pre-flight checks (free RAM before engine
  load, free disk before download). They exist so failures are **fast and
  legible** instead of an OOM crash or a stalled download.

#### `core/inference` — the two objects that matter most
- **`ModelManager`** (`@Singleton`): the *only* owner of the LiteRT-LM `Engine`.
  Invariants:
  - at most one engine exists; loads are serialized by a `Mutex`;
  - the swap is *new engine initialized first, then old closed* — a failed load
    never leaves the app with no working engine;
  - RAM is checked *before* loading (`MemoryUtils.hasHeadroom`), and failure is
    reported as `InferenceError.OutOfMemory` rather than crashing;
  - backend fallback: AUTO/GPU tries `Backend.GPU()` then `Backend.CPU()`;
  - `onTrimMemory(MODERATE+)` unloads the engine under system pressure;
  - `withEngine { … }` holds the mutex so generation cannot race an unload.
- **`ConversationSession`**: wraps one `Conversation` (the KV-cache) and its
  context window. Key behaviors:
  - `send()` streams a reply. **LiteRT-LM emits delta chunks; the session
    accumulates them and emits cumulative text**, so consumers REPLACE the
    buffer, never append. (The KDoc at the class level still says the engine
    emits cumulative text — the method doc is the correct one; see §2.5.)
  - `trimToBudget()` — sliding-window: when reopening a chat, only the most
    recent turns that fit `maxContextTokens` are restored.
  - `estimateTokens()` — `chars / 4`, used for the 80% high-water mark.
  - `close()` frees KV-cache memory *without* unloading the model.

#### `core/database` — Room, currently **version 5**
| Entity | Purpose |
|---|---|
| `DownloadedModelEntity` | one row per downloaded model (id, author, pipeline, localPath, downloadedAt) |
| `ModelFileEntity` | **subtable**, PK `(modelId, fileName)`: per-file status/size/localPath — added in v5 |
| `ChatEntity` / `MessageEntity` | conversations and messages, incl. `completedAt` per message |

`MIGRATION_4_5` splits the old newline-joined `files` column into `model_files`
rows and drops the stale columns (create-copy-rename pattern, since old SQLite
has no `DROP COLUMN`). ⚠️ Migration SQL is fragile — it already had one runtime
crash fixed (`char(10)` instead of a literal newline, commit `31c54e5`). Any
schema change must be tested with a **populated** pre-upgrade database, not an
empty one.

#### `core/network` — Hugging Face catalog
`HuggingFaceApi` (OkHttp) exposes:
- `listModels(search, pipelineTag, author, limit)` — `GET /api/models`, sorted by
  downloads, `full=true`; blank search falls back to `author=litert-community`;
  values are URL-encoded.
- model details endpoint → `ModelDtoParser.parseDetails()` → rich `ModelDetails`
  (`usedStorage`, `gated`, `siblings`, `widgetPrompts`, `createdAt`, …).

Known limits: hard `limit=100` (**no pagination**), `search` and `author` are
mutually exclusive, and errors collapse to a generic `IOException` message (no
429/backoff handling).

#### `domain/` — contracts and rules
- `ModelFilter` — pipeline-tag filter chips (`ALL`, `text-generation`,
  `text-to-image`).
- `DownloadModelUseCase` — builds worker input from `runtimeFiles`
  (`.litertlm`/`.task`/`.tflite`), with `calculateSize()`.
- `DownloadManager`/`DownloadManagerService` — **single-download enforcement**:
  `startDownload()` returns `false` if anything is already active; a queue
  (`queueDownload`/`dequeueNext`) exists for later requests. All state is
  `synchronized` + `StateFlow`.

#### `data/`
- Repository impls (`ChatRepositoryImpl`, `ModelRepositoryImpl`,
  `SettingsRepositoryImpl`) — turn `AppResult`/Flow from core into domain types.
- `ModelDownloadWorker` (`@HiltWorker`, foreground) — unique-work per model,
  progress via `DownloadNotifier`, partial-file cleanup on cancel/failure,
  completion recorded into Room. `CancelDownloadReceiver` handles the
  notification's Cancel action.

#### `feature/` — UI, one package per feature
- **navigation** — `Routes` (tab routes + pushed routes; path args are
  URL-encoded because HF model ids contain `/`), `TinyModelsApp` (outer Scaffold
  with `contentWindowInsets = 0`, bottom bar shown only on the 4 tab routes).
- **chat** — `ChatTabScreen` (session list + FAB) → `ChatRoomScreen` (per-session,
  `SavedStateHandle`), `ChatViewModel` (the largest VM: model selection, session
  build/rebuild, streaming, cancel, regenerate, edit), `components/`
  (MessageBubble, ChatInputBar, ChatHistoryDrawer, InferenceSettingsSheet,
  EditMessageDialog, ModelChip).
- **models** — `ModelsTabScreen` (Browse + Downloaded sub-tabs),
  `ModelListScreen`/`ModelListViewModel` (search + filter), `ModelDetailsScreen`
  (header/stats/about/tags/files + `DownloadBar`), `DownloadedFileDetailScreen`,
  `DownloadedModelsScreen`.
- **home** — `HomeScreen` (AI capability card) + `DeviceInfoScreen` (reached from
  Settings), driven by `DeviceViewModel`.
- **settings** — Appearance (font family, font scale), Inference (default backend
  for *new* sessions), About (device info, clear chat history).

### 1.4 The flows worth memorizing

**Chat (the most bug-prone path):**
```
ChatTabScreen → newChat() → ChatRoomScreen(chatId)
ChatViewModel.observeMessages(chatId)      # Room Flow → UiState.messages
sendMessage(text):
  save user msg → runGeneration():
    ModelManager.withEngine { ConversationSession.create/reuse … session.send(prompt) }
      → cumulative text emitted → uiState replaces the streaming bubble
      → on completion: persistAssistant(completedAt) → Room emission
```
Three invariants reviewers must protect:
1. **Room emissions merge with, and never clobber, the streaming placeholder**
   (`streamingMessageId` merge in `observeMessages`, fix `bc46887`).
2. **Streaming UI replaces text; it never appends** (`1a7435b` fixed
   tokens-replacing-each-other by accumulating deltas inside `ConversationSession`).
3. **Cancellation is `CancellationException` rethrown**, so `cancelGeneration()`
   owns cleanup instead of the flow `.catch` producing a fake error (`b80cac0`).

**Download:**
```
ModelDetailsScreen → onDownloadClick()
  → CHECKING_SIZE (instant UI) + StorageUtils.hasSpaceFor pre-check
  → DownloadModelUseCase → WorkManager unique work
  → ModelDownloadWorker streams to disk → progress via DownloadNotifier
  → completion → DownloadedModelEntity + ModelFileEntity rows
  re-entry → observeExisting(modelId) rebinds to the running work (resumable, D2)
```
`DownloadManager` additionally guarantees **one active download at a time**.

**Settings:** DataStore (`SettingsRepositoryImpl`) → `SettingsViewModel` →
`AppSettings` → applied in `MainActivity`/`TinyModelsTheme` (theme mode, dynamic
color, `fontScale` → `buildTypography`) and in new-chat creation (`defaultBackend`
is copied into each new session at creation, then the session owns it).

### 1.5 Conventions that are not negotiable here

These are the project's invariants; a change that violates them is wrong even if
it compiles and looks right:

1. **One engine, one active model** — never construct `Engine` outside
   `ModelManager`; never delete/replace a model file while it is loaded.
2. **No hardcoded colors or text sizes** — everything from
   `MaterialTheme.colorScheme` / `MaterialTheme.typography` (use `core/ui/Formatters`
   for bytes/dates/counts, do not re-implement them per screen).
3. **Insets are handled once per nesting level** — the outer Scaffold applies
   system-bar padding; nested tab Scaffolds set
   `contentWindowInsets = WindowInsets(0,0,0,0)`. Adding `navigationBarsPadding()`
   *and* relying on Scaffold insets produces the double-gap bug that has already
   been fixed three times (`d65c166`, `f340b86`, `316ecaf`).
4. **MVI** — one immutable `UiState` per screen, events in, state out; no
   composable-local truth for anything a ViewModel must survive (config change,
   navigation).
5. **Schema changes need a real migration** — no `fallbackToDestructiveMigration`
   for user data; bump the version, write the `Migration`, test with data.
6. **Thin vertical slices, one commit each** — every past feature
   (0–8, D1–D4, C1–C7, N1–N9, S1–S7, M1–M7, F1–F6) was delivered this way, with
   a plan doc in `ai/` first.

---

## Part 2 — How to review the next feature

Reviewing here is not "does it compile". It is **does it respect the invariants
in §1.5, and does it close the specific failure modes this codebase has repeatedly
hit**. Use this process for every feature.

### Step 0 — Gather the artifacts (5 min)
- [ ] The plan doc exists in `ai/` (e.g. `*_PLAN.md`) and its slices map 1:1 to commits.
- [ ] `git log --oneline -15` — commits are small, scoped, and conventional
      (`feat(scope)`, `fix(scope)`, `docs`, `test`).
- [ ] Build + tests pass:
      ```bash
      ./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest
      ```
- [ ] Working tree clean; no leftover debug logs/temp files.

### Step 1 — Trace the change through the layers (15 min)
For each file in the diff, identify its layer and check the direction of
dependencies:

| Layer | Question to answer |
|---|---|
| `feature/*` | Does the Screen only read a `UiState` and dispatch events? Any business logic in composables? |
| `domain/*` | Did interfaces change? Are all implementors updated? Any Android framework types leaking in? |
| `data/*` | Are IO/DB calls on the right dispatcher? Errors mapped to `AppResult`? |
| `core/*` | Does it respect the single-engine / theme / insets invariants? |
| `di/*` | New dependencies provisioned? Scope correct (`@Singleton` vs per-screen)? |

Red flags: a Screen importing `OkHttp`/`Room`/`litertlm`, a repository touching
Compose, a composable calling `runBlocking`, a new `Engine` instantiation.

### Step 2 — Apply the per-concern checklist

#### A. State & lifecycle (MVI)
- [ ] Exactly one `UiState` per screen; all fields immutable/`data class`.
- [ ] Collection uses `collectAsStateWithLifecycle` (not `collectAsState`) for
      screen-level flows — **and values read via `.value` inside composables are
      flagged** (past bug: `totalSizeBytes.value` and `loadedModelId` read as
      plain properties never trigger recomposition — `4aec2c2`).
- [ ] No composable-local state for anything that must survive rotation or
      navigation (past bug: chat draft lost on rotation, `fb3702c`).
- [ ] LaunchedEffect keys are correct; no collector accumulating per
      navigation (past bug: `observeMessages` launched per `openChatInternal`
      without cancelling the previous job, `bc46887`).
- [ ] One-shot events (snackbars) are consumed, not re-shown on recomposition.

#### B. Concurrency & streaming
- [ ] Streaming text is **replaced**, never appended; deltas accumulate in one place.
- [ ] `CancellationException` is rethrown in every `catch`; cancellation never
      converted into an error state.
- [ ] Long-running `Flow.collect` bodies guard against **out-of-order responses**:
      later requests cancel earlier ones (past bug: `ModelListViewModel.fetch()`
      launches uncancelled jobs, so a slow old response can overwrite a new search
      — `2c93d6b`, still open).
- [ ] No unbounded sequential network calls in a loop (past bug: one HEAD request
      per file on details load).
- [ ] Engine access goes through `ModelManager.withEngine`; no shared mutable
      session across chats without rebuild (`rebuildSession` on open/edit).

#### C. Persistence & migrations
- [ ] If entities changed: version bumped, `Migration` added, **and tested against
      a populated database** (not just a fresh install).
- [ ] Multi-statement writes are `@Transaction` (past bug: `clearAllChats()` runs
      two DELETEs without a transaction → orphaned `messages` rows, `0b9407f`).
- [ ] No silent `fallbackToDestructiveMigration` on user data.
- [ ] DAO queries return `Flow` where the UI needs to observe; writes on IO.

#### D. UI: insets, theme, and responsiveness
- [ ] Nested Scaffolds zero their `contentWindowInsets`; no manual
      `navigationBarsPadding()` stacked on Scaffold padding.
- [ ] Bottom bars (download bar, input bar) clear the nav bar + IME
      (`navigationBarsPadding()`/`imePadding()` on the bar itself only).
- [ ] System bars derive color from the resolved `ColorScheme` (works in dark
      mode, dynamic color, and forced-dark).
- [ ] Colors/type from the theme; strings formatted via `core/ui/Formatters`.
- [ ] All states explicit: loading, empty, error (+ Retry), content.
- [ ] Lists use stable `key`s; heavy work debounced (search = 500 ms).

#### E. Downloads & storage
- [ ] Storage pre-checked with headroom before enqueue.
- [ ] Unique WorkManager name per unit of work; cancel cleans partial files.
- [ ] Re-entry rebinds to existing work (`observeExisting`) instead of restarting.
- [ ] Single-download enforcement still holds if `DownloadManager` changed.
- [ ] Files are not deleted while an engine may have them open.

#### F. Correctness of the domain claim
- [ ] Does the feature's *promise* actually work end-to-end? (Past gap: the
      details screen advertises `.task`/`.tflite` downloads, but the engine only
      loads `.litertlm` — a download-able-but-unrunnable model, `e396b86`.)
- [ ] Error paths produce a usable message, not a blank screen (past bug: a
      cancelled download wrote into top-level `error`, turning the details screen
      into a full-screen error page).

### Step 3 — Review tests honestly
- [ ] New ViewModel/repository logic has unit tests with **fakes over the real
      seams** (see `ModelDetailsViewModelTest`: fake repository + mocked use case).
- [ ] Tests cover the failure and cancel paths, not just the happy path (past gap:
      no test for resume-on-init or cancel side effects — `1fdf06a`).
- [ ] Assertions match the state machine (e.g. `CHECKING_SIZE → DOWNLOADING →
      COMPLETED/FAILED`), not just "did not throw".
- [ ] Known untested surfaces are stated explicitly: `ModelDownloadWorker`,
      migrations, chat ViewModel (C8) are the standing gaps.

### Step 4 — Check docs and dead code
- [ ] Plan doc updated with what actually shipped (including deviations —
      S2/S3 shipped without the planned Cancel button and slider steps and the
      plan was marked complete anyway).
- [ ] `ai/CHANGES.md` row added for the slice.
- [ ] No newly-dead code: replaced composables removed, unused params dropped
      (past leftovers: `onManageModels`/`onDownloadedModels` still passed but
      unused; `ifEmpty { liteRtFiles }` fallback copied 6× although it can never
      trigger; `lastStreamedLength` written, never read).
- [ ] Duplicated helpers consolidated rather than copied (past pattern:
      `formatBytes`/`formatDate` re-implemented in `DownloadedModelsScreen` and
      `ModelListScreen` after `core/ui/Formatters` was introduced).

### Step 5 — Verdict template
Leave the review in this shape so findings are actionable and comparable:

```markdown
## Review: <feature/slice> (<commit range>)
- **Verdict:** approve / approve-with-nits / request-changes
- **Invariants checked:** engine ☐ · theme ☐ · insets ☐ · MVI ☐ · migration ☐
- **Findings:**
  - [blocker] <file:line> — <what is wrong, why it breaks an invariant>
  - [major]   <file:line> — <bug or data loss risk>
  - [nit]     <file:line> — <style/dead code/duplication>
- **Untested:** <what a follow-up test should cover>
- **Docs:** plan/CHANGES updated? yes/no
```

### Standing backlog to check against every review
These are known, still-open issues; if a new change touches the same code, it
should fix them rather than layer on top:

1. **No catalog pagination** — results cap at 100 (`HuggingFaceApi.listModels`).
2. **Search keyboard never dismisses** on IME Search (`BrowseContent`).
3. **No out-of-order guard** in `ModelListViewModel.fetch()`.
4. **`.task`/`.tflite` models downloadable but unrunnable** by the LiteRT-LM engine.
5. **Cancelled messages render as "streaming" forever** (`isComplete=false` →
   `isStreaming=true` in `toUi()`).
6. **`regenerate()` doesn't rebuild the session** — old assistant turn stays in
   the KV context, diverging from Room.
7. **Font-scale slider has no `steps`/`onValueChangeFinished`** — continuous DataStore
   writes during drag; `lineHeight` not scaled with `fontSize`.
8. **`clearAllChats()` not transactional**; dialog uses a fully-qualified
   `TextButton`.
9. **Dead/duplicated code** listed in Step 4.
10. **Missing test surfaces**: worker, migrations, chat ViewModel (C8).

---

## Quick reference

```bash
# Build & test
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest

# Review a change
git show <hash> --stat      # scope
git show <hash>             # diff
git log --oneline -15       # commit hygiene

# Where to look first for a feature
git diff main...HEAD -- app/src/main/java/com/example/tinymodels/<layer>/
```

| If the change touches… | Read first |
|---|---|
| Engine / loading / RAM | `core/inference/ModelManager.kt`, `core/common/MemoryUtils.kt` |
| Streaming / cancel / regenerate | `core/inference/ConversationSession.kt`, `feature/chat/ChatViewModel.kt` |
| DB schema | `core/database/TinyModelsDatabase.kt`, `Migrations.kt`, `di/DatabaseModule.kt` |
| Downloads | `domain/usecase/model/*`, `data/worker/ModelDownloadWorker.kt` |
| Catalog / search | `core/network/HuggingFaceApi.kt`, `data/repository/ModelRepositoryImpl.kt` |
| Layout / insets / bars | `feature/navigation/TinyModelsApp.kt`, the screen itself |
| Theme / fonts | `core/ui/theme/Theme.kt`, `Type.kt`, `MainActivity.kt` |
| Settings persistence | `data/repository/SettingsRepositoryImpl.kt`, `SettingsViewModel.kt` |
