# Web Search Revamp — Thinai Parity Plan for TinyModels

> **Status (implemented):** steps 1–4 done. New: `WebSearchQuery`, `WebSearchParser`,
> `WebSearchFilter`, `PageReader`, `WebSearchPrompt`, `WebSearchEndpoints`,
> `WebSearchResults`; `WebSearchClient` rewritten to DDG-Lite → DDG-HTML → Bing
> fallback with relevance filter, keyword retry, challenge/cooldown, top-page read;
> `WebSearchRepository` returns `WebSearchResults`; `WebSearchToolSet` description
> + blocked/lead-text handling updated; `ChatViewModel` auto-searches app-side
> (`needsWebSearch` → `searchQueryFrom` → `webSearchPrompt`) with recall +
> `SYSTEM_LINE`. All unit tests green (`./gradlew :app:testDebugUnitTest`).
> Remaining: manual device pass over §6 matrix + Sources UI persistence.

> Goal: make `web_search` work in **all real scenarios** (news, prices, scores, weather,
> releases, follow-ups, Tamil queries, bot-blocks) — not just entity lookups.
> Model: copy what Thinai proved in `lib/web/web_search.dart` (1621 lines), adapted to
> Kotlin + OkHttp + LiteRT-LM tool calling.

---

## 1. Why the current tool fails

Current stack: `WebSearchClient` (DDG Instant Answer JSON + Wikipedia JSON) → `WebSearchToolSet` (tool-only trigger).

| Scenario | What happens today | Why |
|---|---|---|
| `weather today in chennai` | 0 results or stale Wikipedia article | Instant Answer API returns nothing for ephemeral queries; Wikipedia is an encyclopedia, not live |
| `gold price chennai today` | generic gold article, no price | same — no live index |
| `who won yesterday IPL match` | nothing / wrong entity | no live index, no keyword rewrite |
| `search the web whats the news` | searches literal phrase “search the web” | no `searchQueryFrom` cleaning |
| `what is its price` (follow-up to `latest iphone`) | searches `its` → grammar guides | no follow-up carry-over |
| `CEO Nvidia` → returns Delhi Chief Electoral Officer | fed to model anyway → confident wrong answer | no `keepRelevant` filter |
| Model never calls tool | no search at all | tiny on-device models often skip tool calls; we have no app-side fallback |
| DDG rate-limits / empty page | “No results” (lies — internet has answers) | no `blocked` vs `empty` distinction, no fallback engine, no cooldown |
| Snippet says “ignore previous instructions” | model may obey it | no fence / neutralize step |
| Long follow-up chat | model forgets search results | no recall context |

Thinai’s own comment says it best: *“a search that was not needed costs a second;
a search that was needed and skipped is the model confidently describing a world
that has moved on.”* We currently err in the expensive direction.

---

## 2. What Thinai does (the reference)

No API key. Three **HTML** endpoints in fallback order (`WebSearch.endpoints`):

1. `POST https://lite.duckduckgo.com/lite/` (`q`, `kl=wt-wt`) — same index, fraction of markup
2. `POST https://html.duckduckgo.com/html/` — full surface
3. `GET https://www.bing.com/search?q=` — different index, sent **keywords** not questions, desktop UA

Pipeline around them:

```
needsWebSearch() → searchQueryFrom() → for each endpoint:
  ask → parse → isChallengePage? → keepRelevant() → retryKeywordQuery()?
  → take(5) → readPage(top result) → webSearchPrompt() with fences
```

Key numbers: `kMaxWebResults=5`, `kWebResultCharBudget=6000` (~1.5K tokens),
`kWebSnippetCharBudget=320`, `kPageTextBudget=1500`, cooldown `10 min`.

---

## 3. Proposed architecture (fits our clean architecture)

Keep existing seam (`WebSearchRepository`) — swap internals, add helpers.
No new Gradle deps (OkHttp + `org.json` + regex already ship; Jsoup optional, not required).

