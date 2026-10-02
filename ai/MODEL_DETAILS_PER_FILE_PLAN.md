# Model Details — Bottom Bar Fix + Per-File Download

> Two issues to fix:
> 1. The sticky download `bottomBar` in `ModelDetailsScreen` is hidden behind the
>    system navigation bar.
> 2. The download currently downloads **all** runtime files as a package and
>    shows the entire repo size (`usedStorage`). Instead, each file should be
>    individually downloadable: user taps a file → bottom bar shows that file's
>    size + download button → only that one file is downloaded.

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
- `Scaffold` has no `contentWindowInsets` zeroing and the `bottomBar` Surface
  has no `navigationBarsPadding()` → the bar renders behind the gesture nav bar.
- `FilesCard` is informational only — no selection, no per-file size, no per-file
  download. A repo like `gemma-4-2b` has multiple `.litertlm` variants (G5, G6,
  q8, etc.) but the download grabs **all** of them and shows the **total repo
  size** (e.g. 36 GB) even though the user only needs one ~1.4 GB file.

---

## Target design

```
ModelDetailsScreen (Scaffold, contentWindowInsets = 0)
├── topBar: TopAppBar
├── bottomBar: FileDownloadBar (only visible when a file is selected)
│   └── Shows: file name, file size, Download button / progress / Cancel
└── content: LazyColumn
    └── FilesCard (interactive)
        └── Each file row: radio/checkbox icon + file name + size + status badge
            ├── Not downloaded → tap to select → bottom bar appears
            ├── Downloading → progress shown inline + in bottom bar
            ├── Downloaded → green check, tap to deselect
            └── Selected (not yet downloaded) → highlighted, bottom bar shows
```

### Key behavioral changes
1. **No more "Download All" button.** The bottom bar is empty until a file is
   selected. When a file is tapped, the bar slides in showing that file's name,
   size, and a Download button.
2. **Per-file download.** `DownloadModelUseCase` gains `executeFile(model, fileName,
   fileSize)` that enqueues a worker for a **single** file. The existing
   `execute(model, total)` is kept for backward compat but the UI only uses the
   per-file path.
3. **Per-file tracking in Room.** `DownloadedModelEntity` PK changes from
   `modelId` to `modelId + fileName` (composite key) so multiple files from the
   same repo can be downloaded independently. DB migrates v4 → v5.
4. **Per-file size.** On detail page load, fire HEAD requests for each runtime
   file in parallel (background, non-blocking) and update the UI as sizes arrive.
   Each file row shows "—" until its size is known, then "1.43 GB".
5. **Inference unaffected.** `ModelManager.loadModel(modelId, modelFile, ...)`
   already takes a specific `File`. The chat flow that resolves which file to load
   will pick the first downloaded `.litertlm` file for the model (existing
   behavior preserved).

---

## Schema / migration

### `DownloadedModelEntity` — composite PK

```kotlin
@Entity(
    tableName = "downloaded_models",
    primaryKeys = ["modelId", "fileName"]  // was: @PrimaryKey val modelId
)
data class DownloadedModelEntity(
    val modelId: String,
    val fileName: String,           // NEW — the specific downloaded file
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val localPath: String,          // directory (unchanged)
    val sizeBytes: Long,            // this file's size (was: sum of all files)
    val downloadedAt: Long
)
```

- `files` column removed (each entity = one file).
- **Migration v4 → v5:** For each existing row, split `files` by newline and
  create one row per file with `sizeBytes` divided equally (or 0 — the exact
  per-file size is unknown for legacy downloads, so we set 0 and let the UI
  show "Unknown" — the file still works for inference).

### `DownloadedModel` domain model

```kotlin
data class DownloadedModel(
    val modelId: String,
    val fileName: String,          // NEW
    val author: String?,
    val libraryName: String?,
    val pipelineTag: String?,
    val localPath: String,
    val sizeBytes: Long,
    val downloadedAt: Long
)
```

- `files: List<String>` removed (was the newline-split list).
- Callers that used `model.files` → use `model.fileName` (single file).
- `DownloadedModelsViewModel.totalSizeBytes` — sum of all entities (still works,
  now per-file entities).
- Downloaded list UI — group by `modelId` so the user sees one card per model
  with the count of downloaded files, not one card per file.

---

## Slices (each committed separately)

### Slice F1 — Fix bottom bar insets (quick win)
**Goal:** The sticky download bar renders above the system nav bar, not behind it.

Files:
- `feature/models/screens/ModelDetailsScreen.kt`:
  - `Scaffold`: add `contentWindowInsets = WindowInsets(0, 0, 0, 0)`.
  - `DownloadBar` `Surface`: add `Modifier.navigationBarsPadding()` so the bar's
    background extends to the edge but content sits above the gesture bar.
  - Also add `imePadding()` for safety when keyboard is visible.

**Safety:** Pure UI inset fix — no logic change.
- **Commit:** `fix(models): F1 — bottom download bar respects navigation bar insets`

---

### Slice F2 — Room: composite PK + migration v4→v5
**Goal:** Schema supports multiple downloaded files per model repo.

