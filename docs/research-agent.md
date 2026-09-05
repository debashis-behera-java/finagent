# FINAGENT — AI Research Agent (Phase 7)

## What the agent does

`ResearchAgent` turns a free-text request + tickers into guarded research output:

```
run(request):
 1. PLAN       ResearchPlanner → ResearchPlan (deterministic, registry-constrained)
 2. EXECUTE    application services per step (market / news / risk); per-source
              failures degrade into FactEntry data gaps instead of failing the run
 3. FACTS      FactBundleBuilder → immutable FactBundle (null/NaN/stale checks)
 4. SYNTHESIZE InterpretationSynthesizer: bundle in, prose out (LLM reasons only)
 5. GUARD      SafetyGuard: ungrounded figures redacted before the result leaves
```

The run fails only when **no** ticker yields any usable facts
(`FactBundleIncompleteException` → HTTP 502) or when synthesis itself fails
(`AgentException` → HTTP 500). Persistence of runs (PENDING → RUNNING →
COMPLETED/FAILED rows) arrives with the orchestration phase — Phase 7 is in-memory.

## Architecture

```
ResearchAgent
  ├─ ResearchPlanner ──▶ ResearchPlan (tickers + PlannedSteps)
  ├─ ToolSelector ─────▶ step → MCP tool name (registry whitelist, 5 tools)
  ├─ StockDataService / NewsDataService / RiskAnalysisService  (same services
  │      the MCP server exposes — the agent and the MCP tools share code, not data)
  ├─ FactBundleBuilder ─▶ FactBundle (single source of truth)
  ├─ InterpretationSynthesizer ─▶ ChatClient (prose only, never numbers)
  └─ SafetyGuard ──────▶ GuardResult (numeric-consistency check + redaction)
```

**Dependency direction:** `agent → service → {market, news} ports`. Nothing in
`market`/`news` knows about the agent. The agent never touches provider clients,
HTTP, or API keys directly.

### Why the planner is rule-based, not LLM-based

For every ticker the planner emits the same five data-collection steps (price,
history, fundamentals, news, risk). Deterministic planning is reproducible, needs
no API key, and is trivially unit-testable. An LLM may refine step ordering in a
later phase — it will never widen the plan beyond the registry whitelist
(`ToolSelector` rejects unknown step types instead of inventing tools).

### Ticker resolution

1. Explicit tickers (normalized, de-duplicated, capped at 10 per run).
2. Otherwise, `$`-prefixed symbols extracted from the request text (`$AAPL`).
   Bare words are **not** extracted — English words ("compare ... and ...") also
   match the symbol shape, so extracting them would silently research the wrong
   tickers. No tickers from either source → `IllegalArgumentException` (HTTP 400).

## Facts in, prose out (LLM safety)

1. Tools return provider data → validated (null/NaN/stale checks) → `FactBundle`.
2. The deterministic engines (Phase 6) compute all metrics — never the LLM.
   News sentiment (Phase 9) is likewise computed deterministically per ticker and
   stored as `FactEntry.newsSentiment` — but only when the request calls for it
   (sentiment/news/coverage/comprehensive language; a pure price question skips
   it, leaving `null` = "not analyzed"). Sentiment score and counts join
   `groundedNumbers()`, so quoted sentiment figures are guard-checked like any
   other number. Full methodology: see [`sentiment-analysis.md`](sentiment-analysis.md).
3. `InterpretationSynthesizer` sends the bundle as structured input; the system
   prompt forbids inventing, estimating, or rounding figures and mandates a
   one-sentence research-not-advice disclaimer.
4. `SafetyGuard` checks every significant figure in the prose against
   `FactBundle.groundedNumbers()` (numeric match, 1e-6 relative tolerance, so
   `$150.00`, `150`, and `150.0` ground each other) and redacts ungrounded
   figures to `[redacted: ungrounded figure]`.
5. Only decimals, grouped-thousands figures, percentages, currency amounts, and
   large integers are checked — plain small integers ("top 3 risks") and calendar
   years (1900–2100) never trip the guard.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `SPRING_AI_OPENAI_API_KEY` | `test-dummy-key-not-real` | Model API key (env only). The dummy default lets the app boot and stub-backed tests run offline; real synthesis calls with it fail fast at call time. |
| `FINAGENT_AI_MODEL` | `gpt-4o-mini` | Chat model id (`finagent.ai.model` + `spring.ai.openai.chat.options.model`) |
| `FINAGENT_AI_TEMPERATURE` | `0.2` | Low temperature keeps interpretations factual and stable |
| `FINAGENT_AI_MAX_TOKENS` | `800` | Max completion tokens per interpretation |

`AiConfig` builds the research `ChatClient` from the auto-configured
`ChatClient.Builder` with the FinAgent system prompt as default. The MCP
*client* starter is on the classpath but contributes no tools with no remote
servers configured — the agent executes finance tools in-process through the
application services (a self-loop HTTP call to our own `/mcp` would add a
network failure domain for zero architectural gain).

## Testing strategy (no external dependencies)

- **Unit** (`agent/*Test`, Mockito): planner normalization/extraction/limits,
  selector whitelist, builder gaps/validation, guard pass/flag/ignore cases,
  synthesizer success/blank/failure against a mocked `ChatClient`.
- **Integration** (`ResearchAgentTest`, `@SpringBootTest` + `@MockBean ChatClient`):
  full loop on stub providers — plan → execute → bundle → synthesize → guard;
  degraded `AAPL + FAIL` run; redaction path; total-failure
  (`FAIL` only → `FactBundleIncompleteException`).
- No test touches PostgreSQL, the internet, or a real model key.

## Next

Research orchestration & history: persist runs (`PENDING → RUNNING →
COMPLETED/FAILED`), status polling, history endpoints — then REST API v1, PDF
reports, and the React dashboard consume `AgentResult`.