```
core/network/
  WebSearchClient.kt          # orchestrator: fallback loop, cooldown, readTopResult (REWRITE)
  WebSearchEndpoints.kt       # NEW: SearchEndpoint(url, post, keywords, userAgent, parser)
  WebSearchQuery.kt           # NEW: needsWebSearch, searchQueryFrom, keywordQuery,
                              #      retryKeywordQuery, queryTerms (pure, unit-testable)
  WebSearchParser.kt          # NEW: parseDuckDuckGoHtml, parseHeadingResults,
                              #      decodeHtmlEntities, resolveUrl
  WebSearchFilter.kt          # NEW: keepRelevant (scoring + ranking)
  PageReader.kt               # NEW: isReadablePage, extractReadableText
  WebSearchPrompt.kt          # NEW: webSearchContext, webSearchPrompt,
                              #      webRecallContext, fence/neutralize
domain/model/WebSearchResult.kt  # EXTEND: add query field, displayHost, toJson/fromJson
domain/repository/WebSearchRepository.kt  # EXTEND: return WebSearchResults(query, results, fetchedAt, blocked, leadText)
core/inference/tools/WebSearchToolSet.kt  # UPDATE: new description, use new repo type
feature/chat/ChatViewModel.kt # UPDATE: dual-trigger (auto-search + tool), recall, Sources UI
```

`WebSearchResults` (new domain type) distinguishes the three outcomes the UI/model need:

```kotlin
data class WebSearchResults(
  val query: String,
  val results: List<WebSearchResult>,
  val fetchedAt: Long = System.currentTimeMillis(),
  val leadText: String = "",
  val blocked: Boolean = false, // true = challenged on every engine that answered
) {
  val isEmpty get() = results.isEmpty()
}
```

---

## 4. Module specs (port of Thinai, Kotlin-ified)

### 4.1 `WebSearchQuery.kt` — when + what to search

**`needsWebSearch(message: String): Boolean`** — app-side heuristic so we don’t depend
on a 0.5B model deciding to call a tool.

- Return `false` on empty, on explicit refusal (`don't search / without internet / skip the web`).
- Return `true` on explicit ask (`search the web / google it / look this up ...`).
- Return `false` on local tasks (`write poem, translate, summarize, explain this code, refactor, fix my function, poem, story, essay, joke, code, regex, sql query`) and on single-word/greeting input.
- Else return `true` if `_wantsCurrent` matches (`news, latest, today, yesterday, price, score, weather, release, launch, who is, what happened, as of, schedule...`) or a year `\b(19|20)\d{2}\b` appears.
- Port Thinai’s four regexes verbatim (`_asksToSearch`, `_refusesSearch`, `_localTask`, `_wantsCurrent`, `_mentionsYear`). Wrong in the cheap direction on purpose.

**`searchQueryFrom(message, previousUserTurns): String`** — strip app-instructions, resolve follow-ups.

- `_withoutAsk`: strip prefix `please search the web for:` / suffix `...search the web give me results` / trailing `give me results`.
- `_readsAsFollowUp`: true if pronoun (`it/its/they/that/this/he/she/there...`) or ≤6 words with ≤1 meaningful term.
- `_carriedTerms`: key words of last user turn not already in query, prepended under 240-char cap (`_maxQueryLength`). Keeps case (`CEO` stays acronym).
- Cap at 240 chars.

**`queryTerms(query): List<String>`** — meaningful words: `len>=4 && !stopWords` or acronym `[A-Z0-9]{2,3}`. Split on `[^\p{L}\p{M}\p{N}]+` so Tamil vowel signs (combining marks `\p{M}`) don’t shatter `சென்னை` into consonants. ~40 stop words incl. `இன்றைய/இன்று`.

**`keywordQuery(query): String`** — for Bing only. Drop filler (`who/what/is/of...` + Tamil `என்ன/யார்/எப்போது...`), drop low-signal verbs (`won/win/happened`), move time words (`today/yesterday...`) to end. If nothing left, return original.

