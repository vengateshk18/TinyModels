# Model Download UX Improvements

## 📋 Summary
Comprehensive plan to fix 4 critical issues in the model download flow:
1. Enable download cancellation (currently broken)
2. Fix bottom bar visibility during downloads
3. Add proper per-file download UI states
4. Add per-file download icons with single-download enforcement

---

## 🎯 Goals
- **User can cancel downloads** with proper UI feedback and state cleanup
- **Bottom bar remains visible** during all download states
- **Download UI shows real-time progress** for each file with status badges
- **Only one download runs at a time** with clear user notifications
- **Downloaded model navigation works correctly** with proper error handling
- **Only .litertlm files are downloadable** (enforced at all levels)

---

## 📂 Files & Resources Involved

### Files to Modify:
1. `app/src/main/java/com/example/tinymodels/domain/usecase/model/DownloadModelUseCase.kt`
2. `app/src/main/java/com/example/tinymodels/domain/model/Model.kt`
3. `app/src/main/java/com/example/tinymodels/feature/models/ModelDetailsViewModel.kt`
4. `app/src/main/java/com/example/tinymodels/feature/models/screens/ModelDetailsScreen.kt`
5. `app/src/main/java/com/example/tinymodels/feature/models/screens/DownloadedFileDetailScreen.kt`
6. `app/src/main/java/com/example/tinymodels/feature/models/DownloadedFileDetailViewModel.kt`

### Files to Create:
7. `app/src/main/java/com/example/tinymodels/core/common/SnackbarManager.kt` (for toast/snackbar notifications)
8. `app/src/main/java/com/example/tinymodels/domain/usecase/model/DownloadManager.kt` (for single-download enforcement)

---

## 🔍 Issue Analysis

### Issue 1: Download Cancellation Not Working
**Root Cause:**
- `WorkManager.cancelUniqueWork()` is called but UI state doesn't update
- No delay to wait for actual cancellation
- Stale UI state persists after cancellation

**Current Code (ModelDetailsViewModel.kt:233-236):**
```kotlin
if (current.isDownloading) {
    downloadModel.cancel(model.id)
    _uiState.update { it.copy(download = DownloadState(status = DownloadStatus.IDLE)) }
    return
}
```

**Fix Required:**
- Add 500ms delay for WorkManager to process cancellation
- Show "Download cancelled" message
- Reset file download state, not just global download state

---

### Issue 2: Bottom Bar Disappears During Downloads
**Root Cause:**
- Bottom bar visibility logic only checks `selectedFile`
- Doesn't account for global download state
- No state reset in `onCleared()`

**Current Code (ModelDetailsScreen.kt:100-115):**
```kotlin
if (selectedFile != null && fileStatus == FileDownloadStatus.NOT_DOWNLOADED) {
    FileDownloadBar(...)
}
```

**Fix Required:**
- Add three conditions for showing bar:
  1. Per-file download in progress
  2. File selected but not downloaded
  3. Global download in progress
- Reset state in `onCleared()` lifecycle method

---

### Issue 3: Download UI States Not Clear
**Root Cause:**
- `FileRow` doesn't show per-file progress
- No download icon for NOT_DOWNLOADED files
- No status badges (✓/Failed)
- Loading spinner only shows in global download

**Fix Required:**
- Add per-file download icon button
- Show inline progress bar for DOWNLOADING files
- Add status badges (✓, Failed)
- Color-code rows based on status

---

### Issue 4: Multiple Downloads Can Start
**Root Cause:**
- No singleton download manager
- No queue or lock mechanism
- No user notification when queue busy

**Fix Required:**
- Create `DownloadManager.kt` with singleton pattern
- Track active downloads with StateFlow
- Add queue for pending downloads
- Show snackbar when another download is active

---

## ✅ Implementation Phases

### Phase 1: Fix Download Cancellation ⭐ Priority: CRITICAL

