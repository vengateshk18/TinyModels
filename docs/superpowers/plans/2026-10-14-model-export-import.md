# Model Export/Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add export/import functionality for `.litertlm` model files — users can export downloaded models to external storage (Downloads folder) and import `.litertlm` files from external storage into the app.

**Architecture:** 
- Add `exportModel`/`importModel` to `ModelRepository` interface and `ModelRepositoryImpl`
- Use Android Storage Access Framework (SAF) via `ACTION_OPEN_DOCUMENT_TREE` for export destination and `ACTION_OPEN_DOCUMENT` for import source
- File operations run on IO dispatcher; UI launches system pickers via `ActivityResultContracts`
- Export copies model directory to user-selected folder; import copies `.litertlm` file to app's internal models directory and registers in Room

**Tech Stack:** Kotlin, Android Storage Access Framework (SAF), Room, Coroutines, Hilt DI, Compose `rememberLauncherForActivityResult`

**Spec:** This plan.

## Global Constraints

- Min SDK 26, Target SDK 37 — SAF fully supported
- No new permissions needed (`MANAGE_EXTERNAL_STORAGE` not required with SAF)
- Export destination: user-selected folder (e.g., Downloads); import source: user-selected `.litertlm` file
- Must preserve model metadata (author, pipelineTag, etc.) on export/import
- No duplicate models on import — detect existing by modelId + fileName

## Review Focus

| Input / Condition | Expected Behavior |
|-------------------|-------------------|
| Export model with multiple `.litertlm` files | All files copied to destination folder under `modelId/` subfolder |
| Import `.litertlm` file with existing modelId | Skip or prompt — don't create duplicate DB entries |
| Cancel system picker | No crash; UI returns to previous state |
| Import non-`.litertlm` file | Show error "Invalid file type" |
| Export to restricted folder | Graceful error "Cannot write to this location" |

---

### Task 1: Add Export/Import to ModelRepository Interface

**Files:**
- Modify: `app/src/main/java/com/example/tinymodels/domain/repository/ModelRepository.kt`

**Interfaces:**
- Produces: `exportModel(modelId: String, destination: Uri): AppResult<Uri>`, `importModel(sourceUri: Uri): AppResult<DownloadedModel>`

- [x] **Step 1: Write failing test** — compilation error on missing methods
- [x] **Step 2: Add `exportModel` and `importModel` signatures to interface**
- [x] **Step 3: Run test to verify it compiles**

---

### Task 2: Implement Export/Import in ModelRepositoryImpl

**Files:**
- Modify: `app/src/main/java/com/example/tinymodels/data/repository/ModelRepositoryImpl.kt`
- Test: `app/src/test/java/com/example/tinymodels/data/repository/ModelRepositoryImplExportImportTest.kt` (new)

**Interfaces:**
- Consumes: `Context`, `ContentResolver`, `DownloadedModelDao`, `ModelFileDao`, `DownloadedModelEntity`, `ModelFileEntity`

- [x] **Step 1: Write failing test** — `ModelRepositoryImplImportTest` covers import guards (invalid type rejected, duplicates rejected; RED verified by disabling validation)
- [x] **Step 2: Implement `exportModel`** — copy model directory to SAF destination using framework `DocumentsContract` (no new dependency)
- [x] **Step 3: Implement `importModel`** — read `.litertlm` from SAF source, copy to internal storage, insert DB rows
- [x] **Step 4: Run test to verify it passes** — 149/149 unit tests green

---

### Task 3: Import UI in SettingsScreen + Per-File Export in DownloadedFileDetailScreen

**Ruling (user directive):** export-all in Settings was replaced — export is per-file
from the downloaded file's detail screen; Settings keeps import only.

**Files:**
- Modify: `app/src/main/java/com/example/tinymodels/feature/settings/SettingsScreen.kt` (import row only)
- Modify: `app/src/main/java/com/example/tinymodels/feature/settings/SettingsViewModel.kt` (`importModel` only)
- Modify: `app/src/main/java/com/example/tinymodels/domain/repository/ModelRepository.kt` (`exportModelFile(modelId, fileName, destinationUri)`)
- Modify: `app/src/main/java/com/example/tinymodels/data/repository/ModelRepositoryImpl.kt` (single-file SAF copy)
- Modify: `app/src/main/java/com/example/tinymodels/feature/models/DownloadedFileDetailViewModel.kt` (`exportFile`, `isExporting`, `exportMessage`)
- Modify: `app/src/main/java/com/example/tinymodels/feature/models/screens/DownloadedFileDetailScreen.kt` (Export button + folder picker + snackbar)
- Removed now-unused `getDownloadedModels()` (repo + `DownloadedModelDao.getAll()`)

- [x] **Step 1: Write failing test** — UI shows export/import buttons in Settings
- [x] **Step 2: Add `rememberLauncherForActivityResult` for export (folder picker) and import (file picker)**
- [x] **Step 3: Add Export Models / Import Model buttons in SettingsScreen** — superseded: Export button on `DownloadedFileDetailScreen`, Import row in Settings
- [x] **Step 4: Handle results, call repository, show success/error feedback** — via snackbar + persistable URI permissions

---

### Task 4: Add "Imported" Tag & File Availability Check

**Files:**
- Modify: `app/src/main/java/com/example/tinymodels/domain/model/DownloadedModelFile.kt` (add `isImported` field)
- Modify: `app/src/main/java/com/example/tinymodels/core/database/entities/ModelFileEntity.kt` (add `isImported` column)
- Modify: `app/src/main/java/com/example/tinymodels/data/repository/ModelRepositoryImpl.kt` (set `isImported` on import)
- Modify: `app/src/main/java/com/example/tinymodels/feature/models/screens/DownloadedModelsScreen.kt` (show "Imported" tag)
- Modify: `app/src/main/java/com/example/tinymodels/feature/models/DownloadedModelsViewModel.kt` (filter/check file availability)

**Interfaces:**
- Consumes: `DownloadedModelFile.isImported`, `ModelFileEntity.isImported`

- [x] **Step 1: Add `isImported` boolean to `DownloadedModelFile` and `ModelFileEntity`**
- [x] **Step 2: Run migration** — `MIGRATION_9_10` adds column with default `false`, registered in `DatabaseModule`, DB version 10
- [x] **Step 3: Set `isImported = true` on import in `ModelRepositoryImpl`**
- [x] **Step 4: Show "Imported" badge in DownloadedModelsScreen for imported files**
- [x] **Step 5: Add file availability check** — `DownloadedModelsViewModel` filters out rows whose `File(localPath, fileName)` no longer exists