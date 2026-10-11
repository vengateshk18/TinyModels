# Plan: Web Search as a Tool in TinyModels

Give the on-device assistant the ability to **search the web** when the user
asks something it doesn't know — via LiteRT-LM's native **function calling
(tool use)** support, which is already available in the version we ship
(`litertlm-android 0.17.1`).

---

## 0. How LiteRT-LM tool use works (verified against our 0.17.1 AAR)

- A class implementing the marker interface `ToolSet` with methods annotated
  `@Tool("description")` is converted via `tool(toolSet)` into a `ToolProvider`.
- `ConversationConfig(tools = listOf(provider), automaticToolCalling = true, ...)`
  registers the tools with the conversation.
- With `automaticToolCalling = true`, the runtime **loops automatically**:
  model emits a `ToolCall(name, arguments)` → runtime executes the Kotlin
  method → result is fed back as `Content.ToolResponse` → model continues
  generating. The app just streams the final text as usual.
- `ToolManager` / `ToolProvider` / `ToolCall` / `Content.ToolResponse` are the
  relevant classes (confirmed present in the AAR).

This means **web search needs no custom parsing loop** — we implement one
`@Tool` method that performs an HTTP search and returns results; the runtime
handles the rest.

---

## 1. Search backend choice

On-device models can't call external APIs themselves — the *app* executes the
tool. Options:

| Option | Pros | Cons |
|---|---|---|
| **A. DuckDuckGo HTML endpoint (no key)** | free, no API key, no account | unofficial, fragile parsing, rate limits |
| **B. Brave Search API (free tier 2k/mo)** | official JSON, generous free tier | needs API key |
| **C. Google Custom Search JSON API** | quality | 100/day free, key + CSE id |
| **D. Tavily (search-for-AI)** | LLM-optimized snippets | key, small free tier |

**Recommendation: A (DuckDuckGo) for v1** — zero friction, works offline of any
account; abstracted behind an interface so B/C/D can be swapped in later.
Design the tool to return **title + URL + snippet** (not raw HTML) so the
small model gets compact, useful context.

---

## 2. Architecture (fits our clean architecture)

```
core/network/WebSearchClient.kt        # OkHttp + Jsoup-free regex/JSON parse
domain/model/WebSearchResult.kt         # data class (title, url, snippet)
domain/model/WebSearchSettings.kt       # enabled flag (DataStore)
domain/repository/WebSearchRepository.kt# interface: suspend search(query): List<WebSearchResult>
data/repository/WebSearchRepositoryImpl.kt
core/inference/tools/WebSearchToolSet.kt # class WebSearchToolSet : ToolSet {
                                          #   @Tool("Search the web for current information...")
                                          #   suspend fun webSearch(query: String): String
                                          # }
```

- `WebSearchToolSet` gets the repository injected (Hilt) and formats results
  as compact text: `1. Title — snippet (url)`.
- **Threading**: the tool method runs on the runtime's executor — internally
  it must `withContext(dispatchers.io)` for the HTTP call (main-thread policy).
- **Timeouts**: 10s connect/read; on failure return `"Search unavailable: ..."`
  so the model degrades gracefully instead of crashing the turn.

---

## 3. Wiring into the session

`ConversationSession.create(...)` gains a `tools: List<ToolProvider> = emptyList()`
parameter, passed straight into `ConversationConfig(tools = tools,
automaticToolCalling = true, ...)`.

`ChatViewModel.rebuildSession` builds the tool list:
- `webSearchEnabled` (per-app setting, DataStore) → `tool(WebSearchToolSet(repo))`
- else → empty list (no tools registered, zero token overhead).

**Context accounting**: tool schemas + tool call/response turns consume
context tokens. Update `ConversationSession.estimatedTokensInContext` to
also count tool descriptions (once) and each tool round-trip, so the 90%
auto-compaction trigger stays accurate.

**Streaming UI**: with `automaticToolCalling = true`, the user just sees the
final answer stream as today. Optionally (v2) surface a subtle
"🔎 Searching the web…" status while a tool call is in flight (detectable via
a callback/Channel in the config, or simply a "searching" state set around
the send).

---

## 4. Settings & UX

### 4.1 Per-chat toggle — the "+" menu in the input bar