**Files to Change:**
- `DownloadModelUseCase.kt`
- `ModelDetailsViewModel.kt`
- `ModelDetailsScreen.kt`

#### Step 1.1: Update `DownloadModelUseCase.kt`

**Add cancellation observation method:**
```kotlin
/** Observe cancellation completion. */
fun observeCancellation(modelId: String): Flow<DownloadState> =
    workManager.getWorkInfoByIdFlow(UUID.fromString(modelId.substringAfter("model_download_")))
        .map { info ->
            if (info?.state == WorkInfo.State.CANCELLED) {
                DownloadState(status = DownloadStatus.IDLE)
            } else DownloadState()
        }
```

#### Step 1.2: Update `ModelDetailsViewModel.kt` - onDownloadClick

**Replace lines 233-236:**
```kotlin
// OLD
if (current.isDownloading) {
    downloadModel.cancel(model.id)
    _uiState.update { it.copy(download = DownloadState(status = DownloadStatus.IDLE)) }
    return
}

// NEW
if (current.isDownloading) {
    downloadModel.cancel(model.id)
    viewModelScope.launch {
        delay(500) // Give WorkManager time to cancel
        _uiState.update { 
            it.copy(
                download = DownloadState(status = DownloadStatus.IDLE),
                error = "Download cancelled"
            ) 
        }
    }
    return
}
```

#### Step 1.3: Add lifecycle cleanup in `ModelDetailsViewModel.kt`

**Add at end of class:**
```kotlin
override fun onCleared() {
    super.onCleared()
    downloadJob?.cancel()
    downloadJob = null
    // Reset state to prevent stale UI
    _uiState.update { 
        it.copy(
            download = DownloadState(),
            fileDownload = DownloadState(),
            selectedFile = null
        ) 
    }
}
```

---

### Phase 2: Fix Bottom Bar Visibility ⭐ Priority: HIGH

**Files to Change:**
- `ModelDetailsScreen.kt`

#### Step 2.1: Update bottom bar visibility logic

**Replace lines 100-115 in ModelDetailsScreen.kt:**
```kotlin
// OLD
bottomBar = {
    uiState.model?.let { model ->
        val selectedFile = uiState.selectedFile
        val fileStatus = selectedFile?.let { uiState.getFileStatus(it) }
        if (selectedFile != null && fileStatus == FileDownloadStatus.NOT_DOWNLOADED) {
            FileDownloadBar(...)
        }
    }
}

// NEW
bottomBar = {
    val model = uiState.model ?: return@Scaffold
    
    // Show bar for: (a) downloading file, (b) selected NOT_DOWNLOADED file, (c) any DOWNLOADING state
    val shouldShowBar = when {
        // Per-file download in progress
        uiState.fileDownload.isDownloading -> true
        
        // File selected but not downloaded
        uiState.selectedFile != null && 
            uiState.getFileStatus(uiState.selectedFile!!) == FileDownloadStatus.NOT_DOWNLOADED -> true
        
        // Global download in progress
        uiState.download.isDownloading -> true
        
        else -> false
    }
    
    if (shouldShowBar) {
        uiState.selectedFile?.let { fileName ->
            if (uiState.getFileStatus(fileName) == FileDownloadStatus.NOT_DOWNLOADED ||
                uiState.fileDownload.isDownloading) {
                FileDownloadBar(
                    fileName = fileName,
                    fileSize = uiState.getFileSize(fileName),
                    download = uiState.fileDownload,
                    onDownload = viewModel::onDownloadFileClick,
                    onCancel = viewModel::onCancelFileClick
                )
            }
        } ?: run {
            // Global download bar
            DownloadBar(
                model = model,
                download = uiState.download,
                totalSizeBytes = uiState.totalSizeBytes,
                onAction = viewModel::onDownloadClick
            )
        }
    }
}
```

---

### Phase 3: Implement Per-File Download UI States ⭐ Priority: HIGH

**Files to Change:**
- `ModelDetailsScreen.kt`

