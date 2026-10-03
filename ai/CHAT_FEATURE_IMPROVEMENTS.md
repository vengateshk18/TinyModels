# Chat Feature Improvements Plan

## Overview

This plan addresses **4 critical issues** in the chat feature of the TinyModels app:

1. **Model Loading Progress** - No progress indicator when loading models
2. **Unnecessary Session Creation** - Sessions created even without chatting
3. **Proper Model Cleanup** - Model stays loaded, causing RAM bloat
4. **Inference Session Settings** - No UI to change inference parameters

---

## Current State Analysis

### Issue 1: Model Loading Progress

**Problem:** When entering chat, the model loads silently without any progress indicator. Users don't know if:
- The model is loading
- How long it will take
- If it's stuck or failed

**Root Cause:** `ChatViewModel.loadModel()` runs synchronously without exposing progress to the UI.

**Files Affected:**
- `feature/chat/ChatViewModel.kt`
- `feature/chat/ChatScreen.kt`
- `core/inference/ModelManager.kt`

---

### Issue 2: Unnecessary Session Creation

**Problem:** Every time the user navigates to model detail, a new chat session is created even if they never start chatting.

**Root Cause:** `ChatViewModel` creates session in `init {}` block or on `onModelSelected()` without checking if user actually wants to chat.

**Files Affected:**
- `feature/chat/ChatViewModel.kt`
- `data/SessionManager.kt`
- `domain/model/Model.kt`

---

### Issue 3: Proper Model Cleanup

**Problem:** When user exits chat, the model stays loaded in memory, causing RAM usage to spike and stay high.

**Root Cause:** `ChatViewModel` doesn't call `modelManager.unloadModel()` in `onCleared()` or lifecycle callbacks.

**Files Affected:**
- `feature/chat/ChatViewModel.kt`
- `core/inference/ModelManager.kt`

---

### Issue 4: Inference Session Settings

**Problem:** No UI to change inference parameters like temperature, max tokens, context length during the chat session.

**Root Cause:** Settings are only available in the global settings screen, not during active chat session.

**Files Affected:**
- `feature/chat/` (new dialog component needed)
- `data/SettingsRepository.kt`
- `core/inference/ModelManager.kt`

---

## Implementation Plan

### Phase 1: Model Loading Progress Indicator

#### 1.1 Update `ModelManager.kt` to expose loading progress

**File:** `core/inference/ModelManager.kt`

Add progress callback to `loadModel()`:

```kotlin
suspend fun loadModel(
    modelId: String,
    modelFile: File,
    backend: BackendPreference,
    maxNumTokens: Int = DEFAULT_MAX_TOKENS,
    cacheDir: File = context.cacheDir,
    onProgress: ((Float) -> Unit)? = null  // NEW
): AppResult<LoadedModel> {
    onProgress?.invoke(0.2f) // Model file loading
    
    val engine = Engine(EngineConfig(...))
    onProgress?.invoke(0.6f) // Engine initialization
    
    val loadedModel = LoadedModel(engine = engine, modelId = modelId)
    onProgress?.invoke(1.0f) // Complete
    
    return AppResult.Success(loadedModel)
}
```

#### 1.2 Update `ChatViewModel.kt` to handle progress

**File:** `feature/chat/ChatViewModel.kt`

```kotlin
data class ChatUiState(
    val isLoadingModel: Boolean = false,
    val modelLoadProgress: Float = 0f,  // NEW
    val modelLoadError: String? = null,
    // ... existing fields
)

fun loadModel(modelFile: File) {
    viewModelScope.launch {
        _uiState.value = _uiState.value.copy(
            isLoadingModel = true,
            modelLoadProgress = 0f
        )
        
        val result = modelManager.loadModel(
            modelId = modelId,
            modelFile = modelFile,
            backend = settings.defaultBackend,
            onProgress = { progress ->
                _uiState.update { it.copy(modelLoadProgress = progress) }
            }
        )
        
        when (result) {
            is AppResult.Success -> {
                _uiState.update { 
                    it.copy(isLoadingModel = false, modelLoadProgress = 1f) 
                }
            }
            is AppResult.Error -> {
                _uiState.update { 
                    it.copy(
                        isLoadingModel = false,
                        modelLoadError = result.error.message
                    ) 
                }
            }
        }
    }
}
```

