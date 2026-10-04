# Chat Input UI + Conversation/Keyboard Layout — Revamp Plan

Two goals:

1. **Input bar redesign** — a single rounded **rectangle container** holding
   the text field AND both action buttons (Send + Cancel/Stop) inside it,
   WhatsApp-style: one pill/rect, text grows in the middle, buttons live at
   the trailing edge inside the same surface.
2. **Keyboard layout fix** — when the IME opens, the conversation must end
   exactly at the top of the input bar (never hidden behind the keyboard),
   and the input bar must sit directly above the keyboard — like WhatsApp.

---

## Root Cause of the Keyboard Bug (verified in code)

`ChatRoomScreen`'s Scaffold sets:

```kotlin
contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
```

That inset set **excludes `WindowInsets.ime`**. `ChatInputBar` applies
`.imePadding()` to itself, so the input bar does ride above the keyboard —
but the message list's `Box(weight(1f))` is laid out inside the Scaffold's
`padding`, which does NOT shrink for the IME. Result: the list keeps its
full height, and its bottom content (the newest messages) is covered by the
keyboard + input bar. The manifest already has
`android:windowSoftInputMode="adjustResize"` — the window resizes, but the
Compose inset chain is what's broken.

**Fix:** make the IME inset part of the layout chain so the list's available
height shrinks when the keyboard opens:

```kotlin
// ChatRoomScreen — inside Scaffold { padding -> }
Column(
    modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .imePadding()          // ← shrinks the whole column when IME opens
) {
    Box(Modifier.weight(1f)) { MessageListRoom(...) }   // now ends at input bar
    ChatInputBar(...)                                  // sits above keyboard
}
```

and simplify `ChatInputBar`'s own modifiers to `navigationBarsPadding()` only
(imePadding moves to the column so it's applied once, not double-padded).

---

## New Input Bar Design

**Before:** separate rounded text field + separate 48dp FilledIconButton
(Send or Stop, cross-fading) beside it.

**After:** one rounded rectangle (`Surface`, `RoundedCornerShape(28.dp)`,
`surfaceContainerHigh`) containing:

```
┌────────────────────────────────────────────┐
│  Type a message…              [✕]  [➤]     │   ← idle: Cancel hidden/disabled,
└────────────────────────────────────────────┘      Send enabled when text
```

- **Text field**: borderless `BasicTextField`-style (transparent
  `OutlinedTextField` with no border, matching current colors), grows up to
  5 lines / 160dp, placeholder "Message…" / "Load a model to chat".
- **Cancel button**: visible ONLY while generating (Stop icon, error
  container colors) — replaces Send during generation, same as today.
- **Send button**: trailing, always visible when not generating; disabled
  (muted) until text is non-blank and a model is ready.
- Both buttons 40dp inside the rect (48dp rect height keeps the touch
  target ≥ 48dp via minimumInteractiveComponentSize).
- AnimatedContent cross-fade between Send and Stop preserved.

**Why inside the rectangle:** the user asked for "a rectangle within submit
button and cancel button where also I can Type my input" — one container,
all controls inside it, no floating side button.

---

## Slices

### Slice 1 — Keyboard/IME layout fix (the bug)

**Files:** `ChatRoomScreen.kt`, `ChatInputBar.kt`

1. Add `.imePadding()` to the content `Column` in `ChatRoomScreen`
   (after `.padding(padding)`).
2. Remove `.imePadding()` from `ChatInputBar`'s Surface (keep
   `navigationBarsPadding()` — needed when keyboard is closed).
3. Reduce the message list's `contentPadding` bottom from 80dp to 16dp
   (the 80dp was compensating for the old overlap; with the column now
   shrinking, the list naturally ends at the input bar).
4. Keep auto-scroll behavior (`animateScrollToItem(last)`) — with the list
   now correctly sized, the newest message lands just above the input bar.

**Verify:** open a chat with history → tap the input → keyboard opens →
input bar rides above the keyboard AND the last message is fully visible
above the input bar; rotate; send a message with keyboard open.

**Commit:** `Chat: fix conversation hidden behind keyboard (IME insets)`

### Slice 2 — Rectangle input bar redesign

**Files:** `ChatInputBar.kt` (rewrite of the layout only; same public API)

1. Replace the Row[OutlinedTextField + FilledIconButton] with a single
   `Surface(shape = RoundedCornerShape(28.dp), color = surfaceContainerHigh)`
   containing a Row: text field (weight 1f) + Stop/Send buttons inside.
2. Buttons: 40dp `IconButton`s (unfilled, tinted) inside the rect;
   Send uses `Icons.AutoMirrored.Filled.Send` (primary when enabled,
   onSurfaceVariant when disabled); Stop uses `Icons.Filled.Stop` with
   error colors while generating.
3. Keep: `maxLines = 5`, `heightIn(min = 48.dp, max = 160.dp)` on the field,
   `ImeAction.Send` + `KeyboardActions.onSend`, `canSend` gating,
   keyboard hide after send, AnimatedContent send/stop cross-fade.
4. Outer wrapper Surface keeps `navigationBarsPadding()` + tonal elevation.

**Verify:** visual check both themes; long text grows the rect to 5 lines
then scrolls internally; disabled state before model load; stop mid-stream.

**Commit:** `Chat: rectangle input bar with inline send/cancel buttons`

### Slice 3 — Polish + edge cases

**Files:** `ChatInputBar.kt`, `ChatRoomScreen.kt`

- While generating, keep the text field enabled (user can pre-type the next
  message) but Send disabled — matches WhatsApp behavior.
- Empty-state screens (`ChatRoomEmpty`) should also sit above the input bar
  (they already do via the same Box — verify after Slice 1).
- Snackbar host: ensure snackbars appear above the input bar when the
  keyboard is open (Scaffold handles this once imePadding is on the column).
- Trim trailing whitespace-only sends (already handled via `text.trim()`).

**Verify:** full manual pass — send/stop/regenerate/edit flows with the
keyboard open and closed; snackbar visibility; model-switch mid-chat.

**Commit:** `Chat: input polish — pre-type while generating, snackbar insets`

---

## Test Plan

- Manual matrix (device/emulator):
  - Keyboard open: last message visible above input bar; input bar above IME.
  - Keyboard closed: input bar above nav bar; list bottom padding sane.
  - 5-line text growth; internal scroll beyond 5 lines.
  - Send disabled states: no model, blank text, generating.
  - Stop mid-generation; regenerate; edit message.
  - Both themes; landscape.
- No new unit tests needed (pure UI layout change; ViewModel untouched).

## Out of Scope

- Attachments/images, voice input, @mentions.
- Input bar in ChatTabScreen (it has no input bar — only ChatRoomScreen does).
- Message bubble redesign (separate concern).

## Theme & Font Rules

All colors from `MaterialTheme.colorScheme`; typography from
`MaterialTheme.typography`; 48dp minimum interactive targets; edge-to-edge
insets per existing conventions.
