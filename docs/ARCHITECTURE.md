# FINAGENT — System Architecture

**FINAGENT** is an AI-powered personal finance & investment *research* platform. A user submits a
research request (optionally with a portfolio of tickers); the system autonomously plans the research,
collects market data and news through MCP-style tools, computes risk/diversification metrics
**deterministically**, blends in news sentiment, and produces a structured PDF research report.

> **Scope & Safety**: FINAGENT is a financial research and educational system, NOT a financial advisor.
> It never claims guaranteed returns and never gives personalized investment advice. The LLM is never
> allowed to fabricate numerical market data (see §3.2 AI Safety).

---

## 1. High-Level View

```
                       ┌──────────────────────────────────────────────────┐
                       │                    React SPA                     │
                        │  Vite · Tailwind · React Router (native fetch, no charts lib) │
                       └───────────────▲──────────────────────────────────┘
                                       │ REST (JSON)  /api/v1/...
┌──────────────────────────────────────┴──────────────────────────────────────────────────┐
│                              Spring Boot Backend (Java 21)                              │
│                                                                                         │
│  ┌──────────┐   ┌────────────────┐   ┌──────────────────────────────────────────────┐   │
│  │Controller │──▶│    Service     │──▶│               Agent Layer                    │   │
│  │ (API v1) │   │ (orchestration)│   │  ResearchPlanner → ToolExecutor → Synthesizer│   │
│  └──────────┘   └───────┬────────┘   └───────┬───────────────────────┬──────────────┘   │
│                         │                    │                       │                  │
│                 ┌───────▼────────┐   ┌───────▼────────┐   ┌──────────▼───────────┐      │
│                 │ Analysis Layer │   │    MCP Layer   │   │ Spring AI ChatClient │      │
│                 │ Risk · Trend · │   │ ToolRegistry   │   │ (LLM reasoning only) │      │
│                 │ Diversification│   │ (discover/     │   └──────────────────────┘      │
│                 │  Sentiment     │   │  execute tools)│                                 │
│                 └───────┬────────┘   └───────┬────────┘                                 │
│                         │                    │                                          │
│                 ┌───────▼────────────────────▼──────────┐                               │
│                 │        Provider Interfaces (ports)     │                               │
│                 │  MarketDataProvider │ NewsDataProvider │                               │
│                 └───────┬─────────────────────┬─────────┘                               │
│                         │ (adapters)          │ (adapters)                              │
│                 ┌───────▼───────┐     ┌───────▼────────┐                                │
│                 │ MarketAdapter │     │   NewsAdapter  │   ← swappable implementations  │
│                 └───────────────┘     └────────────────┘                                │
│                                                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐    │
│  │ Report Layer │  │  Repository  │  │  DTO Layer   │  │ Exception / Config / Valid.│    │
│  │ (PDFBox PDF) │  │ (JPA/Postgres)│ │ (API border) │  │ (GlobalExceptionHandler)  │    │
│  └──────────────┘  └──────────────┘  └──────────────┘  └───────────────────────────┘    │
│                                                                                         │
│                    ┌──────▼────────┐                                                     │
│                    │   PostgreSQL  │  (Docker Compose)                                   │
│                    └───────────────┘                                                     │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```
---

## 2. Module (Package) Architecture

Package root: `com.finagent`

| Package          | Responsibility                                                                 |
|------------------|--------------------------------------------------------------------------------|
| `controller`     | Thin REST endpoints (`/api/v1/**`). **No business logic.** Delegates to services. |
| `dto`            | Request/response records for the API boundary. Never leak JPA entities.         |
| `service`        | Use-case orchestration: research workflow, portfolio CRUD, report lifecycle.    |
| `agent`          | The AI research agent: planning, tool selection, synthesis, safety guardrails.  |
| `mcp`            | MCP-style tool registry: typed tool descriptors, discovery, execution, failure handling. |
| `market`         | `MarketDataProvider` port + adapters (price, history, fundamentals) + market DTOs. |
| `news`           | `NewsDataProvider` port + adapters + sentiment pipeline.                        |
| `analysis`       | **Deterministic** analytics: returns, volatility, drawdown, beta, Sharpe, trends, risk score. |
| `portfolio`      | Portfolio domain logic: holdings, weights, sector concentration, diversification score. |
| `report`         | PDF generation with Apache PDFBox; report metadata.                             |
| `repository`     | Spring Data JPA repositories (PostgreSQL).                                      |
| `model`          | JPA entities + domain value objects.                                            |
| `config`         | `@Configuration` beans, `@ConfigurationProperties`, CORS, OpenAPI.              |
| `exception`      | Domain exceptions + `@RestControllerAdvice` global handler → consistent errors. |

**Dependency rule**: `controller → service → {agent, analysis, portfolio, report, market, news} → mcp → (ports)`.
Nothing in `market`/`news` knows about `agent`; everything depends on **interfaces** (ports), never on
concrete adapters. Controllers depend only on services and DTOs. Constructor injection everywhere —
no field injection.

---

## 3. Key Design Decisions (with tradeoffs)

### 3.1 MCP tool layer — Streamable-HTTP server over in-process services (as built)
**Decision**: the finance tools are exposed as a real MCP server (Spring AI,
Streamable HTTP at `/mcp`) whose tool implementations delegate to the same
application services the REST controllers and the AI agent use
(`Tool → Service → Provider interface → Adapter`). The agent itself executes
those services in-process (no self-loop network call); external MCP clients
(MCP Inspector, Claude Desktop) connect over HTTP.