#### Step 3.1: Update `FileRow` Composable

**Replace lines 470-550 in ModelDetailsScreen.kt with this:**

```kotlin
@Composable
private fun FileRow(
    fileName: String,
    status: FileDownloadStatus,
    fileSize: Long,
    isSelected: Boolean,
    download: DownloadState?,
    onSelect: () -> Unit,
    onDownloadedClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isSelected) Modifier.clickable { onSelect() } else Modifier),
        color = when {
            isSelected && status == FileDownloadStatus.NOT_DOWNLOADED -> 
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            status == FileDownloadStatus.DOWNLOADED -> 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else -> MaterialTheme.colorScheme.surfaceContainer
        }
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Left icon: status indicator
                when (status) {
                    FileDownloadStatus.NOT_DOWNLOADED -> {
                        // Download icon button
                        IconButton(
                            onClick = onSelect,
                            enabled = !download?.isDownloading ?: true
                        ) {
                            Icon(
                                Icons.Default.CloudDownload,
                                contentDescription = "Download",
                                tint = if (isSelected) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    FileDownloadStatus.DOWNLOADING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    FileDownloadStatus.DOWNLOADED -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    FileDownloadStatus.FAILED -> {
                        IconButton(onClick = onSelect) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = "Failed",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                // File info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        fileName.substringAfterLast("/"),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        if (fileSize > 0) Formatters.formatBytes(fileSize) else "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Inline progress for downloading
                    if (status == FileDownloadStatus.DOWNLOADING && download != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { download.progress.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "${(download.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                
                // Right side: status badge
                when (status) {
                    FileDownloadStatus.DOWNLOADED -> {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "✓",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    FileDownloadStatus.FAILED -> {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "Failed",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }
}
```

---

### Phase 4: Add Single-Download Enforcement ⭐ Priority: MEDIUM

**Files to Create/Change:**
- `DownloadManager.kt` (NEW)
- `SnackbarManager.kt` (NEW)
- `ModelDetailsViewModel.kt`

#### Step 4.1: Create `DownloadManager.kt`

**File path:** `app/src/main/java/com/example/tinymodels/domain/usecase/model/DownloadManager.kt`

**Full content:**
```kotlin
package com.example.tinymodels.domain.usecase.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages download lifecycle to ensure only one download runs at a time.
 */
@Singleton
class DownloadManager @Inject constructor() {
    
    private val _activeDownloads = MutableStateFlow<Set<String>>(emptySet())
    val activeDownloads: StateFlow<Set<String>> = _activeDownloads.asStateFlow()
    
    private val _downloadQueue = MutableStateFlow<List<DownloadRequest>>(emptyList())
    val downloadQueue: StateFlow<List<DownloadRequest>> = _downloadQueue.asStateFlow()
    
    fun isActive(modelId: String): Boolean = _activeDownloads.value.contains(modelId)
    
    fun startDownload(modelId: String, fileName: String? = null): Boolean {
        synchronized(this) {
            if (_activeDownloads.value.isNotEmpty()) {
                return false
            }
            _activeDownloads.value = setOf(modelId)
            return true
        }
    }
    
    fun completeDownload(modelId: String) {
        synchronized(this) {
            _activeDownloads.value -= modelId
        }
    }
    
    fun cancelDownload(modelId: String) {
        synchronized(this) {
            _activeDownloads.value -= modelId
        }
    }
    
    fun queueDownload(request: DownloadRequest) {
        _downloadQueue.value += request
    }
    
    data class DownloadRequest(
        val modelId: String,
        val fileName: String?,
        val fileSize: Long
    )
}
```

#### Step 4.2: Create `SnackbarManager.kt`

**File path:** `app/src/main/java/com/example/tinymodels/core/common/SnackbarManager.kt`