#### 1.3 Add Loading Dialog in `ChatScreen.kt`

**File:** `feature/chat/ChatScreen.kt`

```kotlin
@Composable
fun LoadingDialog(
    progress: Float,
    errorMessage: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Prevent dismissal while loading */ },
        title = { Text("Loading Model") },
        text = {
            Column {
                if (errorMessage != null) {
                    Text(
                        errorMessage,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text("Initializing model for inference...")
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            if (errorMessage != null) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    )
}
```

**Usage in ChatScreen:**

```kotlin
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        bottomBar = { ChatInput(uiState = uiState, onSend = viewModel::sendMessage) }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {
            ChatMessages(messages = uiState.messages)
        }
    }
    
    // Loading dialog
    if (uiState.isLoadingModel) {
        LoadingDialog(
            progress = uiState.modelLoadProgress,
            errorMessage = uiState.modelLoadError,
            onDismiss = { viewModel.clearError() }
        )
    }
}
```

**Estimated Time:** 30 minutes

---

### Phase 2: Fix Unnecessary Session Creation

#### 2.1 Update `ChatViewModel.kt` to delay session creation

**File:** `feature/chat/ChatViewModel.kt`

**Remove:** Session creation from `init {}` block

```kotlin
// OLD - Session created on ViewModel construction
init {
    sessionManager.createSession(modelId)
}

// NEW - Session created only when user starts chatting
private var pendingSessionCreation: String? = null

fun onModelSelected(modelId: String) {
    _uiState.value = _uiState.value.copy(modelId = modelId)
    // Don't create session yet - wait for actual chat
}

fun onStartChat() {
    val modelId = _uiState.value.modelId ?: return
    
    // Create session now that user is actively chatting
    viewModelScope.launch {
        val sessionId = sessionManager.createSession(modelId)
        _uiState.update { it.copy(sessionId = sessionId) }
    }
}
```

#### 2.2 Update `SessionManager.kt` to track session state

**File:** `data/SessionManager.kt`

```kotlin
data class Session(
    val id: String,
    val modelId: String,
    val createdAt: Long,
    val lastMessageAt: Long?,  // NEW: Track if any messages sent
    val messageCount: Int = 0  // NEW
)

fun createSession(modelId: String): String {
    val session = Session(
        id = UUID.randomUUID().toString(),
        modelId = modelId,
        createdAt = System.currentTimeMillis(),
        lastMessageAt = null,
        messageCount = 0
    )
    
    sessionsRepository.save(session)
    return session.id
}

fun hasActiveMessages(sessionId: String): Boolean {
    val session = getSession(sessionId) ?: return false
    return session.messageCount > 0
}
```

#### 2.3 Update Model.kt to track chat status

**File:** `domain/model/Model.kt`

```kotlin
data class ModelDetails(
    // ... existing fields
    val hasActiveChat: Boolean = false  // NEW
) {
    val canStartChat: Boolean
        get() = !hasActiveChat || messageCount == 0
}
```

#### 2.4 Update UI to show "Start Chat" button

**File:** `feature/models/screens/ModelDetailsScreen.kt`

```kotlin
@Composable
fun ModelDetailHeader(model: ModelDetails, uiState: ModelDetailsUiState) {
    // ... existing code
    
    // NEW: Start Chat button in bottom bar
    if (!uiState.isChatActive) {
        TextButton(
            onClick = { viewModel.onStartChat() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Chat, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Chat")
        }
    } else {
        TextButton(
            onClick = { /* Navigate to chat */ },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Chat, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Continue Chat")
        }
    }
}
```

