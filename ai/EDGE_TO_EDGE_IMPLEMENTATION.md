# Edge-to-Edge Implementation Guide

This document provides a comprehensive guide to implementing edge-to-edge display across the TinyModels app. It covers the technical approach, implementation patterns, and code examples for Scaffolds, Bottom Sheets, and Dialogs.

---

## Table of Contents

- [Overview](#overview)
- [Why Edge-to-Edge?](#why-edge-to-edge)
- [Current State](#current-state)
- [Implementation Strategy](#implementation-strategy)
- [Technical Patterns](#technical-patterns)
- [Screen-by-Screen Implementation](#screen-by-screen-implementation)
- [Bottom Sheets & Dialogs](#bottom-sheets--dialogs)
- [Testing Checklist](#testing-checklist)
- [Troubleshooting](#troubleshooting)

---

## Overview

Edge-to-edge display ensures that your app content extends to the physical edges of the screen, while properly respecting system UI elements like the status bar, navigation bar, and display cutouts (notches, punch-holes).

### Key Principles

1. **System bars draw behind content** — Status bar and navigation bar use transparent or matched-color backgrounds
2. **Content respects safe areas** — Text and interactive elements don't hide behind notches or cutouts
3. **Consistent padding strategy** — All screens follow the same pattern for inset handling
4. **Bottom sheets/dialogs handle insets** — Floating UI elements don't overlap system bars

---

## Why Edge-to-Edge?

**User experience benefits:**
- Maximizes screen real estate on modern devices
- Provides a more immersive, native feel
- Matches Material Design 3 guidelines
- Essential for devices with large cutouts or corner displays

**Technical requirements:**
- Android 13+ (API 33) recommends edge-to-edge by default
- Android 14+ (API 34) requires apps to handle display cutouts properly
- Google Play Store guidelines favor edge-to-edge experiences

---

## Current State

### What's Already Done

✅ **`MainActivity.kt`** already calls `enableEdgeToEdge()` on launch  
✅ **`MainActivity.kt`** dynamically adjusts system bar colors on theme changes  
✅ **Main navigation scaffold** uses `contentWindowInsets = WindowInsets(0, 0, 0, 0)`

### What Needs to Be Fixed

❌ **Nested Scaffolds** in screens don't explicitly handle insets  
❌ **Bottom sheets** (`ModalBottomSheet`) don't respect navigation bar padding  
❌ **Display cutouts** (notches) are not handled for horizontal padding  
❌ **Content area** doesn't always add cutout-safe horizontal padding  

**Affected screens (10 total):**
- `HomeScreen.kt`
- `ChatTabScreen.kt`
- `ChatRoomScreen.kt`
- `ModelsTabScreen.kt`
- `ModelListScreen.kt`
- `ModelDetailsScreen.kt`
- `DownloadedModelsScreen.kt`
- `DownloadedFileDetailScreen.kt`
- `SettingsScreen.kt`
- `DeviceInfoScreen.kt`

**Affected bottom sheets (3 total):**
- `FontBottomSheet` (in `SettingsScreen.kt`)
- `InferenceSettingsSheet.kt`
- `ModelPickerSheet` (in `ModelChip.kt`)

**Affected dialogs (2 total):**
- `EditMessageDialog.kt`
- `ModelLoadingDialog.kt`

---

## Implementation Strategy

### WindowInsets Hierarchy

```
MainActivity (enableEdgeToEdge)
  └── TinyModelsApp Scaffold (contentWindowInsets = 0)
      ├── NavigationBar (full-width, no insets)
      └── NavHost content (padding for system bars)
          └── Individual Screen Scaffold
              ├── TopAppBar (full-width, status bar draws behind)
              └── Content area (horizontal cutout padding + vertical insets)
```

### The Golden Rules

1. **Outer Scaffold** (`TinyModelsApp`): `contentWindowInsets = WindowInsets(0, 0, 0, 0)`
   - Navigation bar draws full-width at bottom
   - Status bar draws full-width at top
   
2. **Nested Scaffolds** (individual screens): `contentWindowInsets = WindowInsets(0, 0, 0, 0)`
   - Each screen's top bar takes responsibility for its own insets
   
3. **Content Area Padding**:
   - Add `paddingValues` from Scaffold's `content` parameter
   - Add `WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)` for cutout areas
   - Optional: Add `statusBarsPadding()` for content that should not scroll under status bar

4. **Bottom Sheets**:
   - Add horizontal cutout padding: `WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)`
   - Add bottom navigation bar padding: `WindowInsets.navigationBarsPadding()`
   
5. **Dialogs**: Standard centered dialogs need no special handling

---

## Technical Patterns

### Pattern 1: Screen with Top App Bar

```kotlin
@Composable
fun MyScreen(
    onBack: () -> Unit
) {
    val scrollState = rememberScrollState()
    
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Screen Title") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Scaffold padding
                .padding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)) // Cutout areas
                .verticalScroll(scrollState)
        ) {
            // Screen content here
        }
    }
}
```

### Pattern 2: Screen with Bottom Bar (Download Bar, Input Bar)

```kotlin
@Composable
fun MyScreenWithBottomBar() {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Bottom bar handles its own insets
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding() // Ensure bottom spacing
                    .imePadding() // Avoid keyboard overlap
            ) {
                MyBottomBar()
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
        ) {
            // Content here
        }
    }
}
```

### Pattern 3: Bottom Sheet (ModalBottomSheet)

```kotlin
@Composable
fun MyBottomSheet(
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = false
    )
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        // Sheet content gets horizontal cutout padding
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding() // Bottom spacing
                .verticalScroll(rememberScrollState())
        ) {
            // Sheet content here
        }
    }
}
```

### Pattern 4: Simple Screen without Top Bar

```kotlin
@Composable
fun SimpleScreen() {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
        ) {
            // Full-screen content
        }
    }
}
```

### Pattern 5: Dialog (No Special Handling)

```kotlin
@Composable
fun MyDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dialog Title") },
        text = { Text("Dialog content") },
        confirmButton = {
            TextButton(onClick = { /* action */ }) {
                Text("OK")
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

Standard dialogs are centered and don't need inset handling. Only custom full-width dialogs need special care.

---

## Screen-by-Screen Implementation

### HomeScreen (`feature/home/HomeScreen.kt`)

**Current state:** Uses Scaffold without explicit inset handling  
**Required changes:**
- Add `contentWindowInsets = WindowInsets(0, 0, 0, 0)` to Scaffold
- Wrap content in `Column` with horizontal cutout padding

### ChatTabScreen (`feature/chat/ChatTabScreen.kt`)

**Current state:** Uses Scaffold with `contentWindowInsets = WindowInsets(0, 0, 0, 0)`  
**Required changes:**
- Add horizontal cutout padding to content area
- Ensure FAB respects navigation bar (already has `imePadding()`)

### ChatRoomScreen (`feature/chat/ChatRoomScreen.kt`)

**Current state:** Uses Scaffold with `contentWindowInsets = WindowInsets(0, 0, 0, 0)`  
**Required changes:**
- Add horizontal cutout padding to message list
- Ensure input bar clears navigation bar and IME

### ModelsTabScreen (`feature/models/screens/ModelsTabScreen.kt`)

**Current state:** Uses Scaffold without explicit inset handling  
**Required changes:**
- Add `contentWindowInsets = WindowInsets(0, 0, 0, 0)` to Scaffold
- Add horizontal cutout padding to content area

### ModelListScreen (`feature/models/screens/ModelListScreen.kt`)

**Current state:** Uses Scaffold without explicit inset handling  
**Required changes:**
- Add `contentWindowInsets = WindowInsets(0, 0, 0, 0)` to Scaffold
- Add horizontal cutout padding to list content
- Ensure search bar handles status bar properly

### ModelDetailsScreen (`feature/models/screens/ModelDetailsScreen.kt`)

**Current state:** Uses Scaffold without explicit inset handling  
**Required changes:**
- Add `contentWindowInsets = WindowInsets(0, 0, 0, 0)` to Scaffold
- Add horizontal cutout padding to scrollable content
- Ensure DownloadBar clears navigation bar

### DownloadedModelsScreen (`feature/models/screens/DownloadedModelsScreen.kt`)

**Current state:** Uses Scaffold without explicit inset handling  
**Required changes:**
- Add `contentWindowInsets = WindowInsets(0, 0, 0, 0)` to Scaffold
- Add horizontal cutout padding to list

### DownloadedFileDetailScreen (`feature/models/screens/DownloadedFileDetailScreen.kt`)

**Current state:** Uses Scaffold without explicit inset handling  
**Required changes:**
- Add `contentWindowInsets = WindowInsets(0, 0, 0, 0)` to Scaffold
- Add horizontal cutout padding to content

### SettingsScreen (`feature/settings/SettingsScreen.kt`)

**Current state:** Uses Scaffold without explicit inset handling  
**Required changes:**
- Add `contentWindowInsets = WindowInsets(0, 0, 0, 0)` to Scaffold
- Add horizontal cutout padding to settings list
- Update `FontBottomSheet` (see Bottom Sheets section)

### DeviceInfoScreen (`feature/home/DeviceInfoScreen.kt`)

**Current state:** Uses Scaffold without explicit inset handling  
**Required changes:**
- Add `contentWindowInsets = WindowInsets(0, 0, 0, 0)` to Scaffold
- Add horizontal cutout padding to content

---

## Bottom Sheets & Dialogs

### FontBottomSheet (in SettingsScreen.kt)

**Required changes:**
```kotlin
ModalBottomSheet(
    onDismissRequest = { /* ... */ },
    sheetState = sheetState,
    modifier = Modifier
        .fillMaxWidth()
        .padding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // Font selection content
    }
}
```

### InferenceSettingsSheet (`feature/chat/InferenceSettingsSheet.kt`)

**Required changes:**
```kotlin
ModalBottomSheet(
    onDismissRequest = { /* ... */ },
    sheetState = sheetState,
    modifier = Modifier
        .fillMaxWidth()
        .padding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // Inference settings sliders and toggles
    }
}
```

### ModelPickerSheet (`feature/chat/ModelPickerSheet.kt`)

**Required changes:**
```kotlin
ModalBottomSheet(
    onDismissRequest = { /* ... */ },
    sheetState = sheetState,
    modifier = Modifier
        .fillMaxWidth()
        .padding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp)
            .navigationBarsPadding()
    ) {
        // Model selection list
    }
}
```

### EditMessageDialog (`feature/chat/EditMessageDialog.kt`)

**No changes needed** — Standard `AlertDialog` is already centered and doesn't overlap system bars.

### ModelLoadingDialog (`feature/chat/ModelLoadingDialog.kt`)

**No changes needed** — Standard `AlertDialog` is already centered.

---

## Testing Checklist

### Device Types to Test

- [ ] Device with notch (e.g., iPhone-style cutout)
- [ ] Device with punch-hole camera (e.g., Samsung Galaxy)
- [ ] Device with wide bottom navigation bar (e.g., older Android)
- [ ] Device with gesture navigation (iPhone-like)
- [ ] Tablet with display cutout

### Each Screen Checklist

For every screen after implementation:

**Visual tests:**
- [ ] Content doesn't hide behind notch/cutout on left/right
- [ ] Text is not hidden behind status bar (top)
- [ ] Buttons/cards not hidden behind navigation bar (bottom)
- [ ] Bottom sheet doesn't overlap navigation bar
- [ ] Bottom sheet doesn't overlap keyboard when IME is open

**Functional tests:**
- [ ] Scrolling content works smoothly
- [ ] All buttons are tappable
- [ ] FAB is accessible and not hidden
- [ ] Input field expands properly with IME
- [ ] Dialogs appear centered and readable

**Edge cases:**
- [ ] Rotate device: layout remains correct
- [ ] Split-screen mode: padding adapts
- [ ] Dark mode: system bars match app theme
- [ ] Dynamic color enabled: system bars use theme color

### Quick Manual Test Script

1. **Enable developer options:**
   - Go to Settings → About phone → Tap "Build number" 7 times
   
2. **Force edge-to-edge (Android 12 and below):**
   - Developer options → "Force edge-to-edge" → ON
   
3. **Test on each screen:**
   ```
   Home → Chat list → Chat room → Models → Model details
   → Downloaded models → Settings → Device info
   ```
   
4. **Check each UI element:**
   - Can you read text at the very edges?
   - Can you tap buttons at the bottom?
   - Does content scroll smoothly under the status bar?

---

## Troubleshooting

### Common Issues

#### Issue 1: Content hidden behind notch

**Symptom:** Text or buttons cut off on left/right edges  
**Cause:** Missing horizontal cutout padding  
**Fix:** Add `padding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))`

#### Issue 2: Double padding at bottom

**Symptom:** Large gap between bottom content and navigation bar  
**Cause:** Both Scaffold insets and manual `navigationBarsPadding()` applied  
**Fix:** Set `contentWindowInsets = WindowInsets(0, 0, 0, 0)` and only add manual padding where needed

#### Issue 3: Bottom sheet covers navigation bar

**Symptom:** Sheet content overlaps system navigation buttons  
**Cause:** Missing `navigationBarsPadding()` on sheet content  
**Fix:** Add `navigationBarsPadding()` to the Column inside ModalBottomSheet

#### Issue 4: Input field hidden behind keyboard

**Symptom:** Text field not visible when IME opens  
**Cause:** Missing `imePadding()` on bottom bar or input field  
**Fix:** Add `imePadding()` to the bottom bar or ensure Scaffold handles it

#### Issue 5: Status bar text not visible

**Symptom:** Status bar icons blend into background  
**Cause:** System bar color style not updated with theme  
**Fix:** Ensure `enableEdgeToEdge()` is called with proper `SystemBarStyle` (already done in MainActivity)

### Debug Tools

**Enable display cutout simulation (for testing without physical device):**

```bash
adb shell wm cutout <cutout>
# Options: none, short, tall, double
```

**Force dark mode for testing:**

```bash
adb shell uimode night yes
```

**Check current inset values (for debugging):**

```kotlin
// Add this temporarily to see inset values
val insets = WindowInsets.current
val horizontal = insets.getInsetForSide(WindowInsetsSides.Start) + 
                 insets.getInsetForSide(WindowInsetsSides.End)
Log.d("Insets", "Horizontal cutouts: $horizontal")
```

### Known Limitations

1. **Android 12 and below:** Some devices don't fully support edge-to-edge; use `WindowInsetsCompat` for graceful fallback
2. **Samsung OneUI:** May have additional system UI elements; test on physical Samsung devices
3. **Foldables:** Hinge area may create additional safe areas; Compose handles this automatically with `WindowInsets`

---

## Code Examples Reference

### Utility Extensions (Optional)

If you create shared utilities, consider these extension functions:

```kotlin
// Add to core/ui/components/WindowInsetsHelper.kt

/**
 * Apply horizontal system bar padding (for display cutouts).
 */
fun Modifier.systemBarsHorizontalPadding(): Modifier = this
    .then(padding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)))

/**
 * Apply safe padding for Scaffold content with cutout awareness.
 */
fun Modifier.edgeToEdgeScaffoldPadding(paddingValues: PaddingValues): Modifier = this
    .then(padding(paddingValues))
    .then(padding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)))