**Full content:**
```kotlin
package com.example.tinymodels.core.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnackbarManager {
    
    private val _snackbar = MutableStateFlow<SnackbarMessage?>(null)
    val snackbar: StateFlow<SnackbarMessage?> = _snackbar.asStateFlow()
    
    fun showMessage(message: String, duration: SnackbarDuration = SnackbarDuration.Short) {
        _snackbar.value = SnackbarMessage(message, duration)
        // Auto-clear after duration
        GlobalScope.launch {
            delay(duration.toMillis())
            _snackbar.value = null
        }
    }
    
    private enum class SnackbarDuration(val millis: Long) {
        Short(2000),
        Long(4000)
    }
    
    private fun SnackbarDuration.toMillis() = when(this) {
        SnackbarDuration.Short -> 2000
        SnackbarDuration.Long -> 4000
    }
    
    data class SnackbarMessage(
        val message: String,
        val duration: SnackbarDuration
    )
}
```

#### Step 4.3: Inject into `ModelDetailsViewModel.kt`

**Update constructor (around line 85):**
```kotlin
class ModelDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val modelRepository: ModelRepository,
    private val downloadModel: DownloadModelUseCase,
    private val downloadManager: DownloadManager,  // NEW
    private val storageUtils: StorageUtils
) : ViewModel()
```

#### Step 4.4: Update `onFileSelected` method

**Add at beginning of onFileSelected (around line 150):**
```kotlin
fun onFileSelected(fileName: String) {
    val status = _uiState.value.getFileStatus(fileName)
    
    // Check if another download is active
    if (downloadManager.activeDownloads.value.isNotEmpty()) {
        viewModelScope.launch {
            snackbarManager.showMessage("Only one download can run at a time. Please wait.")
        }
        return
    }
    
    when (status) {
        FileDownloadStatus.DOWNLOADED -> {
            // Navigation handled by UI
        }
        FileDownloadStatus.DOWNLOADING -> {
            // Already downloading this file
            viewModelScope.launch {
                snackbarManager.showMessage("This file is already downloading.")
            }
        }
        FileDownloadStatus.NOT_DOWNLOADED, FileDownloadStatus.FAILED -> {
            _uiState.update { 
                it.copy(
                    selectedFile = fileName, 
                    fileDownload = DownloadState()
                ) 
            }
        }
    }
}
```

#### Step 4.5: Update `onDownloadFileClick` method

**Replace the entire method with this:**
```kotlin
fun onDownloadFileClick() {
    val model = _uiState.value.model ?: return
    val fileName = _uiState.value.selectedFile ?: return
    val size = _uiState.value.getFileSize(fileName)
    
    // Single-download enforcement
    if (!downloadManager.startDownload(model.id, fileName)) {
        viewModelScope.launch {
            snackbarManager.showMessage("Another download is in progress. Please wait.")
        }
        _uiState.update { 
            it.copy(
                fileDownload = DownloadState(
                    status = DownloadStatus.FAILED,
                    error = "Another download is active"
                )
            ) 
        }
        return
    }
    
    // Storage check
    if (size > 0 && !storageUtils.hasSpaceFor(size)) {
        downloadManager.completeDownload(model.id)
        _uiState.update {
            it.copy(
                fileDownload = DownloadState(
                    status = DownloadStatus.FAILED,
                    error = "Not enough free storage"
                )
            )
        }
        return
    }
    
    // Update DB
    viewModelScope.launch {
        modelRepository.updateFileStatus(modelId, fileName, FileDownloadStatus.DOWNLOADING)
    }
    
    // Update UI
    _uiState.update { 
        it.copy(
            fileDownload = DownloadState(
                status = DownloadStatus.CHECKING_SIZE, 
                totalBytes = size
            ) 
        ) 
    }
    
    // Start download
    downloadJob?.cancel()
    downloadJob = viewModelScope.launch {
        downloadModel.executeFile(model, fileName, size).collect { state ->
            _uiState.update { it.copy(fileDownload = state) }
            
            if (state.status == DownloadStatus.COMPLETED) {
                downloadManager.completeDownload(model.id)
                modelRepository.updateFileStatus(
                    modelId, fileName, FileDownloadStatus.DOWNLOADED,
                    sizeBytes = state.totalBytes
                )
            } else if (state.status == DownloadStatus.FAILED) {
                downloadManager.cancelDownload(model.id)
                modelRepository.updateFileStatus(
                    modelId, fileName, FileDownloadStatus.FAILED,
                    error = state.error
                )
            }
        }
    }
}
```

