# Model Details — Per-File Download + Downloaded File Detail Screen

> Two issues to fix + two new features:
> 1. The sticky download `bottomBar` in `ModelDetailsScreen` is hidden behind the
>    system navigation bar.
> 2. The download currently downloads **all** runtime files as a package and shows
>    the entire repo size (`usedStorage`). Instead, each file should be individually
>    downloadable: user taps a file → bottom bar shows that file's size + download
>    button → only that one file is downloaded.
> 3. A model has many files; store each file individually in a **subtable** so that
>    when the user clicks download for a particular file it is already tracked in
>    the local DB. The model is the parent; the model files are children.
> 4. When a file is already downloaded, re-entering the model detail page should
>    NOT show a download button — it should say "Already downloaded — see
>    Downloaded section". Tapping it navigates to a **new Downloaded File Detail
>    screen** showing local data (size, date, path) with **Delete** and **Start
>    chat** actions. This screen is also reachable from the Downloaded list.

---

## Current state

```
ModelDetailsScreen (Scaffold)
├── topBar: TopAppBar
├── bottomBar: DownloadBar (sticky)
│   └── Shows total usedStorage (entire repo) + downloads ALL runtimeFiles
└── content: LazyColumn
    └── FilesCard — lists files as read-only rows (green check icon, no sizes)
```

**Download flow:**
`onDownloadClick()` → `DownloadModelUseCase.execute(model, total)` →
`ModelDownloadWorker` downloads ALL `runtimeFiles` → stores one
`DownloadedModelEntity` (PK = modelId, files = newline-joined).

**Problems:**
- `Scaffold` has no `contentWindowInsets` zeroing and the `bottomBar` Surface has
  no `navigationBarsPadding()` → the bar renders behind the gesture nav bar.
- `FilesCard` is informational only — no selection, no per-file size, no per-file
  download. A repo like `gemma-4-2b` has multiple `.litertlm` variants but the
  download grabs **all** of them and shows the **total repo size** (e.g. 36 GB).
- No per-file status tracking. No way to see which specific file is downloaded.
- No screen to view / manage an already-downloaded file.

---

## Target design

```
ModelDetailsScreen (Scaffold, contentWindowInsets = 0)
├── topBar: TopAppBar
├── bottomBar: FileDownloadBar (only visible when a NOT_DOWNLOADED file is selected)
│   └── Shows: file name, file size, Download button / progress / Cancel
└── content: LazyColumn
    └── FilesCard (interactive)
        └── Each file row: radio icon + file name + size + status badge
            ├── NOT_DOWNLOADED → tap to select → bottom bar appears with Download
            ├── DOWNLOADING → progress shown inline + in bottom bar (Cancel)
            ├── DOWNLOADED → "Already downloaded — see Downloaded section";
            │                tap → navigate to DownloadedFileDetailScreen
            └── FAILED → error + Retry

DownloadedFileDetailScreen (new, pushed)
├── topBar: TopAppBar (back button, "Downloaded file")
└── content: Column
    ├── Info card: file name, model id, size, downloaded date, local path,
    │   pipeline tag, author, library
    ├── "Start chat" button → loads this file into ModelManager → navigates
    │   to a new chat session
    └── "Delete" button → deletes file from disk + DB row → pops back

Downloaded tab (in ModelsTabScreen)
└── Tapping a model card → DownloadedFileDetailScreen (same screen)
```

