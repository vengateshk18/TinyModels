# Plan: Clear model-running architecture + MediaPipe `.task` runtime

**Date:** 2026-10-15
**Status:** approved for immediate execution (user: "Clear architecture the model running part. Start implementing")
**Scope:** `.task` models run on-device via MediaPipe LLM Inference. GGUF/llama.cpp deferred (user decision). Raw `.tflite` LLM files remain unsupported (MediaPipe only runs `.task` bundles).

## Goal

1. Extract a clean pluggable architecture for "the model running part": a
   `ModelRuntime` per file format + a `ChatSession` contract, so LiteRT-LM
   (`.litertlm`) and MediaPipe (`.task`) are peer backends and future runtimes
   (GGUF) slot in without touching chat UI logic.
2. Ship the MediaPipe runtime so `.task` files actually generate text.

## Key verified facts (from source/AAR inspection, 2026-10-15)

- `com.google.mediapipe:tasks-genai:0.10.35` is on Google Maven (already a
  configured repo). Transitive: guava 27.0.1-android, protobuf-javalite.
  AAR ships arm64-v8a/armeabi-v7a/x86/x86_64 native libs.
- API (verified against AAR `javap` + upstream source):
  - `LlmInference.createFromOptions(context, LlmInferenceOptions)`;
    options: `setModelPath`, `setMaxTokens` (input+output budget, default
    512), `setMaxTopK` (default 40), `setPreferredBackend(DEFAULT|CPU|GPU)`.
  - `LlmInferenceSession.createFromOptions(llmInference, SessionOptions)`;
    session options: `topK/topP/temperature/randomSeed`, and
    `setPromptTemplates(PromptTemplates)` (prefix/suffix for user/model/system).
  - `session.addQueryChunk(text)` → `session.generateResponseAsync(ProgressListener)`.
  - **ProgressListener receives DELTAS** (`partialResultDecoded` is the new
    piece; the lib accumulates internally for the final future value).
  - `session.cancelGenerateResponseAsync()` exists → stop button works.
  - `listener.run(x, done=true)` on completion.
  - There is **no history/initial-messages API** and no system-prompt entry
    point on sessions → we are stateless-per-turn: build the full prompt
    ourselves and use one throwaway session per send (same thing the
    library's own `generateResponseAsync` implicit-session path does).
  - Set `PromptTemplates` to ALL-EMPTY strings explicitly so the session adds
    no wrapping of its own (we fully control formatting).
- MediaPipe LLM Inference is in maintenance mode (Google points to LiteRT-LM),
  but it is still the only library that executes `.task` bundles.
- Bulk of community `.task` LLM bundles are Gemma-family → v1 chat template:
  Gemma (`<start_of_turn>user|model ... <end_of_turn>`), system instruction
  folded into the first user turn (Gemma convention).

## Target architecture

```
core/inference/                         (façade, unchanged package)
  ModelManager.kt     — format dispatch, memory guard, state machine,
                        swap atomicity, createSession/withSession entry
  BenchmarkRunner.kt  — benchmarks through the same ChatSession contract
  ModelVisionSupport.kt / ModelContextInspector.kt (unchanged internals)
  runtime/                              (NEW — the pluggable model-running part)
    ChatSession.kt        ChatTurn, ChatSession interface, BaseChatSession
    SessionBudget.kt      estimateTokens / trimToBudget / isCoveredByDigest
                          / IMAGE_TOKEN_COST / SUMMARIZE_PROMPT (moved from
                          ConversationSession companion — pure logic)
    ModelRuntime.kt       ModelRuntime interface + RuntimeInitRequest +
                          SessionRequest
    RuntimeRegistry.kt    format → runtime (null = unsupported)
    RuntimeModule.kt      Hilt: RuntimeRegistry(listOf(litert, mediaPipe))
    LitertLmRuntime.kt    LiteRT-LM engine lifecycle (moved out of ModelManager)
    ConversationSession.kt (moved here; now `: BaseChatSession`)
    MediaPipeLlmRuntime.kt LlmInference lifecycle (new)
    MediaPipeSession.kt    ChatSession over throwaway LlmInferenceSession (new)
    MediaPipePromptBuilder.kt  Gemma template builder (new, pure)
```

### Interfaces (implementer-facing contract)

```kotlin
interface ChatSession {
    val estimatedTokensInContext: Int
    val isContextNearFull: Boolean
    fun send(text: String, imagePaths: List<String> = emptyList()): Flow<String> // CUMULATIVE text
    fun close()
    suspend fun summarizeOlderTurns(older: List<ChatTurn>): String?
}

interface ModelRuntime {
    val format: ModelFormat
    val supportsToolCalling: Boolean          // LiteRT true, MediaPipe false
    fun detectVisionSupport(modelFile: File): Boolean
    suspend fun initialize(request: RuntimeInitRequest)   // ONE backend attempt; throws
    val isInitialized: Boolean
    fun createSession(request: SessionRequest): ChatSession
    suspend fun reloadWithoutVision(): Boolean = false    // LiteRT self-heal
    suspend fun close()
}
```

`ModelManager.loadModel(...)` public signature is unchanged; internally it
resolves `runtimeFor(format)` (else `InferenceError.UnsupportedFormat`),
keeps the GPU→CPU retry loop + vision self-heal policy, and only swaps
(`runtime`/`_engineState`) after a successful `initialize`. `withEngine` is
replaced by `createSession(SessionRequest)` (mutex held during creation) and
`withSession(request) { }` (mutex held for the block; used by BenchmarkRunner).

### MediaPipe runtime decisions

- Load: `LlmInferenceOptions(modelPath, maxTokens = maxNumTokens,
  maxTopK = 64, preferredBackend)`; GPU→CPU fallback inherits ModelManager's
  loop. Vision: not supported in v1 (text-only .task); `detectVisionSupport`
  returns false so photos are stripped/blocked by existing UI guards.
- Session (`MediaPipeSession`) is stateless: each `send()` builds the full
  prompt (system+summary folded per Gemma convention, trimmed history, new
  turn) via `MediaPipePromptBuilder`, creates a throwaway `LlmInferenceSession`
  with the user's sampler + EMPTY prompt templates, streams deltas as
  cumulative text, `cancelGenerateResponseAsync()` on flow cancellation, and
  closes the session. So chat reopen/regenerate/edit work exactly like LiteRT
  (rebuild = rebuild prompt from history).
- Token accounting mirrors LiteRT (`SessionBudget.estimateTokens`, chars/4)
  so the context gauge behaves identically; `isContextNearFull` shares the
  80% high-water policy via `BaseChatSession`.
- Errors: native throwables surface as today's generic generation errors.
- Tool calling: gated by `ModelManager.activeRuntimeSupportsTools`; when the
  active runtime is MediaPipe the chat builds no LiteRT tools and the
  automatic (prompt-augmentation) web search still works.

## Steps (TDD where JVM-testable)

1. Gradle: add `com.google.mediapipe:tasks-genai:0.10.35`; one online build to
   populate the Gradle cache; all later builds stay `--offline`.
2. Tests first:
   - `SessionBudgetTest` (moved/extended from CompactionPolicyTest coverage)
   - `BaseChatSessionTest` (token counting, 80% high-water)
   - `MediaPipePromptBuilderTest` (no-history, system folding, summary
     folding, multi-turn format, always-ends-with-model-cue)
   - `MediaPipeSamplerMapperTest` (topK clamp to maxTopK, Double→Float)
   - `RuntimeRegistryTest` (LITERTLM/TASK resolve, TFLITE/GGUF → null)
3. Implement runtime/ files; move ConversationSession; keep behavior
   identical for LiteRT (all 233 existing tests must stay green).
4. Refactor ModelManager to the registry + `createSession`/`withSession`.
5. Rewire ChatViewModel (session type, 3 rebuild call sites collapse onto
   `SessionRequest`, tools gating, SessionBudget statics) + BenchmarkRunner.
6. Update `InferenceError.UnsupportedFormat` message (`.task` now works).
7. Verify: unit tests, `lint`, `assembleRelease` all `--offline`.
8. Device smoke test (user): download a Gemma `.task` (litert-community),
   load, chat, stop, reopen chat, regenerate; benchmark run.

## Out of scope (explicit)

- GGUF/llama.cpp (deferred by user), `.tflite` raw LLM support, MediaPipe
  vision/audio `.task`, playing back tool-calling inside MediaPipe.