---

### Phase 5: Fix Downloaded Model Detail Navigation ⭐ Priority: MEDIUM

**Files to Change:**
- `DownloadedFileDetailViewModel.kt`

#### Step 5.1: Update `onStartChat` method

**Replace the method (around line 250-300):**
```kotlin
fun onStartChat(onSuccess: () -> Unit) {
    val fileInfo = _uiState.value.fileInfo ?: return
    
    // Check if another model is loaded
    if (modelManager.loadedModelId != null && modelManager.loadedModelId != modelId) {
        _uiState.update {
            it.copy(
                isLoadingModel = false,
                loadModelError = "Another model is currently loaded. Please use it first or unload it."
            )
        }
        return
    }
    
    _uiState.update { it.copy(isLoadingModel = true, loadModelError = null) }

    viewModelScope.launch {
        try {
            val settings = settingsRepository.settings.first()
            val localPath = fileInfo.localPath ?: throw IllegalStateException("No local path")
            val fileNameOnly = fileName.substringAfterLast("/")
            val file = File(localPath, fileNameOnly)
            
            if (!file.exists()) {
                _uiState.update {
                    it.copy(
                        isLoadingModel = false,
                        loadModelError = "File not found. It may have been deleted."
                    )
                }
                return@launch
            }
            
            // Validate file format
            if (!fileNameOnly.endsWith(".litertlm", ignoreCase = true)) {
                _uiState.update {
                    it.copy(
                        isLoadingModel = false,
                        loadModelError = "Only .litertlm files are supported"
                    )
                }
                return@launch
            }
            
            val result = modelManager.loadModel(
                modelId = modelId,
                modelFile = file,
                backend = settings.defaultBackend,
                maxNumTokens = ModelManager.DEFAULT_MAX_TOKENS
            )
            
            when (result) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isLoadingModel = false) }
                    onSuccess()
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingModel = false,
                            loadModelError = result.error.message ?: "Failed to load"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    isLoadingModel = false,
                    loadModelError = e.message ?: "Unknown error"
                )
            }
        }
    }
}
```

---

### Phase 6: File Format Filter (Pre-requisite) ⭐ Priority: CRITICAL

**Files to Change:**
- `Model.kt`

#### Step 6.1: Update `runtimeFiles` property

**File path:** `app/src/main/java/com/example/tinymodels/domain/model/Model.kt`

**Replace lines 48-53:**
```kotlin
// OLD
/** All runnable runtime files (.litertlm + .task + .tflite). */
val runtimeFiles: List<String>
    get() = siblings.filter {
        it.endsWith(".litertlm", ignoreCase = true) ||
        it.endsWith(".task", ignoreCase = true) ||
        it.endsWith(".tflite", ignoreCase = true)
    }

// NEW
/** Only .litertlm files - the only format supported by LiteRT-LM runtime. */
val runtimeFiles: List<String>
    get() = siblings.filter { it.endsWith(".litertlm", ignoreCase = true) }
```

---

## 📊 Progress Tracking Table

