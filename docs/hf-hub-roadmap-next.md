# Hugging Face Hub integration roadmap (next)

This document tracks the remaining Hugging Face Hub / ecosystem improvements for TinyModels after the initial quick wins and device-fit work:

1. **Quick wins (implemented first):** richer `expand`-based model details (license/gating/card prompts/base model/size/params/quant hints), trending/last-modified/new feeds, and tag-driven filters via `/api/models-tags-by-type`.
2. **Device-fit engine (implemented first):** GGUF/safetensors/size metadata + `tree`/`paths-info` signals to recommend compatible quant/size variants and warn before bad downloads.

The items below are planned next, in priority order.

## 3) Discovery surfaces

**Goal:** make model discovery feel current, curated, and useful for on-device users.

- **Trending / curated home sections**
  - Use `GET /api/trending?type=model` for lightweight “what’s hot now” modules.
  - Complement with catalog sorts (`trendingScore`, `lastModified`, `createdAt`, `likes`, `downloads`) and cached sections (e.g. “Trending for mobile”, “New GGUF this week”, “Recently updated”).
- **Daily Papers feed (`/api/daily_papers`)**
  - Add a *Learn / Discover* surface highlighting on-device AI papers (SLMs, quantization, GGUF/llama.cpp, mobile inference, multimodal on-device).
  - Where possible, link papers to related downloadable Hub models/families.
- **Collections (`/api/collections`)**
  - Surface curated lists (e.g. “Best small chat models for phones”, “Vision GGUFs”, “LiteRT-LM picks”) maintained by HF/community/team for higher-quality discovery than raw search.

**Notes:** cache responses with TTL, handle rate limits gracefully, and keep all content optional/offline-tolerant.

## 4) Optional cloud / preview features (opt-in, local-first)

**Goal:** help users preview or compare models without compromising TinyModels’ on-device, privacy-first default.

- **Spaces previews (`/api/spaces`)**
  - Show demos for model families/tasks so users can preview behavior before downloading large files (in-app webview or handoff).
- **Inference Providers / Router (comparison & fallback)**
  - Use Hub inference metadata (`inference_provider`, `expand=inference` / `inferenceProviderMapping`) and `GET https://router.huggingface.co/v1/models` for capability/perf/pricing/context signals.
  - Optional “Try in cloud” or “Compare with cloud” flows via router/chat endpoints, clearly labeled, user-initiated, and using the user’s HF token.
- **Guardrails**
  - Explicit consent, clear privacy/cost messaging, no off-device data by default, and graceful degradation when unavailable/rate-limited.

## 5) Maintenance & trust

**Goal:** keep the catalog reliable, reproducible, and respectful of Hub constraints.

- **Revision/sha tracking & update checks**
  - Persist resolved revision/`sha` for downloaded files; detect upstream updates (new commit/quant) and surface “update available”.
- **Caching & resilience**
  - TTL cache for catalog/detail/tag responses, request debouncing, backoff/retry for transient failures, and defensive parsing for optional/missing Hub fields.
- **Rate limits & auth hygiene**
  - Respect Hub-wide rate limits; prefer fine-grained read/gated-access tokens; improve gated-access guidance and account state (e.g. `GET /api/whoami-v2`) without over-requesting scopes.
- **Provenance signals**
  - Highlight author, maintenance recency, downloads/likes/trends, lineage/base model, and license/gating consistently across browse and details.

## Cross-cutting considerations

- **Offline-first:** Hub features must degrade cleanly with no network; core chat/inference remains fully local.
- **Performance:** minimize payloads with `expand`, paginate, and avoid repeated large fetches on mobile networks.
- **Correctness:** Hub metadata is heterogeneous/optional; treat it as hints, validate runnable files locally, and prefer measured device-fit signals where possible.
