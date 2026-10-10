# TinyModels — From Model Load to Conversation Management

A complete walkthrough of how the chat room works: how a model is loaded, how
each inference setting affects generation, and how the context window is
managed automatically so the user never has to think about it.

---

## 1. Loading the model (selectModel)

When the user picks a model (or a specific `.litertlm` file) in the chat room:

1. **Resolve the file record** from Room (`model_files` table) — the user's
   pick, or the first downloaded file as fallback. If the file is missing on
   disk → error chip "Model file missing on device".
2. **Detect the context window** (`ModelContextInspector`, filename heuristic,
   e.g. `ekv4096` → 4096). This is the *automatic compaction budget*. If
   detection fails, a safe default of **2048** is used. This value is NOT
   user-tunable anymore.
3. **Load the engine** (`ModelManager.loadModel`) with:
   - `backend` (user's per-chat setting)
   - `maxNumTokens = detected context window` — loading with a larger value
     than the bundle supports fails at `initialize()` time, so the detected
     value (or safe default) is used directly.
   - A simulated progress ticker runs alongside the native load (the engine
     only reports a few discrete milestones), merged with real progress.
4. **On success**: persist last-used model, then **rebuild the conversation
   session** for the active chat (see §3).

All native work runs on `Dispatchers.IO` / `Default` — never the main thread
(ANR policy), and **all native access is serialized through `nativeMutex`**
because LiteRT-LM's native runtime is not thread-safe (concurrent access
segfaults in `liblitertlm_jni.so`).

---

## 2. Inference settings and their impact

Each chat has its own `InferenceSettings` (stored as columns on the chat row
in Room). The user edits them in the Session Settings sheet; changes are
persisted and the session is rebuilt so they take effect immediately.

| Setting | What it does | Performance / quality impact |
|---|---|---|
| **Temperature** (0.0–2.0, default 0.7) | Scales the randomness of token sampling. Low → greedy/deterministic, high → creative/chaotic. | 0 gives repetitive, focused output (good for factual/code); 1.0+ adds diversity but increases incoherence and hallucination risk. No speed impact. |
| **Top-K** (default 40) | Only the K highest-probability tokens are considered each step. | Small K (1–10) → safe, repetitive; large K (100+) → more variety, more risk of odd tokens. No measurable speed impact. |
| **Top-P / nucleus** (default 0.95) | Considers the smallest set of tokens whose cumulative probability ≥ P. | 0.9–0.95 is the usual sweet spot. Lower → focused; higher → diverse. No speed impact. |
| **Backend** (AUTO / CPU / GPU) | Which LiteRT-LM accelerator runs the model. | GPU is much faster (when supported) but may fall back to CPU; AUTO picks the best available. Biggest *speed* lever of all settings. |
| **System instruction** | The system prompt baked into the conversation. | Defines persona/behavior. Longer instructions consume context tokens every turn. |
| **Context window** | ❌ **Removed as a user setting.** | Now detected automatically (or 2048 default) and managed by auto-compaction (see §3). |

**How they combine:** temperature/top-K/top-P are passed into the
`SamplerConfig` of every conversation; backend and `maxNumTokens` are engine
level (require a reload/rebuild to change).

---

## 3. Managing the conversation (the session lifecycle)

### 3.1 Session build (rebuildSession)

A `ConversationSession` wraps one native `Conversation`. It is rebuilt:

- after a model loads,
- when settings change,
- when the user edits/regenerates a message,
- after compaction.

The rebuild:

1. Reads the chat's messages from Room.
2. **Excludes messages covered by a compaction digest** (createdAt ≤
   `compactedUpToCreatedAt`) — their gist is carried by the digest instead.
3. Trims history to fit the budget (reserving 25% headroom for the reply —
   `GENERATION_HEADROOM_RATIO`), so a too-long history never reaches the
   native engine (which would fail with "Status code 13: Task failed with
   large input Id").
4. Creates the conversation with the sampler config, system instruction, and
   the prior digest folded into the system prompt.
5. Refreshes the context-usage UI state (`updateContextUsage`).

### 3.2 Sending a message

1. Persist the user message (with an estimated token count) to Room.
2. Optimistic UI: user bubble + streaming assistant placeholder.
3. `runGeneration` holds `nativeMutex` for the whole generation (a concurrent
   compaction summarization can never touch the same conversation) and
   streams cumulative deltas into the placeholder.
4. On completion: persist the assistant message, then **maybeCompact** (§3.3),
   then refresh context usage.

### 3.3 Automatic context management (compaction)

The context budget is **automatic** — the user never tunes it:

- **Budget** = detected context window (filename heuristic) or 2048 default.
- **Trigger**: after each completed generation, if
  `estimatedTokensInContext ≥ 90% of budget` (`COMPACTION_HEADROOM`), compaction runs.
- **Compaction algorithm** (`compactInternal`):
  1. Keep the most recent turns that fit in **half** the budget.
  2. **Summarize** the older turns (plus the previous digest — "summarize the
     summary") using the same local model via a dedicated summarization prompt.
  3. Persist the digest + cutoff timestamp to Room (`saveCompaction`), which
     also increments `compactionCount`.
  4. Rebuild the session: digest in the system prompt, only post-cutoff
     messages in history → the KV cache shrinks and token usage drops.
- **Fallback**: if summarization fails, the rebuild still trims to budget
  (plain sliding window) — correctness is never sacrificed.
- **Invalidation**: if the user edits a message *inside* the compacted region,
  the digest is cleared (it no longer describes reality) and the count resets.

### 3.4 What the user sees

- **Circular icon (top bar)**: live fill = tokens-in-context ÷ budget, with a
  `%` label. Color: primary → tertiary (≥70%) → error (≥90%).
- **Context metrics sheet** (tap the icon): observability only —
  tokens in context, context budget, **how many times compaction has run**,
  auto-compaction behavior, compacted-summary status, and a manual
  **Compact conversation** button (with spinner + result message) for
  when the user wants to free context *before* the 90% threshold.

---

## 4. Threading & safety rules (summary)

| Rule | Why |
|---|---|
| Never block the main thread — native/DB work goes to `Dispatchers.IO`/`Default` | ANR prevention |
| All native calls serialized via `nativeMutex` | LiteRT-LM native runtime is not thread-safe (SIGSEGV otherwise) |
| Navigation callbacks on `Main` | Compose navigation must run off the main thread's forbidden list |
| Reserve 25% of budget for the reply | Prevents "Status code 13" context overflow |
| Compaction at 90% of budget | Keeps headroom before overflow, digest quality stays good |