# FINAGENT — Backend Architecture Design (Java 21 · Spring Boot 3.3.x)

> Companion to `ARCHITECTURE.md` (system-level) and `PHASES.md` (delivery plan).
> This is the **implementation-facing backend design**: packages, modules, classes, interfaces,
> entities, endpoints, MCP layer, agent workflow, data flow, dependency rules, phases.
> Status: **implemented through Phase 17** (verified `mvn clean verify` green,
> 333 backend + 42 frontend unit tests, plus 18 real PostgreSQL Testcontainers ITs
> via `-Ppostgres-integration`; real PostgreSQL via Testcontainers in Phase 13,
> Docker Compose in Phase 12, GitHub Actions CI in Phase 14, JWT auth in Phase 16,
> final hardening in Phase 17). The design below is
> the as-built reference; `PHASES.md` is the live roadmap.

---

## 1. Complete Package Structure

```
backend/src/main/java/com/finagent/
├── FinAgentApplication.java
│
├── config/                          # Wiring & conventions (no business logic)
│   ├── FinAgentProperties.java      # @ConfigurationProperties tree (providers, timeouts, AI, report)
│   ├── OpenApiConfig.java
│   ├── WebConfig.java               # CORS
│   ├── AsyncConfig.java             # ThreadPoolTaskExecutor for research runs
│   ├── MarketProviderConfig.java    # adapter bean selection (alpha-vantage | stooq | stub)
│   ├── NewsProviderConfig.java      # adapter bean selection (newsapi | gnews | stub)
│   └── AiConfig.java                # Spring AI ChatClient + tool callback wiring
│
├── controller/                      # Thin REST layer — zero business logic
│   ├── HealthController.java        # TEMP smoke endpoint, removed in Phase 9
│   ├── ResearchController.java
│   ├── PortfolioController.java
│   ├── StockController.java
│   └── ReportController.java
│
├── dto/                             # API border — never leak JPA entities
│   ├── request/
│   │   ├── ResearchRequestDto.java
│   │   ├── PortfolioCreateRequest.java
│   │   └── HoldingDto.java
│   ├── response/
│   │   ├── ResearchAcceptedDto.java         # 202 + researchId
│   │   ├── ResearchStatusDto.java
│   │   ├── ResearchResultDto.java
│   │   ├── QuoteDto.java
│   │   ├── HistoryDto.java
│   │   ├── FundamentalsDto.java
│   │   ├── PortfolioDto.java
│   │   ├── PageResponse.java
│   │   └── ReportDownloadMeta.java
│   └── common/
│       └── ApiError.java            # canonical error shape
│
├── exception/
│   ├── GlobalExceptionHandler.java  # @RestControllerAdvice
│   ├── ApiError.java
│   ├── ResourceNotFoundException.java
│   ├── ProviderException.java       # upstream market/news provider failures
│   ├── ProviderTimeoutException.java
│   ├── McpToolExecutionException.java
│   ├── FactBundleIncompleteException.java
│   └── ReportGenerationException.java
│
├── service/                         # Use-case orchestration
│   ├── ResearchService.java         # submit/list/get research runs
│   ├── ResearchOrchestrator.java    # async lifecycle: PENDING → RUNNING → COMPLETED/FAILED
│   ├── PortfolioService.java        # portfolio CRUD + analysis trigger
│   ├── StockDataService.java        # passthrough: controller → MCP tools → ports
│   └── ReportService.java           # report metadata lookup + PDF streaming support
│
├── agent/                           # AI research agent (LLM reasons; never sources numbers)
│   ├── ResearchAgent.java           # top-level run entrypoint
│   ├── ResearchPlanner.java         # builds ResearchPlan from user request
│   ├── ToolSelector.java            # plan step → McpTool (registry-constrained selection)
│   ├── FactBundleBuilder.java       # validated raw data → FactBundle
│   ├── InterpretationSynthesizer.java # ChatClient: facts+metrics in, prose out
│   ├── SafetyGuard.java             # numeric-consistency check on LLM output
│   ├── prompts/                     # prompt templates (resources / constants)
│   └── model/
│       ├── ResearchPlan.java
│       ├── PlannedStep.java
│       ├── AgentContext.java
│       ├── FactBundle.java          # the immutable facts contract (see §10)
│       └── FactEntry.java
│
├── mcp/                             # In-process MCP-style tool layer
│   ├── McpTool.java                 # tool SPI (interface)
│   ├── McpToolDescriptor.java       # name, description, input schema, output type
│   ├── McpToolRegistry.java         # discovery + execution + audit hookpoint
│   ├── McpToolResult.java           # typed result + status + duration
│   ├── McpToolAuditor.java          # persists ToolExecution rows
│   └── tools/
│       ├── GetStockPriceTool.java
│       ├── GetStockHistoryTool.java
│       ├── GetStockFundamentalsTool.java
│       ├── SearchFinancialNewsTool.java
│       ├── AnalyzeStockRiskTool.java
│       └── AnalyzePortfolioTool.java

│
├── market/                          # Port + adapters (Hexagonal)
│   ├── MarketDataProvider.java      # PORT (interface)
│   ├── dto/
│   │   ├── Quote.java
│   │   ├── HistoricalBar.java
│   │   ├── HistoricalSeries.java
│   │   └── Fundamentals.java
│   └── adapter/
│       ├── AlphaVantageMarketAdapter.java
│       ├── StooqMarketAdapter.java
│       └── StubMarketDataProvider.java   # deterministic offline dev/test adapter
│
├── news/                            # Port + adapters + deterministic sentiment
│   ├── NewsDataProvider.java        # PORT (interface)
│   ├── SentimentAnalyzer.java       # PORT (interface)
│   ├── LexiconSentimentAnalyzer.java     # deterministic default
│   ├── LlmSentimentLabeler.java          # optional, clearly flagged, non-authoritative
│   ├── dto/
│   │   ├── NewsArticle.java
│   │   └── SentimentScore.java
│   └── adapter/
│       ├── NewsApiAdapter.java
│       ├── GNewsAdapter.java
│       └── StubNewsDataProvider.java
│
├── analysis/                        # 100% deterministic, pure, unit-tested math
│   ├── ReturnsCalculator.java       # daily/period returns
│   ├── VolatilityCalculator.java    # annualized std-dev
│   ├── DrawdownCalculator.java      # max drawdown
│   ├── BetaCalculator.java          # vs benchmark series
│   ├── SharpeCalculator.java
│   ├── TrendAnalyzer.java           # SMA-based trend classification
│   ├── StockRiskEngine.java         # composite per-stock risk score (documented weights)
│   └── MetricResult.java            # value + confidence + "insufficient data" semantics
│
├── portfolio/
│   ├── PortfolioAnalyzer.java
│   ├── WeightCalculator.java
│   ├── SectorConcentrationAnalyzer.java
│   └── DiversificationScore.java
│
├── report/
│   ├── PdfReportGenerator.java      # PDFBox; numbers ONLY from ReportData
│   ├── ReportSectionBuilder.java    # mandated sections incl. disclaimer
│   ├── DisclaimerSection.java       # mandatory, non-removable
│   ├── model/
│   │   ├── ReportData.java          # FactBundle + engine metrics + interpretation
│   │   └── ReportSection.java
│   └── ReportMetadataService.java   # persists path, checksum, pages, generated-at
│
├── repository/                      # Spring Data JPA
│   ├── ResearchRequestRepository.java
│   ├── ResearchResultRepository.java
│   ├── PortfolioRepository.java
│   ├── PortfolioHoldingRepository.java
│   ├── StockSnapshotRepository.java
│   ├── ToolExecutionRepository.java
│   └── ReportMetadataRepository.java
│
├── model/                           # JPA entities + enums (Phase 2)
│   ├── ResearchRequest.java
│   ├── ResearchResult.java
│   ├── Portfolio.java
│   ├── PortfolioHolding.java
│   ├── StockSnapshot.java
│   ├── ToolExecution.java
│   ├── ReportMetadata.java
│   └── enums/
│       ├── ResearchStatus.java      # PENDING, RUNNING, COMPLETED, FAILED
│       └── RiskLevel.java
│
└── common/
    ├── TickerNormalizer.java        # uppercase/trim; shared validation helper
    └── AppClock.java                # injectable Clock (testable time)

backend/src/main/resources/
├── application.yml                  # base config
├── application-dev.yml              # stub providers, no PostgreSQL requirement
├── application-test.yml
└── db/migration/                    # Flyway V1__init.sql … (Phase 2)
```

