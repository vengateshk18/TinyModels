# TityModels Revamp — Implementation Progress

Tracking thin-vertical-slice implementation. Each slice is end-to-end (DI → domain → data → UI),
built and verified, then committed.

| Slice | Scope | Status | Commit |
|-------|-------|--------|--------|
| — | Baseline (pre-revamp legacy app) | ✅ | `chore: baseline` |
| 0 | Foundation: deps, Hilt, DI modules, App class, core/common, sealed Result | 🔄 In progress | — |
| 1 | Memory & engine core: `ModelManager` (RAM guard, GPU→CPU, token cap, trim) | ⬜ | — |
| 2 | Chat persistence: Room v2 (Chat+Message), `ChatRepository` | ⬜ | — |
| 3 | Chat feature MVI: `ChatViewModel` (single UiState), `ConversationSession`, streaming fix | ⬜ | — |
| 4 | Chat UI: ChatScreen, bubbles, input (send/stop), model picker, history drawer | ⬜ | — |
| 5 | Models feature: browse/details/download + ViewModels + Hilt worker | ⬜ | — |
| 6 | Downloaded-models management + delete (unload-if-active) | ⬜ | — |
| 7 | Settings feature: DataStore theme/backend/sampler/context + theme wiring | ⬜ | — |
| 8 | NavHost wiring, remove legacy code, final build & verify | ⬜ | — |

Legend: ⬜ pending · 🔄 in progress · ✅ done

## Notes / decisions
- Single-Activity + Navigation-Compose (chat = start destination).
- `ModelManager` is a Hilt `@Singleton` that owns the LiteRT-LM `Engine` independent of ViewModels.
- LiteRT-LM `sendMessageAsync` emits **cumulative** text → UI **replaces**, never appends (fixes duplication bug).
- Backend selection: AUTO = GPU → CPU fallback. `maxNumTokens` caps KV cache.
- Theme colors must come from `MaterialTheme.colorScheme` (no hardcoded `Color.Black/Blue`).
