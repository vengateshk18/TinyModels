# Conversation Compaction & Model Context Detection — Plan

## Problem

When a chat grows long, the prompt + history exceed the model's real context
window and the engine fails with `Status code 13: Task failed with large input`.
Today we only do a hard sliding-window trim (`trimToBudget`), which silently
drops old turns. We want **compaction** — summarize old turns instead of
dropping them — plus **automatic detection of each model's true context size**.

---

## Part 1 — Detecting each model's context window

There is no single universal algorithm, but there are three practical sources,
in order of reliability:

### 1a. Parse the `.litertlm` container metadata (best, offline)

The `.litertlm` file is a LiteRT-LM container with a documented layout
(`schema/core/litertlm_header_schema.fbs` in the LiteRT-LM repo):

```
[0:8]    magic "LITERTLM"
[8:20]   major/minor/patch version (3x uint32 LE)
[20:24]  padding
[24:32]  header_end_offset (uint64 LE)
[32:end] FlatBuffer LiteRTLMMetaData (root_type)
```

The FlatBuffer metadata contains `LlmMetadata` with a **`max_num_tokens`**
field (the compiled context length — the KV cache is sized for exactly this),
plus `start_token`, `stop_tokens`, and `prompt_templates`. Reading it requires
parsing the FlatBuffer with the LiteRT-LM schema; a small Kotlin parser can
read just the header (a few KB) without loading the weights.

**Caveat:** many community containers (gemma3/qwen3 conversions) omit
`max_num_tokens` — then fall back to (1b).

### 1b. Parse the embedded TFLite graph shape (reliable fallback)

The container's `tf_lite_prefill_decode` section is a TFLite model whose
`kv_cache` / attention tensors have a **sequence-length dimension equal to the
compiled context**. Reading the TFLite flatbuffer header (also a few KB) and
inspecting the decode-graph tensor shapes gives the exact context. This works
even when `LlmMetadata.max_num_tokens` is missing.

### 1c. Filename heuristics (last resort)

Community bundles often encode the context in the filename, e.g.
`DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm`
(`ekv4096` → 4096 KV positions). A regex like `ekv(\d+)` or `ctx(\d+)` covers
most cases.

### 1d. HF API metadata (online, optional)

`config.json` on the HF repo has `max_position_embeddings`, but that is the
*training* context, not the *compiled* context of the quantized bundle — the
compiled value is always ≤ it. Use only as an upper bound.

### Recommended implementation

```
ModelContextInspector (new, core/inference)
  fun detectContext(file: File): Int?
    1. try litertlm LlmMetadata.max_num_tokens
    2. try TFLite prefill/decode graph seq-len dim
    3. try filename regex (ekvN / ctxN)
    4. null → caller falls back to user setting (current behavior)
```

Store the detected value on `ModelFileEntity` (new column `contextTokens: Int?`,
DB migration v5→v6) when a download completes, and use it to:
- cap the "Max context tokens" options shown in `InferenceSettingsSheet`
- pass the true `maxNumTokens` to `ModelManager.loadModel` instead of the
  user's guess (or clamp the user's choice to the detected max)

---

## Part 2 — Conversation compaction

### When to compact

Trigger when `estimatedTokensInContext` crosses a **compaction threshold**
(e.g. 70% of the effective budget = detected context − generation headroom).
The existing 80% `HIGH_WATER_RATIO` stays as the "context nearly full" warning.

### Compaction algorithm (summarize-then-keep-recent)

This is the standard approach used by Claude Code / ChatGPT-style clients:

1. **Split history**: keep the most recent K turns verbatim (e.g. last 6
   turns, or the newest turns that fit in ~50% of the budget).
2. **Summarize the rest**: ask the *same local model* to summarize the older
   turns into a compact digest. Prompt:

   > "Summarize the following conversation so far in under 200 tokens.
   > Keep: user goals, decisions, constraints, key facts, and any
   > unresolved questions. Drop pleasantries and repetition."

3. **Rebuild the session** with:
   - system instruction = original system instruction + "\n\nConversation so far (summary): <digest>"
   - initialMessages = the kept recent turns
4. **Persist the digest** so compaction survives app restarts:
   - new `MessageEntity.role = "SUMMARY"` (or a `chat_summaries` table keyed
     by chatId with `summaryText` + `coveredUpToMessageId`), so we never
     re-summarize already-summarized turns.

### Data model changes

```
MessageEntity: add role value "SUMMARY" (no schema change — role is a String)
   OR (cleaner):
ChatEntity: add compactedSummary: String? = null,
            compactedUpToCreatedAt: Long? = null   (migration v5→v6)
```

`rebuildSession` then builds history as:
`[SUMMARY digest as a system-style preamble] + messages after compactedUpToCreatedAt`.

### Compaction flow (in ChatViewModel / ConversationSession)

```
on each completed generation:
  if session.estimatedTokensInContext > 0.70 * effectiveBudget:
      compact(chatId)

compact(chatId):
  1. older = messages before the keep-window
  2. digest = model.summarize(older)          // one-shot, non-streaming
  3. chatRepository.saveCompaction(chatId, digest, cutoffTimestamp)
  4. rebuildSession(chatId)                    // now uses digest + recent turns
```

### Edge cases

- **Summarization itself can overflow**: cap the older-turns input to the
  budget with the existing `trimToBudget` before summarizing (worst case we
  summarize the summary — iterative compaction, which is fine).
- **Model too small to summarize well** (270M models): fall back to pure
  sliding-window trim (current behavior) — make compaction opt-in per chat or
  only enable above a model size threshold.
- **User edits a message inside the summarized region**: invalidate the digest
  (`compactedUpToCreatedAt = null`) and re-compact from full history.
- **Regenerate**: same invalidation rule if the regenerated turn predates the
  cutoff.

### UX

- Show a subtle system chip in the transcript: "Older messages were
  summarized to fit the context window" (tappable to view the raw digest).
- In session settings, expose a toggle: "Auto-compact long conversations"
  (default on when model ≥ ~1B params).

---

## Part 3 — Token counting accuracy (supporting fix)

The `chars / 4` estimate under-counts for code/CJK text. Improvements:
- LiteRT-LM exposes the tokenizer in the container; a Kotlin SP-tokenizer
  parse would give exact counts (heavier — Phase 2).
- Cheap interim fix: use `chars / 3` for text containing code fences or
  non-ASCII, and keep the 25% generation headroom already added.

---

## Implementation phases

| Phase | Work | Files |
|---|---|---|
| 1 | `ModelContextInspector` (litertlm header + TFLite shape + filename regex), DB column + migration, clamp engine `maxNumTokens` | new `core/inference/ModelContextInspector.kt`, `ModelFileEntity`, `Migrations.kt`, `ModelManager`, `ChatViewModel.selectModel` |
| 2 | Compaction engine: summarize prompt, `compact()` in ViewModel, digest persistence on ChatEntity, `rebuildSession` digest injection | `ChatViewModel`, `ConversationSession`, `ChatRepository(+Impl)`, `ChatDao`, `ChatEntity` |
| 3 | UX: summary chip in transcript, settings toggle, invalidation on edit/regenerate | `ChatRoomScreen`, `InferenceSettingsSheet`, `ChatUi` |
| 4 | Exact tokenization via embedded SP tokenizer (optional) | `ConversationSession` |

Phase 1 alone eliminates most "Status code 13" failures (right-sized engine
context). Phase 2 preserves long-conversation coherence instead of silently
dropping turns.