---

## 2. Main Modules

| Module (packages) | Nature | Talks to |
|---|---|---|
| **API layer** (`controller`, `dto`, `exception`) | Inbound REST border | services only |
| **Service layer** (`service`) | Use-case orchestration, async lifecycle | agent, portfolio, report, mcp, repositories |
| **Agent** (`agent`) | AI planning + synthesis + guardrails | mcp registry, ChatClient, analysis outputs |
| **MCP layer** (`mcp`) | Typed in-process tool registry | market/news ports, analysis engine |
| **Market data** (`market`) | Port + swappable adapters | external HTTP (Alpha Vantage / Stooq) |
| **News + sentiment** (`news`) | Port + adapters + lexicon scoring | external HTTP (NewsAPI / GNews) |
| **Analysis engine** (`analysis`) | Deterministic math, no I/O | nothing (pure functions) |
| **Portfolio analytics** (`portfolio`) | Deterministic math over holdings | analysis primitives |
| **Report** (`report`) | PDFBox rendering + metadata persistence | ReportData structures |
| **Persistence** (`repository`, `model`) | JPA entities + Spring Data | PostgreSQL |
| **Cross-cutting** (`config`, `common`) | Wiring, properties, CORS, OpenAPI, clock | all (via constructor injection) |

## 3. Responsibilities of Each Module