Files:
- `core/database/entities/DownloadedModelEntity.kt`:
  - Add `fileName: String` field.
  - Change `@PrimaryKey val modelId` → `primaryKeys = ["modelId", "fileName"]`.
  - Remove `files: String` column.
- `core/database/TinyModelsDatabase.kt`: bump `version = 4` → `5`.
- `core/database/Migrations.kt` (new or existing):
  - `MIGRATION_4_5`: for each row, split `files` by `\n`, insert one row per file
    with `fileName = <split piece>`, `sizeBytes = 0` (unknown for legacy), copy
    other columns.
- `core/database/DownloadedModelDao.kt`:
  - `observeAll()`: unchanged (returns all per-file rows).
  - `getById(modelId)`: change to `getByModelId(modelId): List<...>` (returns
    multiple rows).
  - `insert()`: unchanged (now inserts per-file).
  - `delete(modelId)`: change to `delete(modelId, fileName)` for single-file
    delete; add `deleteAllForModel(modelId)` for deleting all files of a model.
  - Add `observeByModelId(modelId): Flow<List<...>>` for the details screen to
    check which files are already downloaded.
- `domain/model/Model.kt`: `DownloadedModel` — replace `files: List<String>`
  with `fileName: String`.
- `data/repository/ModelRepositoryImpl.kt`:
  - `observeDownloadedModels()`: map entities → `DownloadedModel` with `fileName`.
  - `getDownloadedModel(modelId)`: return first matching entity (or a list).
  - `deleteDownloadedModel(modelId)`: delete all rows for that modelId.
  - Add `deleteDownloadedFile(modelId, fileName)`.
  - Add `observeDownloadedFiles(modelId): Flow<List<String>>` → returns fileNames.
- `feature/models/DownloadedModelsViewModel.kt`:
  - `delete(model)`: use `deleteDownloadedFile(model.modelId, model.fileName)`.
  - `totalSizeBytes`: sum of all per-file entities (unchanged logic).
- Update any callers that reference `DownloadedModel.files` (grep + fix).

**Safety:** Migration is additive (splits existing data). All existing downloaded
models continue to work. The inference path that looks up a downloaded model
by `modelId` still works (returns the first file).
- **Commit:** `feat(db): F2 — composite PK for per-file downloads + migration v5`

---

### Slice F3 — DownloadModelUseCase: per-file download
**Goal:** The use case can enqueue + observe a single-file download.

Files:
- `domain/usecase/model/DownloadModelUseCase.kt`:
  - Add `executeFile(model: ModelDetails, fileName: String, fileSize: Long):
    Flow<DownloadState>` — enqueues a worker with `KEY_FILES = [fileName]` and
    `KEY_TOTAL_BYTES = fileSize`. Unique work name =
    `model_download_${modelId}_${fileName}` so multiple files can download in
    parallel without collision.
  - Add `observeExistingFile(modelId, fileName): Flow<DownloadState>?` —
    resume a per-file download on re-entry.
  - Add `cancelFile(modelId, fileName)`.
  - Add `calculateFileSize(model, fileName): Long` — single HEAD request.
  - Keep existing `execute(model, total)` for backward compat.
- `data/worker/ModelDownloadWorker.kt`:
  - `doWork()`: when inserting the `DownloadedModelEntity`, use
    `fileName = files.first()` (single file), `sizeBytes = target.length()`.
  - Already handles single-file (it loops over `files`, one file = one iteration).
  - Add `KEY_FILE_NAME` to the work data for the unique-work naming.

**Safety:** Existing `execute()` still works. New method is additive.
- **Commit:** `feat(download): F3 — per-file download use case + worker update`

---

### Slice F4 — ModelDetailsViewModel: per-file state + sizes
**Goal:** ViewModel tracks file selection, per-file sizes, per-file download state.

Files:
- `feature/models/ModelDetailsViewModel.kt`:
  - `UiState`:
    - Add `fileSizes: Map<String, Long>` — per-file size (from HEAD requests).
    - Add `downloadedFiles: Set<String>` — which files are already downloaded
      (from Room, via `observeDownloadedFiles(modelId)`).
    - Add `selectedFile: String?` — the file the user tapped.
    - Add `fileDownload: DownloadState` — download state for the selected file.
  - `init`:
    - After model loads, fire parallel HEAD requests for each `runtimeFile`
      via `downloadModel.calculateFileSize(model, fileName)` — update
      `fileSizes` as each resolves (non-blocking, lazy UI update).
    - Observe `observeDownloadedFiles(modelId)` → update `downloadedFiles`.
  - `onFileSelected(fileName)`:
    - If already downloaded → deselect (or no-op).
    - If downloading → bind to existing flow (`observeExistingFile`).
    - Else → set `selectedFile = fileName`, check if a download is already
      running (`observeExistingFile`), set `fileDownload` accordingly.
  - `onDownloadClick()`:
    - Get `selectedFile` + its size from `fileSizes` (or 0 if unknown).
    - Storage pre-check.
    - Set `CHECKING_SIZE` → `executeFile(model, fileName, size)` → stream.
  - `onCancelDownload()`: `cancelFile(modelId, fileName)`.
  - Remove old `isDownloaded` (whole-model) → replaced by per-file `downloadedFiles`.

