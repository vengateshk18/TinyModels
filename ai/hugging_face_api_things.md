## Part 1: Searching for LLM models — params and payload

The Hugging Face Hub API's model-listing endpoint is a plain `GET` request — no POST payload, everything is passed as query string parameters.

**Base endpoint:**
```
GET https://huggingface.co/api/models
```

**Key query parameters:**

| Parameter | What it does | Example |
|---|---|---|
| `search` | Fuzzy/substring match across id, tags, description | `search=gemma` |
| `author` | Restrict to one org/user's uploads | `author=litert-community` |
| `filter` | Exact tag/pipeline match (stricter than `search`) | `filter=text-generation` |
| `pipeline_tag` | Same as above, dedicated param in some client libs | `pipeline_tag=text-generation` |
| `library` | Restrict by library/format (`transformers`, `gguf`, `litert-lm`) | `library=gguf` |
| `language` | Filter by supported language tag | `language=en` |
| `license` | Filter by license tag | `license=apache-2.0` |
| `sort` | Field to sort by (`downloads`, `likes`, `trending`, `lastModified`) | `sort=downloads` |
| `direction` | `-1` for descending, `1` for ascending | `direction=-1` |
| `limit` | Max results per call (you page past this with repeated calls) | `limit=100` |
| `full` | Include extended metadata (tags, pipeline info) in the response | `full=true` |