| Package | Responsibility | Explicitly NOT responsible for |
|---|---|---|
| `controller` | HTTP mapping, input validation (`@Valid`), status codes, delegation | business logic, entity exposure |
| `dto` | Request/response records for API border | containing JPA entities or provider payloads |
| `exception` | Domain exceptions + one global handler → consistent `ApiError` JSON | per-controller error logic |
| `service` | Use cases: research submit/track, portfolio CRUD, report lookup; async run lifecycle | computation of metrics (delegates to `analysis`/`agent`) |
| `agent` | Research planning, tool selection, FactBundle assembly, LLM interpretation, safety guard | sourcing any number, computing metrics, rendering |
| `mcp` | Tool registration/discovery, typed execution contract, failure typing, execution audit | business rules inside tools |
| `market` | Quote/history/fundamentals via provider adapters; retry/timeout; normalization | analysis, persistence |
| `news` | Article search via adapters; lexicon (deterministic) sentiment; optional LLM labeling | analysis beyond sentiment |
| `analysis` | Returns, volatility, drawdown, beta, Sharpe, trend, composite risk score — deterministic | I/O, LLM calls, persistence |
| `portfolio` | Weights, sector concentration, diversification score | market data fetching |
| `report` | PDFBox report with all mandated sections + non-removable disclaimer; metadata | deciding what the numbers are |
| `repository` | Spring Data query methods | schema decisions (Flyway owns schema) |
| `model` | JPA entities, enums, embedded value objects | API serialization |
| `config` | Properties tree, bean selection, CORS, OpenAPI, executor | anything runtime-behavioral |

## 4. Important Classes (and why they matter)

| Class | Role |
|---|---|
| `FinAgentApplication` | Boot entrypoint; `@ConfigurationPropertiesScan` |
| `FinAgentProperties` | Single typed config tree: `market.provider`, `market.timeout`, `news.*`, `ai.model`, `report.output-dir`; every field env-overridable |
| `ResearchOrchestrator` | Owns run lifecycle: persists `PENDING` → executes on async executor → `RUNNING` → `COMPLETED`/`FAILED`; heart of Phase 11 |
| `ResearchAgent` | Facade: plan → select tools → execute via registry → build FactBundle → run analysis → synthesize → persist result |
| `McpToolRegistry` | Discovers all `McpTool` beans at startup; exposes descriptors (for LLM + OpenAPI) and executes with timing + audit + typed failures |
| `FactBundle` | Immutable snapshot of validated facts + computed metrics per run; the **single source of truth** for LLM and PDF |
| `StockRiskEngine` | Deterministic weighted composite score (volatility, drawdown, beta, sector concentration, data quality); formula published in the report |
| `PdfReportGenerator` | Renders PDF strictly from `ReportData`; embeds disclaimer; writes metadata (checksum, pages) |
| `GlobalExceptionHandler` | Maps exceptions → `ApiError` JSON: 404 (resource), 400 (validation), 502 (provider), 500 (unexpected) |

## 5. Interfaces (Ports & SPIs)