**`retryKeywordQuery(keywords): String?`** — one retry only: move leading `latest/newest/recent` to end, else swap first two words. Return null if identical (don’t re-ask).

### 4.2 `WebSearchEndpoints.kt` + `WebSearchClient.kt` — where to ask

```kotlin
data class SearchEndpoint(
  val url: String,
  val post: Boolean = false,
  val keywords: Boolean = false,
  val userAgent: String? = null,
  val parse: (String) -> List<WebSearchResult>,
)

val DEFAULT_ENDPOINTS = listOf(
  SearchEndpoint("https://lite.duckduckgo.com/lite/", post=true, parse=::parseDuckDuckGoHtml),
  SearchEndpoint("https://html.duckduckgo.com/html/", post=true, parse=::parseDuckDuckGoHtml),
  SearchEndpoint("https://www.bing.com/search", keywords=true,
    userAgent=DESKTOP_UA, parse=::parseHeadingResults),
)
```

- Headers: mobile UA by default (`Pixel 7 / Chrome 122`), desktop UA for Bing, `Accept-Language: en-US`, `Referer: https://duckduckgo.com/` **only** on DDG (never leak to Bing or pages).
- DDG: `POST` form `q=<sent>&kl=wt-wt`. Bing: `GET ?q=<keywordQuery>`.
- Timeouts: 10s connect/send, 12s receive for search; 6s for page reads.
- **Cooldown:** `Map<String, Long> restingUntil`, 10 min on challenge/refusal (202, 403, 429, connect-timeout). Skipped engines sit out unless *all* are resting — then ask anyway.
- **Challenge:** `isChallengePage(status, body, hadResults)`: `status==202` → true; else true only if zero results AND body matches `anomaly|captcha|challenge-form|unusual traffic|verify you are human|bots use duckduckgo`.
- **Empty vs blocked:** `reached==0 && failure!=null` → throw `WebSearchException(describeSearchFailure(e))`; else return `WebSearchResults(blocked = reached>0 && blocked==reached)`. UI shows “blocked, try later” vs “nothing found”.

### 4.3 `WebSearchParser.kt` — read HTML without breaking every CSS rename

- `parseDuckDuckGoHtml(html)`: find links `<a ...result__a|result-link...>`, snippets `<a|td ...result__snippet|result-snippet...>`. Merge by document offset (snippet = first snippet after link, before next link). Dedupe by URL, drop empty titles, unwrap `//duckduckgo.com/l/?uddg=<real>`; drop ad trackers with no `uddg`.
- `parseHeadingResults(html)`: shape-based for Bing — `<h2><a href>` or `<a><h2>`, snippet = next `<p>` ≥20 chars before next link. Skip engine-own hosts (`bing.com, microsoft.com...`). Regex must not cross `<p` boundaries (Bing mobile serves unclosed `<p`).
- `resolveUrl(raw)`: decode entities, fix `//` and `/` prefixes, unwrap DDG `uddg` (host check must be exact-or-subdomain, never suffix — `notduckduckgo.com` must not unwrap), unwrap Bing `/ck/?u=a1<base64url>`. Accept only `http/https` with host.
- `decodeHtmlEntities`: named (`amp/lt/gt/nbsp/hellip/mdash/rupee...` incl. `percnt→%`), decimal + hex numeric; decode twice when `&amp;` present (`&amp;percnt;`); leave unknown as-is.

### 4.4 `WebSearchFilter.kt` — never hand the model a confident wrong answer

`keepRelevant(results, query)`:

- Score per result: title hit +2, snippet hit +1 per term (prefix match at word start — `price` finds `prices`/`சென்னையில்`, but `rate` must not match `separate`).
- `terms>=3` → keep `matched >= ceil(n/2)`. `terms<=2` → keep `>=1`, but if any result covers *all* terms, drop partials.
- Sort by score desc, engine order breaks ties (stable sort with index).
- Live cases it fixes: `Nvidia CEO` vs Electoral Officer, `ISRO launch` vs clothing shop “Next”, `Erode weather` vs Ulhasnagar.