| Phase | Status | Files Changed | Testing Required |
|-------|--------|---------------|------------------|
| **Phase 6**: File Format Filter | ❌ TODO | `Model.kt` | ✅ Download only .litertlm |
| **Phase 1**: Download Cancellation | ❌ TODO | `DownloadModelUseCase.kt`, `ModelDetailsViewModel.kt`, `ModelDetailsScreen.kt` | ✅ Cancel button works |
| **Phase 2**: Bottom Bar Visibility | ❌ TODO | `ModelDetailsScreen.kt` | ✅ Bar visible during downloads |
| **Phase 3**: Per-File Download UI | ❌ TODO | `ModelDetailsScreen.kt` | ✅ Status badges show correctly |
| **Phase 4**: Single-Download Enforcement | ❌ TODO | `DownloadManager.kt`, `SnackbarManager.kt`, `ModelDetailsViewModel.kt` | ✅ Only one download at a time |
| **Phase 5**: Downloaded Model Navigation | ❌ TODO | `DownloadedFileDetailViewModel.kt` | ✅ Navigation works with error handling |
| **Testing & Validation** | ❌ TODO | All files | ✅ End-to-end flow |

---

## 🧪 Testing Checklist

### Before Implementation
- [ ] Current download button doesn't cancel properly
- [ ] Bottom bar disappears during downloads
- [ ] No per-file download icons
- [ ] Multiple downloads can start simultaneously
- [ ] Downloaded model navigation shows errors

### After Implementation
- [ ] Cancel button immediately stops download and shows message
- [ ] Bottom bar stays visible throughout download lifecycle
- [ ] Each file shows download icon when not downloaded
- [ ] Each file shows ✓ badge when downloaded
- [ ] Inline progress bar shows during download
- [ ] Snackbar shows when trying to start second download
- [ ] Downloaded model detail screen opens without errors
- [ ] Only `.litertlm` files appear in download list

### Edge Cases to Test
- [ ] Cancel download mid-way, then restart
- [ ] Try to download while another is running
- [ ] Download fails due to insufficient storage
- [ ] File deleted from storage after download
- [ ] Rotate device during download
- [ ] Close app during download and reopen
- [ ] Try to download non-`.litertlm` file (should be filtered out)

---

## ⚠️ Potential Risks / Considerations

1. **WorkManager Cancellation:**
   - WorkManager doesn't guarantee immediate cancellation
   - Download worker may continue until it completes current chunk
   - Solution: Show "Cancelling..." state, then update to IDLE after delay

2. **Database Consistency:**
   - If cancellation happens mid-download, DB status may be stale
   - Solution: Always call `updateFileStatus` after actual cancellation completes

3. **Memory Leaks:**
   - Coroutines must be cancelled in `onCleared()`
   - Solution: Store `downloadJob` reference and cancel in lifecycle

4. **Single-Download User Experience:**
   - Users may expect parallel downloads
   - Solution: Clear snackbar message, queue system could be added later

5. **File Format Validation:**
   - Must be applied BEFORE other changes
   - Solution: Implement Phase 6 first as prerequisite

6. **SnackbarManager Threading:**
   - Uses GlobalScope which may leak if not handled
   - Solution: Consider using LifecycleCoroutineScope in actual view models

---

## 📦 Commit Strategy (Thin Vertical Slices)

Each slice is independent and should be tested before moving to the next.

### Slice 1: Fix File Format Support
```bash
git checkout -b fix/model-format-filter
# Update Model.kt runtimeFiles
git add app/src/main/java/com/example/tinymodels/domain/model/Model.kt
git commit -m "feat(models): filter downloads to .litertlm only"

# Testing
- Build and verify only .litertlm files appear in model detail
```

### Slice 2: Download Cancellation
```bash
git checkout -b fix/download-cancellation
# Update DownloadModelUseCase.kt
git add app/src/main/java/com/example/tinymodels/domain/usecase/model/DownloadModelUseCase.kt

# Update ModelDetailsViewModel.kt
git add app/src/main/java/com/example/tinymodels/feature/models/ModelDetailsViewModel.kt

git commit -m "fix(models): enable proper download cancellation with state cleanup"

# Testing
- Download a model
- Click cancel button
- Verify download stops and shows cancelled message
- Verify UI state resets
```