| Interface | Methods (conceptual) | Implementations | Purpose |
|---|---|---|---|
| `MarketDataProvider` | `getQuote(ticker)`, `getHistory(ticker, from, to)`, `getFundamentals(ticker)` | `AlphaVantageMarketAdapter`, `StooqMarketAdapter`, `StubMarketDataProvider` | Swappable market data source; selected via config; keys from env only |
| `NewsDataProvider` | `searchNews(queryOrTicker, from, to, limit)` | `NewsApiAdapter`, `GNewsAdapter`, `StubNewsDataProvider` | Swappable news source |
| `SentimentAnalyzer` | `score(text) → SentimentScore` | `LexiconSentimentAnalyzer` (default, deterministic), `LlmSentimentLabeler` (optional) | Sentiment stays pluggable and explainable |
| `McpTool` | `descriptor()`, `execute(typedInput) → McpToolResult` | 6 tool classes (§8) | Tool SPI; adding a tool = implement + register, zero agent changes |
| `PdfReportGenerator` *(port in Phase 10)* | `generate(ReportData) → Path` | `PdfBoxReportGenerator` | Rendering backend swappable |
| Spring Data repositories | derived queries | generated | Persistence ports for free |

**Rule:** every external touchpoint (HTTP providers, LLM, time via `AppClock`) sits behind an interface.
Adapters never leak their client types upward; tools normalize provider payloads into market/news DTOs.

## 6. Database Entities (PostgreSQL, Flyway-managed)

| Table / Entity | Key columns | Notes |
|---|---|---|
| `research_requests` → `ResearchRequest` | id (UUID), request_text, tickers[], portfolio_id FK?, status, created_at, started_at, finished_at | status = `PENDING/RUNNING/COMPLETED/FAILED`; one row per research run |
| `research_results` → `ResearchResult` | id, request_id FK (1:1), executive_summary, interpretation (LLM prose), metrics_snapshot (JSONB), sentiment_summary, created_at | metrics_snapshot is engine output — the authoritative numbers |
| `portfolios` → `Portfolio` | id, name, created_at | |
| `portfolio_holdings` → `PortfolioHolding` | id, portfolio_id FK, ticker, quantity, cost_basis, sector | |
| `stock_snapshots` → `StockSnapshot` | id, research_request_id FK, ticker, payload (JSONB: quote/fundamentals/history meta), captured_at | audit trail: what data the run actually saw |
| `tool_executions` → `ToolExecution` | id, research_request_id FK, tool_name, input_hash, status, duration_ms, error_message, executed_at | MCP observability; inputs/outputs redacted of keys |
| `report_metadata` → `ReportMetadata` | id, research_request_id FK, file_path, checksum, page_count, generated_at | PDF exists on disk; DB holds metadata |

Relationships: `ResearchRequest 1—1 ResearchResult`, `1—N StockSnapshot`, `1—N ToolExecution`, `1—1 ReportMetadata`; `Portfolio 1—N PortfolioHolding`.

## 7. API Endpoints (v1, under `/api/v1`)

| Method | Path | Purpose | Response |
|---|---|---|---|
| `POST` | `/research` | submit research request (text + optional tickers / portfolioId) | **202** + `ResearchAcceptedDto {researchId}` |
| `GET` | `/research` | run history | paged `ResearchStatusDto` |
| `GET` | `/research/{id}` | status; when COMPLETED, includes result + interpretation | `ResearchStatusDto` / `ResearchResultDto` |
| `POST` | `/portfolio` | create portfolio + holdings | 201 + `PortfolioDto` |
| `GET` | `/portfolio/{id}` | portfolio + diversification analysis | `PortfolioDto` + metrics |
| `GET` | `/stocks/{ticker}/price` | latest quote (through `GetStockPriceTool`) | `QuoteDto` |
| `GET` | `/stocks/{ticker}/history` | historical series | `HistoryDto` |
| `GET` | `/stocks/{ticker}/fundamentals` | fundamentals | `FundamentalsDto` |
| `GET` | `/reports/{researchId}` | PDF download | `application/pdf` stream |
| `GET` | `/health` *(temp, Phase 1 only)* | smoke test | `{"status":"UP","application":"FINAGENT"}` |

Errors always return `ApiError {timestamp, status, error, message, path, fieldErrors[]}` via `GlobalExceptionHandler`.
Docs: springdoc UI at `/swagger-ui.html`.

## 8. MCP Architecture (in-process, typed, bridged to Spring AI)

**Decision (from ARCHITECTURE.md §3.1):** MCP tools are typed Java components in an internal
`McpToolRegistry` — same architectural properties as a standalone MCP server (discovery, typed
contracts, late binding, pluggability) without transport complexity. Bridging to a real MCP server
later requires no changes to tool implementations.

