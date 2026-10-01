# TinyModels Revamp — Implementation Progress

Tracking thin-vertical-slice implementation. Each slice is end-to-end (DI → domain → data → UI),
built and verified, then committed.

| Slice | Scope | Status | Commit |
|-------|-------|--------|--------|
| — | Baseline (pre-revamp legacy app) | done | chore: baseline |
| — | Rename TityModels -> TinyModels (package/appId/theme/db) | done | refactor: rename |
| 0+1 | Foundation (Hilt/nav/DataStore/markdown) + Memory & engine core (ModelManager, ConversationSession) | done | feat(core): slice 0+1 |
| 2 | Chat persistence: Room v2 (Chat+Message), ChatRepository | done | feat(data): slice 2 |
| 3 | Chat feature MVI: ChatViewModel (single UiState), streaming fix | done | feat(chat): slice 3 |
| 4 | Chat UI: ChatScreen, components, theme, single-activity NavHost + MainActivity | done | feat(chat-ui): slice 4 |
| 5 | Models feature: browse/details/download + ViewModels + Hilt worker (wire remote catalog into ModelRepository) | done | feat(models): slice 5 |
| 6 | Downloaded-models management + delete (unload-if-active); remove legacy models/local + worker | done | feat(downloads): slice 6 |
| 7 | Settings feature screen (theme/backend/sampler/context UI) | done | feat(settings): slice 7 |
| 8 | Final cleanup: delete legacy models/, chat/, utils/ (Injection, OkHttpUtil->Hilt), verify | done | chore: slice 8 |

## Milestone
- App assembles end-to-end (assembleDebug -> app-debug.apk) with the new chat feature live
  through the single-activity NavHost. Models/settings/downloaded destinations are placeholders
  until slices 5-7.

## Notes / decisions
- Single-Activity + Navigation-Compose (chat = start destination).
- ModelManager is a Hilt @Singleton that owns the LiteRT-LM Engine independent of ViewModels.
- LiteRT-LM sendMessageAsync emits CUMULATIVE text -> UI REPLACES, never appends (fixes duplication bug).
- Backend selection: AUTO = GPU -> CPU fallback. EngineConfig.maxNumTokens caps KV cache.
- ConversationConfig.initialMessages restores prior turns on reopen (sliding-window trimmed).
- Theme colors must come from MaterialTheme.colorScheme (no hardcoded Color.Black/Blue).
- AGP 9: built-in Kotlin (no kotlin-android plugin); hiltViewModel now in androidx.hilt.lifecycle.viewmodel.compose.