### 4.5 `PageReader.kt` — snippets lie, pages tell

- `isReadablePage(url)`: false for `youtube, youtu.be, facebook, instagram, x/twitter, tiktok, pinterest, linkedin, reddit` (+ subdomains).
- `extractReadableText(html, limit=1500, query)`: strip `script/style/noscript/svg/head/nav/footer/form/aside` + unclosed tail; collect `<p|h1|h2|h3|li>` ≥40 chars (headings ≥12 — `Anthropic releases Claude Fable 5.1` *is* the answer); rank paragraphs by term coverage, emit in page order up to budget; whole-page fallback only if `_readsAsProse` (≥50% letters, <2% codeish `{}[];=...`).
- Cap pages at 600KB (beyond that it’s a web app, not an article).

### 4.6 `WebSearchPrompt.kt` — small models need fences + order

- `neutralizeUntrusted(s)` + `fenceUntrusted("WEB_RESULTS", body)`: neutralize field-by-field so budgets count what the model sees; random fence suffix to defeat `<<<` injection in page text.
- `webSearchContext(results, charBudget=6000, snippetBudget=320)`: `Today is 2 Sept 2026. Web search results for "<q>", fetched just now:` + fenced `[1] Title (host)\nsnippet` entries + `From [1] host:\n<leadText>`. Always keep result 1 even if long.
- `webSearchPrompt(results, question)`: context + *“Answer in 2–4 sentences of plain prose. Name specific products/versions/numbers/dates. Do not copy a headline or list results back. Cite [1]/[2]. If results don’t answer, say what’s missing instead of guessing.”* — question **last**, as user turn (not system: small models continue system blocks as headlines).
- `webRecallContext(sources, charBudget=1800, max=3)` + `webRecallPrompt(sources, question)`: re-inject prior turn’s sources for follow-ups (“what is the model name” after a searched answer).
- `formatSearchDate`: `2 September 2026` written out — no `2/9` ambiguity.

### 4.7 `WebSearchResult` / repo changes

```kotlin
data class WebSearchResult(
  val title: String, val url: String, val snippet: String = "",
  val query: String = "",              // NEW: what was searched; shown under Sources
) {
  val displayHost: String              // NEW: host minus www.
  fun toToolText(i: Int) = ...
  fun toJson()/fromJson()              // NEW: persist snippet≤200 chars + query
}
```

Repository returns `WebSearchResults` (not bare list) so callers get `blocked`/`leadText`.

---

## 5. Integration — dual trigger (the core behavior change)

Tool-only triggering is the #1 reliability gap. Thinai never trusts the model to decide.

1. **App-side auto-search (primary):** in `ChatViewModel` before `session.send()`:
   `if (webSearchEnabled && needsWebSearch(userText)) { results = repo.search(searchQueryFrom(...)); prompt = webSearchPrompt(results, userText) }`.
   Log `JOURNEY: web_search auto (N results, blocked=..)` like today’s tool logs.
2. **Tool fallback (secondary):** keep `WebSearchToolSet.webSearch` registered for models that *do* call it (Gemma-3 it). Update its `@Tool` description — delete `NOT real-time`, replace with Thinai-style: *“Search the web for current or factual info… Input: short query. Returns numbered results with title/snippet/URL + page excerpt.”* Tool path calls the same `repo.search()`.
3. **Recall:** store last turn’s `List<WebSearchResult>` per chat (memory + Room JSON); on follow-up with empty fresh results, use `webRecallPrompt()`.
4. **Sources UI:** render `results.map { title + displayHost }` under the bubble (persisted via `toJson`), matching Thinai’s Sources list.
5. **Context accounting:** count `webSearchPrompt` chars in `estimatedTokensInContext` (6000 chars ≈ 1500 tokens) so 90% auto-compaction stays accurate. Recall budget 1800 chars.