```
                       ┌────────────────────────────────────────────┐
   ToolSelector ──────▶│              McpToolRegistry               │
   ("which tools       │  discover() → McpToolDescriptor[]          │
    exist?")           │  execute(name, input) → McpToolResult      │
                       │    ├── validates input vs descriptor       │
                       │    ├── times execution                     │
                       │    ├── McpToolAuditor → tool_executions    │
                       │    └── maps failures → typed exceptions    │
                       └───────┬──────────┬──────────┬──────────────┘
                               │          │          │
                    ┌──────────▼──┐ ┌─────▼──────┐ ┌─▼──────────────┐
                    │ price/hist/ │ │ news tool  │ │ risk/portfolio │
                    │ fundamentals│ │ (news port)│ │ (analysis eng.)│
                    │ tools       │ └────────────┘ └────────────────┘
                    └──────┬──────┘
                           │ (ports)
                    MarketDataProvider / NewsDataProvider adapters
```

Key properties:
- **Typed contracts** — each tool declares a `McpToolDescriptor` (name, description, input schema,
  output type). Descriptors feed both the LLM's tool list and OpenAPI docs.
- **Genuine tool calling** — Spring AI tool callbacks wrap registry tools, so the LLM *chooses*
  tools; orchestration is never hardcoded in prompt text.
- **Failure handling** — provider timeouts/HTTP failures become `McpToolExecutionException` with a
  cause category; tools may return partial results flagged in `McpToolResult` (never silent gaps).
- **Auditability** — every execution row: tool, input hash, status, duration, error, timestamp.

**The six tools:** `get_stock_price`, `get_stock_history`, `get_stock_fundamentals`,
`search_financial_news`, `analyze_stock_risk`, `analyze_portfolio`.

## 9. Agent Workflow

```
ResearchAgent.run(request):
 1. PLAN          ResearchPlanner → ResearchPlan (PlannedStep list: which data, which tickers,
                  whether portfolio analysis; LLM-assisted, constrained to registry tool list)
 2. SELECT        ToolSelector maps each step → concrete McpTool via registry (LLM may refine
                  selection; registry is the whitelist — no invented tools)
 3. EXECUTE       registry.execute(...) per step; retry/timeout inside adapters;
                  failures recorded, optionally degraded (run continues with flagged gaps)
 4. FACTS         FactBundleBuilder validates raw results (null/NaN/stale checks) → immutable
                  FactBundle; StockSnapshot rows persisted (audit trail)
 5. ANALYZE       analysis + portfolio engines compute ALL metrics deterministically from bundle
 6. SYNTHESIZE    InterpretationSynthesizer: ChatClient receives FactBundle + metrics (structured)
                  → returns interpretation prose ONLY
 7. GUARD         SafetyGuard: numeric-consistency check — any number asserted by the LLM that is
                  absent from the bundle flags a violation → strip/redact; disclaimer enforced
 8. PERSIST       ResearchResult saved (metrics_snapshot + guarded interpretation); COMPLETED
 9. REPORT        (Phase 10/11) PdfReportGenerator renders from bundle+metrics; metadata saved
```

Failure at any step ⇒ orchestrator marks the run `FAILED` with a structured error; partial
artifacts (FactBundle, snapshots, tool executions) are preserved for debugging.

## 10. Data Flow — user request → MCP → analysis → PDF

```
User (React)                    Backend                                   External
────────────                    ───────                                   ────────
POST /api/v1/research ──▶ ResearchController (validate DTO)
        ◀── 202 {researchId}  ResearchService: persist PENDING row
                              ResearchOrchestrator: async submit
                                  │
                                  ▼
                          ResearchAgent.run()                       Alpha Vantage / Stooq
                          ├─ plan                                   NewsAPI / GNews
                          ├─ registry.execute(tools) ○──adapters──▶ (HTTP, keys from env,
                          │      │   audit → tool_executions         retry/timeout in adapter)
                          │      ▼
                          │  FactBundleBuilder ◀── validated market/news DTOs
                          │      │  snapshots → stock_snapshots
                          │      ▼
                          │  analysis/portfolio engines (pure math)
                          │      │  metrics: returns, vol, drawdown, beta, Sharpe,
                          │      │           trend, risk score, diversification
                          │      ▼
                          │  InterpretationSynthesizer ──prompt──▶ OpenAI (Spring AI)
                          │      │  ◀── interpretation prose only ─┘
                          │  SafetyGuard (numbers must exist in bundle)
                          │      ▼
                          │  ResearchResult persisted; status COMPLETED
                          │      ▼
                          │  PdfReportGenerator (PDFBox) ◀── ReportData(bundle+metrics+prose)
                          │      │  PDF on disk; metadata → report_metadata
                          ▼      ▼
GET /api/v1/research/{id} ─▶ status/result JSON    GET /api/v1/reports/{id} ─▶ PDF stream
```