**Estimated Time:** 45 minutes

---

### Phase 3: Proper Model Cleanup

#### 3.1 Update `ChatViewModel.kt` to unload model on exit

**File:** `feature/chat/ChatViewModel.kt`

```kotlin
override fun onCleared() {
    super.onCleared()
    
    // IMPORTANT: Unload model to free RAM
    viewModelScope.launch {
        modelManager.unloadModel()
    }
    
    // Also save session if needed
    val sessionId = _uiState.value.sessionId
    if (sessionId != null) {
        viewModelScope.launch {
            sessionManager.updateLastMessageAt(sessionId)
        }
    }
}

// Also add explicit unload method
fun unloadModel() {
    viewModelScope.launch {
        modelManager.unloadModel()
        _uiState.update { 
            it.copy(
                isModelLoaded = false,
                isLoadingModel = false,
                modelLoadProgress = 0f
            ) 
        }
    }
}
```

#### 3.2 Update `ModelManager.kt` to properly unload

**File:** `core/inference/ModelManager.kt`

```kotlin
suspend fun unloadModel(): AppResult<Unit> {
    return try {
        synchronized(this) {
            currentModel?.let { model ->
                model.engine.close()  // Close engine
                currentModel = null
                _modelState.value = ModelState.UNLOADED
            }
        }
        AppResult.Success(Unit)
    } catch (e: Exception) {
        AppResult.Error(InferenceError.UnloadFailed(e.message))
    }
}

// Add to InferenceError sealed class
sealed class InferenceError {
    // ... existing errors
    data class UnloadFailed(val message: String?) : InferenceError()
}
```

#### 3.3 Add confirmation dialog for quick unload

**File:** `feature/chat/ChatScreen.kt`

```kotlin
@Composable
fun UnloadModelDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unload Model") },
        text = { 
            Text("This will free up RAM. You'll need to reload the model to chat again.") 
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Unload")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

**Usage:**

```kotlin
// Add to top app bar
TopAppBar(
    title = { Text("Chat") },
    actions = {
        IconButton(onClick = { showUnloadDialog = true }) {
            Icon(Icons.Default.Clear, contentDescription = "Unload Model")
        }
    }
)

if (showUnloadDialog) {
    UnloadModelDialog(
        onConfirm = {
            viewModel.unloadModel()
            showUnloadDialog = false
        },
        onDismiss = { showUnloadDialog = false }
    )
}
```

**Estimated Time:** 45 minutes

---

### Phase 4: Inference Session Settings

#### 4.1 Create `InferenceSettingsDialog.kt`

**File:** `feature/chat/InferenceSettingsDialog.kt` (NEW)

```kotlin
package com.example.tinymodels.feature.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class InferenceSettings(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val contextLength: Int = 2048
)

@Composable
fun InferenceSettingsDialog(
    currentSettings: InferenceSettings,
    onSave: (InferenceSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var temperature by remember { mutableFloatStateOf(currentSettings.temperature) }
    var maxTokens by remember { mutableIntStateOf(currentSettings.maxTokens) }
    var contextLength by remember { mutableIntStateOf(currentSettings.contextLength) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Inference Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Temperature slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Temperature", modifier = Modifier.weight(1f))
                    Text("%.1f".format(temperature))
                }
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0.0f..2.0f
                )
                
                // Max tokens
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Max Tokens", modifier = Modifier.weight(1f))
                    Text("$maxTokens")
                }
                Slider(
                    value = maxTokens.toFloat(),
                    onValueChange = { maxTokens = it.toInt() },
                    valueRange = 64f..2048f,
                    steps = 63
                )
                
                // Context length
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Context Length", modifier = Modifier.weight(1f))
                    Text("$contextLength")
                }
                Slider(
                    value = contextLength.toFloat(),
                    onValueChange = { contextLength = it.toInt() },
                    valueRange = 512f..4096f,
                    steps = 6
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    InferenceSettings(
                        temperature = temperature,
                        maxTokens = maxTokens,
                        contextLength = contextLength
                    )
                )
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