**Safety:** The old `onDownloadClick()` (download-all) is removed from the UI
but the use case method stays. No existing test breaks if we update the test's
expectations.
- **Commit:** `feat(models): F4 — ViewModel per-file selection, sizes, download state`

---

### Slice F5 — ModelDetailsScreen: interactive files + per-file bottom bar
**Goal:** UI overhaul of the Files card + bottom bar.

Files:
- `feature/models/screens/ModelDetailsScreen.kt`:
  - `Scaffold`: `contentWindowInsets = WindowInsets(0, 0, 0, 0)` (from F1).
  - `bottomBar`: replace `DownloadBar` with `FileDownloadBar`:
    - Only visible when `uiState.selectedFile != null`.
    - Shows: file name (truncated), file size (from `fileSizes` or "Unknown"),
      Download button (idle) / progress bar + % + speed + Cancel (downloading) /
      "Downloaded ✓" (completed).
    - `Modifier.navigationBarsPadding()` on the Surface.
  - `FilesCard` (rewritten):
    - Title: "Files"
    - Subtitle: "Tap a file to download it individually."
    - Each file row:
      - RadioButton (selected = `selectedFile == fileName`).
      - File name (bodyMedium, ellipsis).
      - File size (labelSmall, from `fileSizes[fileName]` or "—" if pending).
      - Status badge:
        - Downloaded → green "✓ Downloaded" chip.
        - Downloading → small linear progress + % inline.
        - Not downloaded → nothing (or "Not downloaded" label).
      - `onClick`: `viewModel.onFileSelected(fileName)`.
    - Highlight selected row with `surfaceContainerHighest`.
  - `StatsCard`:
    - "Size" stat → show `usedStorage` as "Repo size" (clarify it's the whole repo).
    - "Files" stat → count of `runtimeFiles.size`.
    - Add "Downloaded" stat → `downloadedFiles.size`.
  - Remove old `DownloadBar`, `DownloadIdle`, `DownloadInProgress` composables
    (replaced by `FileDownloadBar`).

**Safety:** No navigation change. `onBack` still works.
- **Commit:** `feat(models): F5 — interactive per-file selection + per-file download bar`

---

### Slice F6 — Downloaded models list: group by modelId
**Goal:** Since `DownloadedModelEntity` is now per-file, the downloaded list must
group multiple files under one card.

Files:
- `feature/models/DownloadedModelsViewModel.kt`:
  - `models: StateFlow<List<DownloadedModel>>` → `models: StateFlow<List<DownloadedFileModel>>`
    where `DownloadedFileModel` = `modelId + author + fileCount + totalSize +
    downloadedAt + fileName(first) + pipelineTag`.
  - Or: keep `List<DownloadedModel>` and group in the UI.
- `feature/models/screens/ModelsTabScreen.kt`:
  - `DownloadedContent`: group `downloadedModels` by `modelId` → show one card per
    model with "N files · total size" instead of one card per file.
  - `DownloadedModelCard`: show file count badge, total size, first file name.
  - Delete: delete ALL files for that modelId (or show a dialog to pick which file).
- `feature/models/screens/DownloadedModelsScreen.kt` (standalone):
  - Same grouping.

**Safety:** `delete()` logic changes to `deleteAllForModel(modelId)` when deleting
from the grouped card.
- **Commit:** `feat(models): F6 — downloaded list groups per-file entities by model`

---

### Slice F7 — Inference: load correct downloaded file
**Goal:** When starting a chat, the engine loads the specific downloaded file.

Files:
- Find where `ModelManager.loadModel` is called (chat screen / chat ViewModel).
- Currently it probably finds the model directory + picks the first `.litertlm`.
  With per-file downloads, the `DownloadedModel` now has `fileName` — use it to
  construct `File(localPath, fileName)`.
- If the caller used `downloadedModel.files.first()` → change to
  `File(downloadedModel.localPath, downloadedModel.fileName)`.

**Safety:** Only changes the file-resolution logic. Engine load is unchanged.
- **Commit:** `feat(inference): F7 — load the specific downloaded file for chat`

---

## Migration / safety summary

| Change | Migration? | Risk |
|--------|-----------|------|
| Bottom bar insets | No | Pure UI |
| `DownloadedModelEntity` composite PK | **Yes — v4→v5** | Splits existing rows; legacy downloads preserved |
| `DownloadedModel.files` → `fileName` | No (domain model) | Callers updated via grep |
| `DownloadModelUseCase.executeFile` | No | Additive; old `execute` kept |
| ViewModel per-file state | No | New fields with defaults |
| Files card interactive | No | UI only |
| Downloaded list grouping | No | UI grouping of existing data |
| Inference file resolution | No | Uses `fileName` instead of `files[0]` |

**Key safety principles:**
- Migration v4→v5 is non-destructive (splits rows, copies data).
- Existing downloaded models still work after migration (file list preserved).
- The old download-all `execute()` method is kept but unused by the UI.
- Inference path changes minimally (uses `fileName` instead of `files.first()`).
- Each slice is independently buildable + committable.