**Invariant (LLM safety, "facts in, prose out"):** every number in the UI or PDF originates from
the `FactBundle`/engine output. The LLM only writes prose; the PDF never parses numbers out of prose.

## 11. Dependency Relationships

```
controller ──▶ service ──┬─▶ agent ──▶ mcp ──▶ (market, news ports) ──▶ adapters
                         ├─▶ portfolio ──▶ analysis
                         ├─▶ report
                         └─▶ repository ──▶ model

dto: used by controller + service only          config: wired into everything (DI)
exception: implemented in exception/, thrown by all layers
```

Rules (enforced by review; package structure makes violations obvious):
1. `controller` depends only on `service` + `dto` — never on `mcp`, `analysis`, entities.
2. `analysis` depends on **nothing** internal (pure math) — trivially unit-testable.
3. `agent` and `mcp` depend on **ports** (`MarketDataProvider`, `NewsDataProvider`), never adapters.
4. `market`/`news` know nothing about `agent`/`mcp` — no upward imports.
5. `mcp.tools` may call `analysis` engines and ports; nothing else calls tools directly except the registry.
6. DTOs never contain entities; entities never leave `service`/`repository`/`model`.
7. Constructor injection everywhere; no field injection; no `@Value` scattering (use `FinAgentProperties`).
8. Adapters are selected by `config` beans — swapping Alpha Vantage → Stooq touches zero business code.

## 12. Recommended Development Phases

Aligned 1:1 with `PHASES.md` (no phase starts before the previous compiles & passes tests):

| # | Phase | Key deliverables (backend) |
|---|---|---|
| 1 | Skeleton & foundations | Maven POM (all deps now), boot app, package tree, `FinAgentProperties`, global exception handler, health endpoint, `mvn verify` green |
| 2 | Domain & persistence | JPA entities, Flyway V1 migration, repositories, persistence tests (needs Docker/PostgreSQL or deferred JPA in dev) |
| 3 | Market data layer | `MarketDataProvider` port + stub + real adapter, market DTOs, retry/timeout, mocked-HTTP tests |
| 4 | Deterministic analysis & risk engine | Calculators + `StockRiskEngine`, golden-number unit tests |
| 5 | News & sentiment | `NewsDataProvider` port + adapter, lexicon sentiment, optional LLM labeling, tests |
| 6 | Portfolio & diversification | Weights, sector concentration, diversification score, tests |
| 7 | MCP tool layer | Registry + 6 typed tools, failure handling, audit rows, tests |
| 8 | AI research agent | ChatClient, planner/selector/synthesizer, FactBundle, `SafetyGuard`, guardrail tests |
| 9 | REST API v1 & OpenAPI | All controllers/DTOs, validation, springdoc; remove temp health endpoint |
| 10 | PDF report | PDFBox generator, mandated sections + disclaimer, metadata, parse-back tests |
| 11 | Orchestration & history | Async run lifecycle, status polling, history endpoints |
| 12 | React dashboard | (frontend phase — backend contract frozen by Phase 9 DTOs) |
| 13 | Integration tests & Docker | Testcontainers PostgreSQL, Dockerfiles, compose, runbook |

**Practical sequencing notes:**
- Phases 1–3 require no database → unblocked without Docker on this machine.
- Phase 7 (MCP) is buildable/testable with stub providers before any real API keys exist.
- Phase 8 needs an OpenAI key; everything except live synthesis can be tested with a mocked `ChatClient`.
- Phase 12 can start in parallel with 10–11 since the API contract freezes at Phase 9.

---
**Status:** Phases 1–14 implemented and verified (`mvn clean verify` green).
Phases 7–10 added the AI research agent (rule-based planner, FactBundle,
ChatClient synthesis, SafetyGuard), research orchestration & history, news
sentiment analysis, and PDF reports; Phase 11 the React dashboard; Phase 12
Docker Compose; Phase 13 the Testcontainers PostgreSQL suite; Phase 14 GitHub
Actions CI. See `PHASES.md` for the live roadmap, `risk-methodology.md`
for the formulas, and `mcp-tools.md` for the MCP details.