Layout: **`[+] [ input field ] [ send/stop ]`** — a `+` IconButton sits
*before* the text field in `ChatInputBar`.

- Tapping `+` opens a small **dropdown menu** anchored to the button with
  one entry for now (extensible later):
  - `🔎 Web search` — with a check icon when enabled for this chat.
- Toggling it:
  1. Flips a **per-chat** `webSearchEnabled` flag (new column on
     `ChatEntity`, DB v8 + `MIGRATION_7_8`, default `false`).
  2. Persists via `ChatRepository.updateWebSearchEnabled(chatId, enabled)`.
  3. `ChatViewModel` rebuilds the session so the tool list changes take
     effect immediately (same path as inference-settings changes).
- **Visual state**: when enabled for the chat, the `+` button shows a small
  badge/dot so the active state is visible without opening the menu.
- **New chat**: the per-chat flag defaults to OFF; the global setting (below)
  only controls *availability*, not per-chat state. Before the first message
  creates the chat row, the flag is stashed in `ChatUiState` and applied at
  chat creation.

### 4.2 Global toggle — Settings screen

- New "Web search" switch (default ON) in Settings, persisted in DataStore
  (`webSearchEnabled`).
- Semantics: **global = availability gate**; **per-chat = actual state**.
  - Global OFF → the `+` menu entry is hidden/disabled; tools never register.
  - Global ON → each chat can independently opt in/out via the `+` menu.
- Note under the switch: "When enabled for a chat, the model may search the
  web when it needs current information. Search queries leave the device."

### 4.3 Behavior details

- **Offline / search failure**: the tool returns "Search unavailable" text;
  the model answers from its own knowledge — never a crash.
- **Privacy**: only the search query (chosen by the model) goes to the
  search provider; everything else stays on-device.
- **Indicator**: while a tool round-trip is in flight, show a subtle
  "🔎 Searching the web…" status above the input bar (v2, optional).

---

## 5. Model capability caveat

Function calling requires a model trained for it (Gemma 3 it/litert variants
support tool use; tiny base models may not). Plan handles this:
- Add a `supportsTools` flag to the model/file metadata (from filename/catalog
  heuristics, e.g. `-it` instruction-tuned).
- If unsupported: hide the toggle, don't register tools, and optionally
  fall back to a **prompt-based tool** (system instruction describing a
  `SEARCH:` convention the app detects in the stream) — v2, only if needed.

---

## 6. Implementation steps (ordered)

1. `WebSearchClient` + `WebSearchRepository` (DuckDuckGo), unit tests with
   MockWebServer for parsing + failure modes.
2. `WebSearchToolSet` (`@Tool` method, io dispatcher, compact formatting).
3. `ConversationSession.create(..., tools)` → `ConversationConfig` plumbing.
4. DB v8: per-chat `webSearchEnabled` column (`MIGRATION_7_8`) +
   `ChatRepository.updateWebSearchEnabled` + domain model field.
5. DataStore: global `webSearchEnabled` (default true) + Settings UI switch.
6. `ChatInputBar`: `+` IconButton before the text field → dropdown menu with
   "Web search" entry (check state) → per-chat toggle → session rebuild.
7. `ChatViewModel.rebuildSession`: tool list = global AND per-chat enabled.
8. Context accounting for tool tokens (compaction stays accurate).
9. Optional "Searching…" status chip during tool round-trips.
8. Tests: repository parsing, tool formatting, session wiring; manual test
   on device with a tool-capable model (e.g. gemma-3 it).
9. Docs: README + `ai/` plan status update.

**Estimated size**: ~350 lines + tests. No new dependencies (OkHttp already
shipped; DDG endpoint returns HTML we parse with existing regex approach —
or add `org.jsoup` ~1 dependency if we want robust parsing).

---

## 7. Risks & mitigations

| Risk | Mitigation |
|---|---|
| DDG endpoint changes/blocks | interface-based client; swap to Brave/Tavily with a key setting |
| Small model emits malformed tool calls | `automaticToolCalling` handles retry; tool returns error text, model recovers |
| Tool tokens blow the context budget | counted in usage estimate → auto-compaction triggers correctly |
| Privacy expectations | explicit Settings note + per-chat toggle |
| Native thread-safety | tool execution is app-side (OkHttp), never touches the native conversation concurrently — no mutex changes needed |