#### 4.2 Update `SettingsRepository.kt` to persist settings

**File:** `data/SettingsRepository.kt`

```kotlin
data class InferenceSettingsData(
    val defaultTemperature: Float = 0.7f,
    val defaultMaxTokens: Int = 512,
    val defaultContextLength: Int = 2048
)

// Add per-model settings
data class ModelInferenceSettings(
    val modelId: String,
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val contextLength: Int = 2048
)

// Save per-model settings
suspend fun saveModelInferenceSettings(settings: ModelInferenceSettings) {
    // Implementation using DataStore or Room
}

// Load per-model settings
suspend fun getModelInferenceSettings(modelId: String): ModelInferenceSettings {
    // Implementation
}
```

#### 4.3 Update `ChatViewModel.kt` to use session settings

**File:** `feature/chat/ChatViewModel.kt`

```kotlin
data class ChatUiState(
    // ... existing fields
    val inferenceSettings: InferenceSettings = InferenceSettings(),
    val showInferenceSettings: Boolean = false
)

fun toggleInferenceSettings() {
    _uiState.update { it.copy(showInferenceSettings = !it.showInferenceSettings) }
}

fun saveInferenceSettings(settings: InferenceSettings) {
    viewModelScope.launch {
        val modelSettings = ModelInferenceSettings(
            modelId = modelId,
            temperature = settings.temperature,
            maxTokens = settings.maxTokens,
            contextLength = settings.contextLength
        )
        settingsRepository.saveModelInferenceSettings(modelSettings)
        
        _uiState.update { it.copy(inferenceSettings = settings) }
        
        // Reload model with new settings if already loaded
        if (_uiState.value.isModelLoaded) {
            viewModelScope.launch {
                modelManager.updateInferenceSettings(
                    modelId = modelId,
                    maxTokens = settings.maxTokens,
                    contextLength = settings.contextLength
                )
            }
        }
    }
}

fun sendMessage(prompt: String) {
    viewModelScope.launch {
        val settings = _uiState.value.inferenceSettings
        
        val result = modelManager.generate(
            prompt = prompt,
            maxTokens = settings.maxTokens,
            temperature = settings.temperature
        )
        
        // ... handle result
    }
}
```

#### 4.4 Update `ChatScreen.kt` to show settings button

**File:** `feature/chat/ChatScreen.kt`

```kotlin
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Settings button in top bar
    TopAppBar(
        title = { Text("Chat") },
        actions = {
            IconButton(onClick = { viewModel.toggleInferenceSettings() }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    )
    
    // Settings dialog
    if (uiState.showInferenceSettings) {
        InferenceSettingsDialog(
            currentSettings = uiState.inferenceSettings,
            onSave = { settings -> viewModel.saveInferenceSettings(settings) },
            onDismiss = { viewModel.toggleInferenceSettings() }
        )
    }
    
    // ... rest of screen
}
```

**Estimated Time:** 1.5 hours

---

### Phase 5: Testing & Polish

#### 5.1 Test Cases

| Test Case | Steps | Expected Result |
|-----------|-------|-----------------|
| **Loading Progress** | Enter chat with large model | Progress dialog shows 0% → 100% |
| **Session Creation** | Enter model detail without chatting | No session created in database |
| **Session Creation** | Click "Start Chat" then send message | Session created only after clicking |
| **Model Cleanup** | Exit chat screen | Model unloaded, RAM usage drops |
| **Model Cleanup** | Click unload button during chat | Confirmation dialog appears |
| **Settings Persistence** | Change temperature to 0.9 | Setting saved, used in next generation |
| **Settings Persistence** | Reload app, enter same model | Previous settings loaded |

