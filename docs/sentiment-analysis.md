# FINAGENT — News Sentiment Analysis (Phase 9)

## 1. Architecture

```
NewsDataProvider (Phase 4, unchanged)
        │  List<NewsArticle> (title + description are the only analyzed inputs)
        ▼
SentimentAnalysisService (service layer, no HTTP, no providers)
        │  delegates scoring to the SentimentAnalyzer port
        ▼
SentimentAnalyzer ◀── LexiconSentimentAnalyzer (deterministic baseline, offline)
        │  (future: AiSentimentAnalyzer implements the same interface)
        ▼
SentimentResult (structured, explainable)
        ├──▶ FactEntry.newsSentiment → FactBundle (grounded facts for the LLM)
        ├──▶ analyze_news_sentiment MCP tool (via analyzeSymbol fetch-then-score)
        └──▶ metrics_snapshot JSON (label, score, counts, confidence, methodology)
```

Kept separate from MCP transport, market data, risk engine, orchestration, and
PDF/React (out of scope). Controllers never compute sentiment; news retrieval is
never duplicated (the agent reuses already-fetched articles; the MCP tool goes
through `NewsDataService`).

## 2. Sentiment model

`SentimentResult`: `symbol`, `label` (POSITIVE / NEUTRAL / NEGATIVE / UNAVAILABLE),
`score` (Double, null when UNAVAILABLE), `confidence`, `articleCount`,
`analyzedCount`, `unavailableCount`, `positiveCount`, `neutralCount`,
`negativeCount`, `unavailableReason`, `methodology`, per-`articles` breakdown
(title, url, label, score, positive/negative hit counts).

- **Score range:** `[-1.0, +1.0]` (−1 strongly negative, 0 neutral, +1 strongly
  positive). A score is always paired with its hit counts — never quoted alone.
- **Confidence** in `[0.0, 1.0]`: evidence strength
  (`min(1, lexiconHits / confidenceHits)`, default divisor 5), averaged over
  analyzed articles for aggregates. It is *not* a probability — low evidence
  yields low confidence by construction, and zero-hit articles score NEUTRAL
  with 0.0 confidence rather than pretending certainty.

## 3. Lexicon

Small, maintainable, overridable via `finagent.sentiment.*` properties
(`positive-terms`, `negative-terms` lists). Defaults (~30 terms per side,
whole-word, case-insensitive):

- Positive: growth, profit(s), profitable, upgrade(d/s), beat(s), strong(er),
  strength, record(s), surge(s/ing), gain(s), rally(ies), outperform, bullish,
  breakthrough, robust, soar(s), jump(s), rise(s/ing).
- Negative: loss(es), downgrade(d/s), decline(s/ing), weak(ness), miss(ed/es),
  lawsuit(s), fall(s/ing), drop(s), plunge(s), bearish, warning(s), fraud,
  bankruptcy, layoff(s), slump, tumble, crash.

Generic English words were deliberately excluded — a term is listed only if it
carries directional signal in financial prose.

## 4. Scoring formula

Per article over `title + " " + description`:

```
tokens  = lowercase words (split on non-letters, punctuation discarded)
pos, neg = lexicon hit counts (every occurrence counts — repeated terms accumulate)
hits    = pos + neg
score   = hits == 0 ? 0.0 : (pos − neg) / hits        ∈ [-1.0, +1.0]
```

## 5. Thresholds

Article and aggregate share one documented rule (defaults; configurable):

```
score ≥ +0.20 → POSITIVE      score ≤ −0.20 → NEGATIVE      else NEUTRAL
```

±0.20 keeps weak single-hit signals (e.g. 1-0 splits score ±1.0 but carry low
confidence — the label/confidence pair must always be read together) while
requiring genuinely one-sided evidence for directional buckets. Zero-hit
articles are NEUTRAL with 0.0 confidence.

## 6. Aggregation

Simple transparent **mean**: aggregate score = mean of analyzed article scores,
aggregate confidence = mean of article confidences, plus per-label counts.
No recency/position weighting — unjustified complexity for a baseline.
Articles with blank title *and* description are skipped (`unavailableCount`),
not scored.

## 7. Confidence

See §2: per-article `min(1, hits/5)`, aggregate = mean. Rationale: confidence
must grow with evidence and collapse to zero without it. It does not claim
statistical calibration.

## 8. Missing-data behavior

| Situation | Result |
|---|---|
| Null/empty article list | UNAVAILABLE / `NO_ARTICLES` |
| Provider fetch failed | UNAVAILABLE / `PROVIDER_ERROR` (never NEUTRAL) |
| Articles exist, none with usable text | UNAVAILABLE / `INSUFFICIENT_CONTENT` |
| No sentiment requested for the run | `null` ("not analyzed") — distinct from UNAVAILABLE |

## 9. Limitations

Keyword baseline only: no negation ("not strong" reads positive), no stemming
(inflections listed explicitly), whole-word match, no sarcasm/irony, small
lexicon. This is **not** professional financial NLP — every result carries its
methodology string and hit counts so its basis can be inspected. Sentiment is
scored from news content only, never from price movement.

## 10. Future AI sentiment option

`SentimentAnalyzer` is the seam: a future `AiSentimentAnalyzer` (LLM-labeled,
still returning this schema with its own methodology string) plugs in without
touching the service, agent, MCP tool, bundle, or persistence. Not implemented
in this phase — the deterministic baseline must work offline and it does.