- *Tradeoff vs. a pure in-process registry*: a real server gives protocol-level
  interop (any MCP client can call the tools) at the cost of an unauthenticated
  HTTP surface — see `mcp-tools.md` (keep `/mcp` off public networks or set
  `FINAGENT_MCP_ENABLED=false`).
- Spring AI `@Tool` / `ToolCallbackProvider` registration advertises the tools;
  the rule-based planner stays constrained to the known tool names.
- Adding a tool later = one `@Tool` method + registration; zero changes to
  services or agent.

### 3.2 LLM safety — "facts in, prose out"
The LLM **never** sources numbers. Data flows only through this pipeline:

1. Tools return raw provider data → validated (null/NaN/stale checks) → assembled into a
   **FactBundle** (prices, history, fundamentals, news).
2. The `analysis` package computes **all** metrics deterministically (returns, volatility,
   drawdown, beta, Sharpe, diversification, risk score).
3. The LLM receives the FactBundle + computed metrics as structured input and produces
   **interpretation text only**.
4. The PDF report renders numbers **exclusively** from the FactBundle/engine results — never by
   parsing numbers out of LLM prose. A numeric-consistency guard flags LLM output asserting
   figures absent from the bundle.

### 3.3 External APIs behind ports
`MarketDataProvider` and `NewsDataProvider` interfaces with adapter implementations
(e.g., Alpha Vantage / Stooq for market data, GNews/NewsAPI for news, plus a deterministic
`StubMarketDataProvider` for offline dev and tests). Adapters are selected via configuration;
API keys come **only** from environment variables.

### 3.4 Deterministic risk engine with transparent scoring
Every metric is a pure, unit-tested function of validated data. The composite risk score uses a
**documented, weighted formula** (volatility, max drawdown, beta, sector concentration, data
quality) published in the report itself. The LLM may *explain* the score but never compute it.
Insufficient data ⇒ metric reported as "insufficient data", never guessed.

### 3.5 Persistence & migrations
PostgreSQL via Spring Data JPA. Schema version-controlled with **Flyway** migrations (chosen over
`ddl-auto=update` for reproducible, reviewable schema evolution — interview-defensible).

### 3.6 Async research workflow
Research runs are long-running (tool calls + LLM + PDF). `POST /api/v1/research` returns `202` with a
`researchId`; status is polled via `GET /api/v1/research/{id}` (simple, robust; SSE/WebSocket noted
as a future enhancement).

---

## 4. Data Model (initial)

| Table | Purpose |
|---|---|
| `research_requests` | request text, tickers, portfolio ref, status (`PENDING/RUNNING/COMPLETED/FAILED`), timestamps |
| `research_results` | executive summary, AI interpretation, computed metrics snapshot, sentiment summary |
| `portfolios` / `portfolio_holdings` | portfolio name + holdings (ticker, quantity, cost basis, sector) — persistence only, no REST endpoints |
| `research_tool_executions` | tool name, input hash, status, duration, error, executed-at (MCP observability; schema ready, writes not yet wired) |
| `report_metadata` | file path, generated-at, checksum, page count |

---

## 5. API Surface (v1)

| Method | Path | Purpose |
|---|---|---|
| `GET`  | `/api/v1/health` | service status |
| `GET`  | `/api/v1/stocks/{symbol}` | quote + fundamentals (nulls allowed for missing fields) |
| `GET`  | `/api/v1/stocks/{symbol}/news` | recent articles (`from`/`to`/`limit` optional) |
| `POST` | `/api/v1/research` | submit research request (202 + id) |
| `GET`  | `/api/v1/research` | list history (paged) |
| `GET`  | `/api/v1/research/{id}` | status + result |
| `GET`  | `/api/v1/research/{id}/report.pdf` | PDF download (409 unless COMPLETED) |

Portfolios persist via JPA but expose no portfolio REST endpoints; the MCP
server lives at `/mcp` (Streamable HTTP, not under `/api/v1`).

OpenAPI 3 docs via `springdoc-openapi` at `/swagger-ui.html`. All input validated with
Jakarta Validation; errors returned as a consistent `ApiError` JSON.

---

## 6. Security & Configuration

- Secrets exclusively via environment variables (`ALPHA_VANTAGE_API_KEY`, `NEWS_API_KEY`,
  `SPRING_AI_OPENAI_API_KEY`, `SPRING_DATASOURCE_*`). `.env` is git-ignored; `.env.example` committed.
- No secrets in logs; tool-execution logging stores inputs/outputs redacted of keys.
- The financial research disclaimer is a mandatory, non-removable section of every PDF report.

---

## 7. Testing Strategy

| Layer | Approach |
|---|---|
| Analysis/risk engine | Pure JUnit 5 tests with known datasets (golden numbers). |
| Services | Mockito; providers and LLM mocked. |
| Agent | Mock ChatClient + stub providers; assert plan/tool-selection/fact-bundle/guard flow. |
| MCP tools | Unit tests per tool incl. provider failure → typed provider exceptions. |
| Report | Assert PDF bytes parse (PDFBox load) + expected section markers. |
| Integration | `@SpringBootTest` + Testcontainers PostgreSQL: request → run → status → report workflow. |