/**
 * Apply bottom sheet safe padding (horizontal cutouts + navigation bar).
 */
fun Modifier.bottomSheetSafePadding(): Modifier = this
    .then(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal).let {
        padding(it)
    })
    .then(navigationBarsPadding())
```

---

## Implementation Order

Follow this order for systematic rollout:

1. **Phase 1:** Create shared utilities (optional)
2. **Phase 2:** Update `TinyModelsApp.kt` main scaffold
3. **Phase 3:** Update tab screens (Home, Chat, Models, Settings)
4. **Phase 4:** Update detail screens (ModelDetails, DownloadedFileDetail)
5. **Phase 5:** Update bottom sheets
6. **Phase 6:** Verify all dialogs
7. **Phase 7:** Test on physical devices
8. **Phase 8:** Update this documentation with lessons learned

---

## Related Documentation

- [CHANGES.md](../CHANGES.md) — History of app revamp
- [CODE_REVIEW_GUIDE.md](../CODE_REVIEW_GUIDE.md) — Review checklist (see Section 2.4 for UI/insets)
- [CHAT_UX_REVAMP_PLAN.md](../CHAT_UX_REVAMP_PLAN.md) — Chat screen specific UX improvements

---

## Changelog

| Date | Change | Author |
|------|--------|--------|
| 2024-01-XX | Initial implementation guide | — |

---

*This document should be updated whenever new screens are added or the inset handling strategy changes. Keep it synced with actual implementation.*