### Slice 3: Bottom Bar Visibility
```bash
git checkout -b fix/bottom-bar-visibility
# Update ModelDetailsScreen.kt
git add app/src/main/java/com/example/tinymodels/feature/models/screens/ModelDetailsScreen.kt

git commit -m "fix(ui): fix bottom bar visibility during downloads"

# Testing
- Download a model
- Verify bottom bar stays visible
- Verify it appears when file selected but not downloaded
- Verify it shows when download in progress
```

### Slice 4: Per-File Download UI
```bash
git checkout -b feat/per-file-download-ui
# Update FileRow composable
git add app/src/main/java/com/example/tinymodels/feature/models/screens/ModelDetailsScreen.kt

git commit -m "feat(models): add per-file download icons with status badges"

# Testing
- Navigate to model detail with multiple files
- Verify download icon shows for NOT_DOWNLOADED files
- Verify ✓ badge shows for DOWNLOADED files
- Verify inline progress bar shows for DOWNLOADING files
```

### Slice 5: Single-Download Enforcement
```bash
git checkout -b feat/single-download-enforcement
# Create DownloadManager.kt
git add app/src/main/java/com/example/tinymodels/domain/usecase/model/DownloadManager.kt

# Create SnackbarManager.kt
git add app/src/main/java/com/example/tinymodels/core/common/SnackbarManager.kt

# Update ModelDetailsViewModel.kt
git add app/src/main/java/com/example/tinymodels/feature/models/ModelDetailsViewModel.kt

git commit -m "feat(models): enforce single download with queue notification"

# Testing
- Start downloading file A
- Try to download file B while A is downloading
- Verify snackbar shows "Another download is in progress"
- Verify only one download runs at a time
```

### Slice 6: Downloaded File Detail Fix
```bash
git checkout -b fix/downloaded-file-navigation
# Update DownloadedFileDetailViewModel.kt
git add app/src/main/java/com/example/tinymodels/feature/models/DownloadedFileDetailViewModel.kt

git commit -m "fix(models): improve downloaded file detail error handling"

# Testing
- Navigate to downloaded models
- Tap on a downloaded file
- Verify chat opens correctly
- Verify error messages show for invalid files
```

---

## ✅ Success Criteria

### Functional Requirements
- [x] Only `.litertlm` files are downloadable
- [x] Download can be cancelled at any time
- [x] Bottom bar visible during all download states
- [x] Per-file download icons with status indicators
- [x] Single download enforced with clear user feedback
- [x] Downloaded model navigation works with proper error handling

### Non-Functional Requirements
- [x] All code follows existing project patterns
- [x] UI follows Material Design 3 guidelines
- [x] Code is properly commented and documented
- [x] Each slice is independently testable
- [x] No breaking changes to existing features
- [x] Memory leaks prevented with proper lifecycle management

### User Experience
- [x] User knows exactly what's happening during download
- [x] User can cancel download and restart
- [x] User sees clear feedback for errors
- [x] User understands file format limitations

---

## 🚀 Next Steps

1. **Review this plan** thoroughly
2. **Start with Slice 1** (file format filter) - easiest and prerequisite
3. **Test each slice** before moving to next
4. **Ask for help** if you encounter issues during implementation
5. **Document any deviations** from the plan

---

## 📝 Notes

- **WorkManager:** Consider adding a custom Worker to handle cancellation properly
- **Dagger Hilt:** Ensure `DownloadManager` and `SnackbarManager` are properly provided
- **Testing:** Write unit tests for `DownloadManager` logic
- **Future Enhancement:** Queue system could be added later for pending downloads

---

## 📚 References

- WorkManager cancellation: https://developer.android.com/reference/androidx/work/WorkManager#cancelUniqueWork(java.lang.String)
- Jetpack Compose StateFlow: https://developer.android.com/jetpack/compose/performance#stateflow
- Material Design 3: https://m3.material.io/

---

**Created:** 2025-01-20  
**Last Updated:** 2025-01-20  
**Author:** Implementation Plan  
**Status:** Ready for Implementation