**Example combined query (what you've mostly been using):**
```bash
curl "https://huggingface.co/api/models?author=google&filter=text-generation&sort=downloads&direction=-1&limit=100&full=true"
```

**What you get back:** a JSON array of model summary objects (id, author, tags, pipeline_tag, downloads, likes, siblings — filenames only, no sizes). This is the *discovery* layer — good for finding candidates, not for making a final size/format decision.

**Two follow-up endpoints you need per-candidate (no bulk equivalent exists):**
```bash
# Full metadata — param count, config, tokenizer info
GET https://huggingface.co/api/models/<repo_id>

# File tree with actual byte sizes
GET https://huggingface.co/api/models/<repo_id>/tree/main?recursive=true
```

These three endpoints — list, detail, tree — are the whole toolkit. There's no single call that returns "all text-gen models under 2B with file sizes and mobile format" in one shot; you compose it as: list → detail (param count) → tree (file size/format), filtering client-side at each stage, exactly as we built up over this conversation.

---

## Part 2: What actually determines "mobile-suitable"

None of these are a single API flag — "mobile-ready" is something you derive by checking several independent signals together.

**1. Task type — `pipeline_tag`**
Must be `text-generation` (or `text2text-generation`) to even be an LLM candidate. This eliminates vision/audio/embedding models that happen to match a keyword search (the MobileNetV3 case from earlier).

**2. Parameter count — `safetensors.total` (from the detail endpoint)**
The real size of the model, in exact param count — not the name (`Qwen3-0.6B` was actually 751M, not 0.6B). This is your primary size gate; roughly ≤2B is a reasonable phone ceiling, though it interacts heavily with #3.

**3. File format — presence of specific filenames in `siblings`/tree**
This is the single biggest practical gate. In order of "most ready to least":
- `.task` / `.litertlm` — fully bundled, load directly into LiteRT-LM/MediaPipe, no conversion
- `.gguf` — ready for `llama.cpp`-based Android runtimes, no conversion
- `.tflite` (bare) — converted graph, but needs bundling with a tokenizer before MediaPipe's high-level API will load it
- `.safetensors` only — raw checkpoint, needs full conversion (LiteRT Torch or `convert_hf_to_gguf.py`) before it runs on-device at all

**4. Quantization — encoded in the filename, not a separate field**
Look for `int4`, `int8`, `q4`, `q8`, `dynamic_int8` in the filename. This is often a bigger factor in actual on-device footprint than raw param count — an INT4 1B model can be smaller than an INT8 500M model.

**5. Actual file size — `size` field from the `/tree` endpoint**
The only authoritative number for "will this fit on the phone and how long will the download take." Never estimate from param count alone — quantization changes this dramatically (a 751M-param model is ~1.5GB in BF16 but could be ~400MB at INT4).

**6. Gated status — `gated` field**
Determines whether you need an HF account + accepted license + bearer token, or can hit the resolve URL anonymously. Doesn't affect whether it's "mobile-suitable" technically, but affects whether your download pipeline needs auth handling.

**7. Benchmarked device data — not in the API at all, only in the model card README**
The actual proof that a model runs well on real hardware (tokens/sec, time-to-first-token, peak RSS memory) is prose/tables in the README, not structured API data. For the litert-community Gemma models we looked at, this is where you'd see things like "513 MB peak memory on S25 Ultra" — this has to be read manually or scraped from the card text, since there's no API field for it.

**Putting it together as a filter pipeline:**
```
list candidates (pipeline_tag=text-generation)
  → check detail: safetensors.total ≤ threshold
  → check tree: does a .task/.litertlm/.gguf file exist
  → check tree: file size acceptable
  → check filename: quantization level
  → check gated: do you need auth
  → (manually) check README: any benchmark numbers for real devices
```

No single query parameter does this for you — it's always this multi-step verification, which is exactly the shape of the scripts we built up over the conversation.

## Part 3: The model detail endpoint — params and response fields

This is the endpoint you hit once you've picked a candidate from the search/list step, to get the full picture before downloading.

**Endpoint:**
```
GET https://huggingface.co/api/models/<repo_id>
```
No query string needed for the basics — just the repo ID in the path. A few optional params extend what comes back:

| Parameter | What it adds |
|---|---|
| `blobs=true` | Include git blob OIDs for each file (rarely needed) |
| `securityStatus=true` | Include any malware/pickle scan results HF ran on the repo |
| `expand[]=<field>` | Request specific optional fields explicitly (used when the default response omits something you need) |

**Example:**
```bash
curl "https://huggingface.co/api/models/google/gemma-3-1b-it"
```

---

### Response fields, grouped by what they're for

**Identity**
- `id` / `modelId` — repo path (`org/name`), always identical to each other
- `author` — the org/user namespace
- `sha` — current commit hash of the default branch; pin to this for reproducibility
- `createdAt` / `lastModified` — repo creation and last-update timestamps

**Access control**
- `private` — whether the repo is publicly visible at all
- `gated` — whether you must accept a license/terms before downloading (Gemma, Llama are `true`; Qwen3 was `false`)
- `disabled` — whether HF or the author has taken the repo offline

**Classification**
- `pipeline_tag` — the primary task (`text-generation`, `image-text-to-text`, etc.) — your first filter for "is this an LM"
- `library_name` — which library loads it (`transformers`, `litert-lm`, `gguf`)
- `tags` — free-form list: architecture name, format, license, deployment compatibility, arXiv paper links

**Size and architecture — the fields that matter most for your filtering**
- `safetensors.total` — exact parameter count (ground truth, better than the model name)
- `safetensors.parameters` — breakdown by precision (e.g. `{"BF16": 751632384}`) — tells you the native storage precision, which lets you estimate raw file size (params × bytes-per-param)
- `config.architectures` — the exact model class (`Qwen3ForCausalLM`, `Gemma3ForCausalLM`) — needed if you're writing custom loading/conversion code
- `config.model_type` — short architecture identifier used by `transformers`/conversion tooling to pick the right code path
- `usedStorage` — total bytes for the whole repo including git history — **not** a reliable download-size estimate, since it includes old revisions; use the `/tree` endpoint instead for the current snapshot's real file sizes

**Files**
- `siblings` — list of `{rfilename}` entries, filenames only, no size (this is the gap you need the `/tree` endpoint to fill, as covered earlier)

**Tokenizer/chat behavior — critical if you're building your own inference wrapper rather than using a pre-bundled `.task`**
- `config.tokenizer_config.chat_template` — the Jinja2 template defining exactly how to format a multi-turn conversation into model input (role markers, special tokens). Get this wrong and output quality silently degrades even though nothing crashes.
- `config.tokenizer_config.eos_token` / `pad_token` / `bos_token` — the special tokens the model expects; needed for correct generation stopping and padding

**Card metadata (human-facing, duplicated for programmatic use)**
- `cardData` — condensed YAML frontmatter from the README (license, base_model, pipeline_tag) — same info as elsewhere, just structured

**Popularity/social (irrelevant to technical filtering, useful as a sanity signal)**
- `downloads`, `likes` — adoption signals; high numbers suggest the repo is well-tested, not a technical guarantee of quality
- `spaces` — list of HF Spaces (demo apps) using this model — good for seeing it running live, irrelevant to your download pipeline
- `inference` — whether HF's own hosted Inference API has it warm — unrelated to on-device use

**What this endpoint does NOT give you (the recurring gap)**
- Actual file sizes in bytes — go to `/tree/main?recursive=true` instead
- Benchmark numbers (tokens/sec, memory on real devices) — these live only as prose/tables in the README, no structured field
- Whether a `.task`/`.litertlm`/`.gguf` build exists *elsewhere* on the Hub — the detail endpoint only describes the one repo you queried; finding a converted sibling requires a separate `search=` query

---

### How this fits into your full discovery → verify → download pipeline

```
/api/models?filter=text-generation&...     → discover candidates (list, no sizes)
/api/models/<repo_id>                       → verify: param count, format, gated status, chat template
/api/models/<repo_id>/tree/main?recursive=true → verify: actual file sizes, confirm .task/.gguf exists
```

Each endpoint answers a different question — list answers "what exists," detail answers "what is it exactly," tree answers "how big are the actual files." You need all three for a confident download decision; none of them alone gives you the complete picture, which is exactly why every script we've built in this conversation chains calls across them.