#### 5.2 Performance Metrics

- **Model Loading Time:** Measure from 0% → 100% progress
- **Memory Usage:** Before/after unload (should drop by 500MB-2GB)
- **Session Count:** Should only have sessions with actual messages
- **Token Generation Rate:** Show during chat (tokens/second)

---

## Commit Strategy

### Slice 1: Model Loading Progress

```bash
git checkout -b feat/model-loading-progress
# Update ModelManager.kt
# Update ChatViewModel.kt
# Update ChatScreen.kt
git add feature/chat/ core/inference/
git commit -m "feat(chat): add model loading progress indicator"
```

### Slice 2: Fix Session Creation

```bash
git checkout -b fix/session-creation
# Update ChatViewModel.kt
# Update SessionManager.kt
# Update ModelDetailsScreen.kt
git add feature/chat/ data/ domain/model/
git commit -m "fix(chat): create session only when user starts chatting"
```

### Slice 3: Model Cleanup

```bash
git checkout -b feat/model-cleanup
# Update ChatViewModel.kt
# Update ModelManager.kt
git add feature/chat/ core/inference/
git commit -m "feat(chat): unload model on exit to free RAM"
```

### Slice 4: Inference Settings

```bash
git checkout -b feat/inference-settings
# Create InferenceSettingsDialog.kt
# Update SettingsRepository.kt
# Update ChatViewModel.kt
# Update ChatScreen.kt
git add feature/chat/ data/
git commit -m "feat(chat): add inference settings dialog"
```

---

## Risk Analysis

### High Risk
- **Model Unloading:** If model unload fails, could leave orphaned engine
- **Mitigation:** Add error handling, fallback to crash recovery

### Medium Risk  
- **Session Creation:** Could create duplicate sessions if not careful
- **Mitigation:** Check existing session before creating new one

### Low Risk
- **Inference Settings:** New UI component, unlikely to cause crashes
- **Mitigation:** Test edge cases (min/max values, invalid input)

---

## Success Criteria

✅ **Phase 1:** Users see loading dialog with progress bar during model load  
✅ **Phase 2:** No empty sessions created without actual chat activity  
✅ **Phase 3:** RAM usage drops after exiting chat  
✅ **Phase 4:** Settings persist across app restarts  
✅ **All Phases:** Zero crashes, smooth UX, no memory leaks  

---

## Timeline

| Phase | Estimated Time | Actual Time | Status |
|-------|----------------|-------------|--------|
| **Phase 1** | 30 min | - | ⏳ Pending |
| **Phase 2** | 45 min | - | ⏳ Pending |
| **Phase 3** | 45 min | - | ⏳ Pending |
| **Phase 4** | 1.5 hours | - | ⏳ Pending |
| **Phase 5** | 1 hour | - | ⏳ Pending |
| **Total** | ~5 hours | - | ⏳ Pending |

---

## Dependencies

- **External:** Google LiteRT-LM API (for model loading)
- **Internal:** `ModelManager`, `SessionManager`, `SettingsRepository`
- **None blocking** - All features are self-contained

---

## Notes

- **Memory Management:** Model size varies from 200MB-2GB depending on model
- **User Experience:** Progress dialog should be dismissible only on error
- **Backwards Compatibility:** Existing sessions should remain unchanged
- **Testing:** Use large model (>1GB) to properly test loading progress

---

## Next Steps

1. ✅ Review this plan
2. ⏳ Implement Phase 1 (Model Loading Progress)
3. ⏳ Implement Phase 2 (Fix Session Creation)
4. ⏳ Implement Phase 3 (Model Cleanup)
5. ⏳ Implement Phase 4 (Inference Settings)
6. ⏳ Test all phases thoroughly
7. ⏳ Deploy to production

---

**Created:** 2024-01-22  
**Last Updated:** 2024-01-22  
**Status:** Ready for Implementation