### Key behavioral changes
1. **Pre-register files on page load.** When the model detail page loads, all
   `runtimeFiles` are inserted into the `model_files` subtable with status
   `NOT_DOWNLOADED` (if they don't already exist). Download just updates the
   status to `DOWNLOADING` → `DOWNLOADED` / `FAILED`.
2. **Per-file download.** `DownloadModelUseCase` gains `executeFile(model,
   fileName, fileSize)` that enqueues a worker for a **single** file. Progress
   flows via WorkManager → notification + ViewModel → UI.
3. **Resume on re-entry.** Re-entering the model detail page observes any
   in-flight per-file WorkManager job and binds to its progress. If the file is
   already `DOWNLOADED`, no download button is shown.
4. **Downloaded file detail screen.** New screen showing local data + Delete +
   Start chat. Reached from both the Files card (tap a downloaded file) and the
   Downloaded list (tap a model card).
5. **Inference.** `ModelManager.loadModel(modelId, modelFile, ...)` already takes
   a specific `File`. The Start chat button constructs
   `File(localPath, fileName)` and calls `loadModel`.

---

## Schema / migration

### New subtable: `model_files` (parent = `downloaded_models`)

```kotlin
@Entity(
    tableName = "model_files",
    primaryKeys = ["modelId", "fileName"]
)
data class ModelFileEntity(
    val modelId: String,          // FK → downloaded_models.modelId
    val fileName: String,          // e.g. "model_G5.litertlm"
    val status: String,            // NOT_DOWNLOADED | DOWNLOADING | DOWNLOADED | FAILED
    val sizeBytes: Long,           // per-file size (from HEAD or actual)
    val localPath: String?,       // set when DOWNLOADED (directory path)
    val error: String?             // set when FAILED
)
```

### `downloaded_models` (parent — now lightweight metadata)

The existing `downloaded_models` table keeps the model-level metadata. But since
we now track per-file status in `model_files`, the parent table is populated
**pre-emptively** when a file is pre-registered (so it exists even before any
download starts). We change the schema:

```kotlin
@Entity(tableName = "downloaded_models")
data class DownloadedModelEntity(
    @PrimaryKey val modelId: String,
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val localPath: String,        // directory (set on first pre-register)
    val downloadedAt: Long         // set on first pre-register, not on download
)
```

- `files` column and `sizeBytes` column removed from parent (now per-file in
  `model_files`).
- `downloadedAt` on parent = when the model was first visited (pre-registered).
- Actual download time is tracked per-file in `model_files` (add a
  `downloadedAt: Long?` column there if needed — but `downloadedAt` on parent is
  fine for display).

**Actually — simpler approach:** Keep `downloaded_models` as-is (no schema
change needed for it). Only add the new `model_files` table. The parent is
populated lazily (on first pre-register). `files` and `sizeBytes` on the parent
become stale but are not used by the new UI (which reads from `model_files`).
We remove the `files` and `sizeBytes` columns in the migration to avoid
confusion.

### Migration v4 → v5

1. Create `model_files` table.
2. For each existing `downloaded_models` row:
   - Split `files` by `\n`.
   - For each split piece, insert a `model_files` row with
     `status = DOWNLOADED`, `sizeBytes = 0`, `localPath = parent.localPath`.
3. Drop `files` and `sizeBytes` columns from `downloaded_models`
   (Room ALTER TABLE — add new columns for SQLite 3.35+ or use the
   create-new-table-and-copy pattern).

### `DownloadedModel` domain model

```kotlin
data class DownloadedModel(
    val modelId: String,
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val localPath: String,
    val downloadedAt: Long
)
```

- `files: List<String>` and `sizeBytes: Long` removed (now per-file).
- Callers that used `model.files` or `model.sizeBytes` → read from `model_files`.

### New domain model: `DownloadedModelFile`

```kotlin
data class DownloadedModelFile(
    val modelId: String,
    val fileName: String,
    val status: FileDownloadStatus,
    val sizeBytes: Long,
    val localPath: String?,
    val error: String?
)

enum class FileDownloadStatus { NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, FAILED }
```

---

## Slices (each committed separately)

### Slice F1 — Fix bottom bar insets (quick win)
**Goal:** The sticky download bar renders above the system nav bar.

Files:
- `feature/models/screens/ModelDetailsScreen.kt`:
  - `Scaffold`: add `contentWindowInsets = WindowInsets(0, 0, 0, 0)`.
  - `DownloadBar` `Surface`: add `Modifier.navigationBarsPadding()`.
  - Add `imePadding()` for keyboard safety.

**Safety:** Pure UI inset fix.
- **Commit:** `fix(models): F1 — bottom download bar respects navigation bar insets`

---

### Slice F2 — Room: model_files subtable + migration v4→v5
**Goal:** Schema supports per-file tracking.

Files:
- `core/database/entities/ModelFileEntity.kt` (new):
  - As above.
- `core/database/entities/DownloadedModelEntity.kt`:
  - Remove `files: String` and `sizeBytes: Long` columns.
- `core/database/TinyModelsDatabase.kt`:
  - Add `ModelFileEntity::class` to entities.
  - Bump `version = 4` → `5`.
  - Add `modelFileDao()` abstract fun.
- `core/database/Migrations.kt`:
  - `MIGRATION_4_5`: create `model_files` table; for each existing row, split
    `files` by `\n` → insert per-file rows with `status = DOWNLOADED`;
    drop `files` and `sizeBytes` from `downloaded_models`.
- `core/database/ModelFileDao.kt` (new):
  - `observeByModelId(modelId): Flow<List<ModelFileEntity>>`
  - `observeDownloaded(): Flow<List<ModelFileEntity>>` (all DOWNLOADED files)
  - `getByModelIdAndFile(modelId, fileName): ModelFileEntity?`
  - `insert(entity)` (upsert)
  - `updateStatus(modelId, fileName, status, sizeBytes?, localPath?, error?)`
  - `delete(modelId, fileName)`
  - `deleteAllForModel(modelId)`
- `core/database/DownloadedModelDao.kt`:
  - Remove `getById` (or keep for compat).
  - Add `insertIfAbsent(entity)` — for pre-registering the parent.
- `domain/model/Model.kt`:
  - `DownloadedModel`: remove `files` + `sizeBytes`.
  - Add `DownloadedModelFile` + `FileDownloadStatus`.
- `data/repository/ModelRepositoryImpl.kt`:
  - `observeDownloadedModels()`: unchanged (returns parent rows).
  - Add `observeModelFiles(modelId): Flow<List<DownloadedModelFile>>`.
  - Add `observeDownloadedFiles(): Flow<List<DownloadedModelFile>>` (all DOWNLOADED).
  - Add `preRegisterModel(model: ModelDetails)` — inserts parent + all
    `runtimeFiles` as `NOT_DOWNLOADED` (upsert, won't overwrite existing).
  - Add `updateFileStatus(modelId, fileName, status, sizeBytes?, localPath?, error?)`.
  - Add `deleteModelFile(modelId, fileName)`.
  - `deleteDownloadedModel(modelId)` → `deleteAllForModel(modelId)` + delete
    parent + delete files on disk.
- Update callers that reference `DownloadedModel.files` / `.sizeBytes`.

**Safety:** Migration is additive + splits data. Existing downloads preserved.
- **Commit:** `feat(db): F2 — model_files subtable + migration v5 + per-file DAO`

---

### Slice F3 — DownloadModelUseCase: per-file download + pre-register
**Goal:** Enqueue + observe a single-file download.

Files:
- `domain/usecase/model/DownloadModelUseCase.kt`:
  - Add `executeFile(model: ModelDetails, fileName: String, fileSize: Long):
    Flow<DownloadState>` — enqueues worker with `KEY_FILES = [fileName]`,
    `KEY_TOTAL_BYTES = fileSize`. Unique work name =
    `model_download_${modelId}_${sanitizedFileName}`.
  - Add `observeExistingFile(modelId, fileName): Flow<DownloadState>?`.
  - Add `cancelFile(modelId, fileName)`.
  - Add `calculateFileSize(model, fileName): Long` — single HEAD.
  - Keep existing `execute(model, total)` for backward compat.
- `data/worker/ModelDownloadWorker.kt`:
  - `doWork()`: insert `DownloadedModelEntity` (parent, if absent) + insert
    `ModelFileEntity` with `status = DOWNLOADED`, `sizeBytes = file.length()`,
    `localPath = modelDirectory.absolutePath`.
  - On failure: update `ModelFileEntity.status = FAILED` + error.
  - Already handles single-file (loop over `files`, one file = one iteration).
- `data/repository/ModelRepositoryImpl.kt`:
  - `preRegisterModel(model)`: called by ViewModel on page load.

**Safety:** Existing `execute()` kept. New methods additive.
- **Commit:** `feat(download): F3 — per-file download use case + worker + pre-register`

---

### Slice F4 — ModelDetailsViewModel: per-file state + sizes + resume
**Goal:** ViewModel tracks per-file sizes, statuses, selection, download state.

Files:
- `feature/models/ModelDetailsViewModel.kt`:
  - `UiState`:
    - `fileSizes: Map<String, Long>` — per-file size (from HEAD).
    - `modelFiles: List<DownloadedModelFile>` — from Room `observeModelFiles`.
    - `selectedFile: String?`.
    - `fileDownload: DownloadState`.
  - `init`:
    - After model loads → `preRegisterModel(model)` (inserts NOT_DOWNLOADED rows).
    - Observe `observeModelFiles(modelId)` → `modelFiles`.
    - Fire parallel HEAD requests for each `runtimeFile` → update `fileSizes`.
  - `onFileSelected(fileName)`:
    - If DOWNLOADED → no-op (UI handles navigation to detail screen).
    - If DOWNLOADING → bind to `observeExistingFile`.
    - If NOT_DOWNLOADED → set `selectedFile`, show bottom bar.
  - `onDownloadClick()`:
    - Get `selectedFile` + size.
    - Storage pre-check.
    - `updateFileStatus(modelId, fileName, DOWNLOADING)`.
    - `executeFile(model, fileName, size)` → stream → on success
      `updateFileStatus(DOWNLOADED)`, on failure `updateFileStatus(FAILED)`.
  - `onCancelDownload()`: `cancelFile` + `updateFileStatus(NOT_DOWNLOADED)`.
  - `onDownloadedFileClick(fileName)`: callback to navigate to detail screen.

**Safety:** Old `isDownloaded` replaced by per-file `modelFiles`. Old
`onDownloadClick` (download-all) removed from UI.
- **Commit:** `feat(models): F4 — ViewModel per-file state, sizes, resume, pre-register`

---

### Slice F5 — ModelDetailsScreen: interactive files + per-file bottom bar
**Goal:** UI overhaul of the Files card + bottom bar.

Files:
- `feature/models/screens/ModelDetailsScreen.kt`:
  - `Scaffold`: `contentWindowInsets = WindowInsets(0, 0, 0, 0)` (from F1).
  - `bottomBar`: `FileDownloadBar`:
    - Only visible when `selectedFile != null` AND that file is NOT_DOWNLOADED.
    - Shows: file name, size, Download button / progress / Cancel.
    - `navigationBarsPadding()`.
  - `FilesCard` (rewritten):
    - Title: "Files", subtitle: "Tap a file to download it individually."
    - Each file row (from `modelFiles` joined with `fileSizes`):
      - Radio icon (selected = `selectedFile == fileName`).
      - File name + size (or "—" if pending).
      - Status badge:
        - DOWNLOADED → green "Already downloaded — see Downloaded section".
          Tap → `onDownloadedFileClick(fileName)` (navigate to detail).
        - DOWNLOADING → inline progress + %.
        - FAILED → red error + Retry (sets selected + triggers download).
        - NOT_DOWNLOADED → tap to select → bottom bar appears.
      - Highlight selected row.
  - `StatsCard`: "Repo size" (usedStorage), "Files" (count), "Downloaded"
    (count of DOWNLOADED files).
  - Remove old `DownloadBar`, `DownloadIdle`, `DownloadInProgress`.

**Safety:** No navigation change. `onBack` works.
- **Commit:** `feat(models): F5 — interactive per-file selection + per-file download bar`

---

### Slice F6 — DownloadedFileDetailScreen (new)
**Goal:** New screen showing local data for a downloaded file + Delete + Start chat.

Files:
- `feature/models/screens/DownloadedFileDetailScreen.kt` (new):
  - `DownloadedFileDetailScreen(modelId: String, fileName: String, onStartChat:
    (modelId, file) -> Unit, viewModel: DownloadedFileDetailViewModel)`.
  - TopAppBar with back button, title = file name.
  - Info card: model id, file name, size (from Room), downloaded date, local
    path, pipeline tag, author, library.
  - "Start chat" button:
    - Constructs `File(localPath, fileName)`.
    - Calls `ModelManager.loadModel(modelId, file, backend, maxTokens)`.
    - On success → `onStartChat(modelId, fileName)` → navigates to chat.
    - Shows loading state while engine initializes.
  - "Delete" button:
    - Confirmation dialog.
    - Deletes file from disk + `deleteModelFile(modelId, fileName)`.
    - If no more DOWNLOADED files for this model, delete parent + directory.
    - Pops back.
- `feature/models/DownloadedFileDetailViewModel.kt` (new):
  - Loads `DownloadedModelFile` from Room.
  - `startChat()` → `ModelManager.loadModel`.
  - `delete()` → repository + disk cleanup.
- `feature/navigation/TinyModelsApp.kt`:
  - Add `Routes.DOWNLOADED_FILE_DETAIL` route with `modelId` + `fileName` args.
  - `onStartChat` → navigate to chat room (create new session for this model).
- `domain/repository/ModelRepository.kt`:
  - Add `getModelFile(modelId, fileName): DownloadedModelFile?`.

**Safety:** New screen, new route. No existing route changes.
- **Commit:** `feat(models): F6 — DownloadedFileDetailScreen with Delete + Start chat`

---

### Slice F7 — Downloaded list: navigate to DownloadedFileDetailScreen
**Goal:** Tapping a model card in the Downloaded tab opens the detail screen.

Files:
- `feature/models/screens/ModelsTabScreen.kt`:
  - `DownloadedContent`: `onModelClick` → `onDownloadedFileClick(modelId, fileName)`.
    Since the downloaded list shows models (parents), and a model can have
    multiple downloaded files, tapping a model card navigates to the detail
    screen of the **first** downloaded file (or shows a list if multiple).
    - Simpler: group by `modelId`, show file count, tap → detail screen of first
      downloaded file.
- `feature/models/screens/ModelDetailsScreen.kt`:
  - Files card tap on DOWNLOADED file → `onDownloadedFileClick(modelId, fileName)`
    → navigate to `DownloadedFileDetailScreen`.
- `feature/navigation/TinyModelsApp.kt`:
  - Wire both entry points to the new route.

**Safety:** Navigation only. No data change.
- **Commit:** `feat(models): F7 — wire downloaded list + Files card to detail screen`

---

### Slice F8 — Downloaded list: group by modelId (UI)
**Goal:** Since `DownloadedModelEntity` no longer has `files`/`sizeBytes`, the
Downloaded list groups `model_files` by `modelId`.

Files:
- `feature/models/DownloadedModelsViewModel.kt`:
  - `models: StateFlow<List<DownloadedModel>>` → keep (parent metadata).
  - `downloadedFiles: StateFlow<List<DownloadedModelFile>>` → all DOWNLOADED.
  - `totalSizeBytes` → sum of `downloadedFiles` sizes.
  - `delete(model)` → `deleteDownloadedModel(modelId)` (deletes parent + all
    files + disk).
- `feature/models/screens/ModelsTabScreen.kt`:
  - `DownloadedContent`: group `downloadedFiles` by `modelId`, show one card per
    model with file count + total size.
  - `DownloadedModelCard`: file count badge, total size, pipeline tag, author.
  - Tap → `DownloadedFileDetailScreen` (first file).
- `feature/models/screens/DownloadedModelsScreen.kt` (standalone): same grouping.

**Safety:** UI grouping of existing data.
- **Commit:** `feat(models): F8 — downloaded list groups per-file entities by model`

---

### Slice F9 — Inference: load correct downloaded file
**Goal:** Start chat loads the specific downloaded file.

Files:
- `feature/models/DownloadedFileDetailViewModel.kt`:
  - `startChat()`: `File(localPath, fileName)` → `ModelManager.loadModel`.
- `feature/navigation/TinyModelsApp.kt`:
  - `onStartChat(modelId, fileName)` → create a new chat session (Room) with the
    modelId → navigate to `ChatRoomScreen(chatId)`.
- `feature/chat/ChatRoomViewModel.kt` (or wherever model is resolved):
  - When creating a session, store the `modelId` + `fileName` in the chat entity
    so the chat knows which file to load.

**Safety:** Only changes file-resolution + session creation. Engine unchanged.
- **Commit:** `feat(inference): F9 — start chat loads specific downloaded file`

---

## Migration / safety summary

| Change | Migration? | Risk |
|--------|-----------|------|
| Bottom bar insets | No | Pure UI |
| `model_files` table | **Yes — v4→v5** | New table; existing data split into it |
| `downloaded_models` drop `files`+`sizeBytes` | **Yes — v5** | Columns removed; data migrated to `model_files` |
| `DownloadedModel` domain (remove `files`+`sizeBytes`) | No | Callers updated via grep |
| `DownloadModelUseCase.executeFile` | No | Additive |
| ViewModel per-file state | No | New fields with defaults |
| `DownloadedFileDetailScreen` | No | New screen + route |
| Downloaded list grouping | No | UI grouping |
| Inference file resolution | No | Uses `fileName` |

**Key safety principles:**
- Migration v4→v5 is non-destructive (splits rows, preserves data).
- Existing downloaded models still work after migration (files preserved as
  DOWNLOADED rows in `model_files`).
- Pre-register uses upsert (won't overwrite existing DOWNLOADED rows).
- The old download-all `execute()` method is kept but unused by the UI.
- Each slice is independently buildable + committable.
- Download progress flows via WorkManager → notification (always) + ViewModel
  (when detail page is visible). Re-entering the page resumes observation.