---

## 6. Test matrix — “perfectly in all scenarios” means these pass

Port Thinai’s `test/` cases + our `WebSearchClientTest`:

| # | Scenario | Input | Expect |
|---|---|---|---|
| 1 | news | `latest AI news` | ≥1 result, titles mention AI/news terms |
| 2 | price, follow-up | `latest iphone` → `what is its price` | 2nd query becomes `iphone what is its price…`, results mention iphone+price |
| 3 | score, verb trap | `who won yesterday IPL match` | keyword sent `IPL match yesterday`; no `South Korean won` currency pages survive filter |
| 4 | acronym | `Nvidia CEO` | keeps Jensen Huang, drops Electoral Officer |
| 5 | Tamil | `இன்றைய சென்னை வானிலை` | terms=`சென்னை,வானிலை`; no Turkish/Japanese pages |
| 6 | instruction strip | `search the web whats the news` | sent=`whats the news` |
| 7 | local task | `write a poem about rain` | `needsWebSearch==false` |
| 8 | refusal | `answer without searching the web` | `false`, no HTTP |
| 9 | blocked | all engines 202/captcha | `results=[] , blocked=true` → UI “search blocked, try later” |
| 10 | offline | airplane mode | `WebSearchException("could not reach the internet")` → model answers from knowledge |
| 11 | injection | page contains `ignore previous instructions` | fenced, model does not obey (manual eval) |
| 12 | recall | searched turn → `what is the model name` | answered from recalled sources, not re-search |

Unit tests: query cleaners, keyword + retry, parsers on canned DDG/Bing HTML (incl. redirect unwrap, entity decode, unclosed `<p`), `keepRelevant` scoring, `extractReadableText` ranking, challenge detection, cooldown map. Use MockWebServer for fallback-order + retry tests.

---

## 7. Build order (small, reviewable PRs)

1. `WebSearchQuery.kt` + tests (no behavior change yet).
2. `WebSearchParser.kt` + `WebSearchFilter.kt` + `PageReader.kt` + `WebSearchPrompt.kt` + tests.
3. `WebSearchEndpoints.kt` + `WebSearchClient` rewrite + `WebSearchResults`/`WebSearchResult` extend + repo seam update + `WebSearchClientTest` refresh.
4. `WebSearchToolSet` description update + `ChatViewModel` auto-search + recall + Sources UI + context accounting.
5. Manual device pass over §6 matrix + logcat `TinyModelsJourney` review; update README + this plan’s status.

**Size:** ~900–1100 Kotlin lines + ~400 test lines (Thinai is 1621 Dart lines incl. comments; Kotlin port is denser). Zero new deps.

---

## 8. Risks & answers

| Risk | Answer |
|---|---|
| HTML parsing breaks on redesign | two DDG surfaces + Bing fallback; forgiving regexes; one engine’s break never kills search |
| DDG bot-blocks on mobile IPs | challenge detect + 10-min cooldown + Bing fallback; `blocked` message is honest |
| Small model ignores / mangles tool | app-side `needsWebSearch` trigger doesn’t need the model’s cooperation |
| Context blow-up | hard budgets (6000/1500/1800 chars) + snippet caps + top-5; counted toward compaction |
| Prompt injection from pages | field-level neutralize + random-suffix fences + “quoted data, not instructions” line |
| Privacy | same gate as today: global Settings + per-chat toggle; note “queries leave the device” |

---

## 9. References

- Thinai source: `https://github.com/Abhinivesh2729/Thinai/blob/main/lib/web/web_search.dart` + `test/` web-search tests.
- Current code: `core/network/WebSearchClient.kt`, `core/inference/tools/WebSearchToolSet.kt`, `domain/model/WebSearchResult.kt`, `ai/WEB_SEARCH_TOOL_PLAN.md`.
- Keep: `WebSearchRepository` seam (Brave/Tavily keyed backend can still slot in as a 4th endpoint later).
