# FINAGENT

AI-powered personal finance & investment **research** platform. FINAGENT is a research/educational
tool, **NOT** a financial advisor — it never gives personalized investment advice.

- Architecture: see [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
- Roadmap: see [`docs/PHASES.md`](docs/PHASES.md)
- Backend design: see [`docs/BACKEND_DESIGN.md`](docs/BACKEND_DESIGN.md)
- React dashboard: see [`docs/frontend.md`](docs/frontend.md)
- Dockerized local stack: see [`docs/docker.md`](docs/docker.md)
- CI quality gate: see [`docs/ci-cd.md`](docs/ci-cd.md)
- Production-readiness audit: see [`docs/production-readiness-audit.md`](docs/production-readiness-audit.md)
- Security (JWT auth, roles, rate limits, prod): see [`docs/security.md`](docs/security.md)
- Final hardening (AI timeout, PDF Unicode, audit trail, containers): see
  [`docs/final-hardening.md`](docs/final-hardening.md)

## Continuous integration (Phase 14)

Every push to `main` (and every pull request targeting it) runs the `CI`
workflow (`.github/workflows/ci.yml`, `ubuntu-latest`):

| Job | What it verifies |
|---|---|
| `backend` | `mvn clean verify -Ppostgres-integration` on Java 21: compilation, full unit suite (H2, 333 tests), plus the 18 real PostgreSQL/Testcontainers integration tests (`PostgresResearchIT` 12 + `PostgresAuthIT` 6; Flyway V1–V4 + Hibernate `validate`) |
| `frontend` | `npm ci` → `npm test` (Vitest) → `npm run build` (`tsc` + Vite) on Node 20 |
| `docker` (after both) | `docker compose config` + `docker build` of the backend and frontend images (validation only — no push, no deploy) |

No secrets required: stub providers + a dummy AI key. Local equivalents of
every CI step are documented in [`docs/ci-cd.md`](docs/ci-cd.md#8-local-equivalent-commands).
There is no deployment yet — CI builds and validates only.

## Quick start (Docker — full stack, no Maven/npm needed)

Prerequisites: Docker Desktop.

```bash
cp .env.example .env   # then edit: set SPRING_DATASOURCE_PASSWORD + optional API keys
docker compose up --build
```

Then: dashboard at `http://localhost` → submit research → track status → download PDF;
API + Swagger at `http://localhost:8080`; PostgreSQL at `localhost:5432`
(`finagent`/`finagent`, password from `.env`). Data persists in the `pgdata`
volume; `docker compose down -v` destroys it.

## Tech stack (backend)

Java 21 · Spring Boot 3.5.x · Spring Web · Validation · Spring Data JPA · PostgreSQL + Flyway ·
Lombok · Actuator · springdoc-openapi · Spring AI MCP (Streamable HTTP) · Maven

## Tech stack (frontend)

React 18 · Vite 7 · TypeScript (strict) · Tailwind CSS v4 · React Router 6 · Vitest + Testing
Library (jsdom). No state-management or data-fetching libraries — presentation only, the backend
remains the source of truth for every number.

## Quick start (dashboard + backend)

Prerequisites: Java 21, Maven 3.9+, Node 20+. Research endpoints need a database, so run the
backend with the `postgres` profile (see [Database](#database-phase-2) below for the one-line
Docker setup); the React dev server proxies `/api` to it, so no CORS setup is needed locally.

```bash
# terminal 1 — backend (example with local PostgreSQL)
SPRING_PROFILES_ACTIVE=postgres SPRING_DATASOURCE_PASSWORD=change-me mvn spring-boot:run
# terminal 2 — dashboard
cd frontend
npm install
npm run dev        # http://localhost:5173
```

Production builds (`npm run build` → `frontend/dist/`) served from another origin rely on backend
CORS: `FINAGENT_CORS_ALLOWED_ORIGINS` (default `http://localhost:5173`, GET/POST on `/api/**`).
Only public config lives in `frontend/.env.example` (`VITE_API_BASE_URL`) — never any API keys.

## Quick start (backend only, no database)

Prerequisites: Java 21, Maven 3.9+. The default `dev` profile runs **without a database**.

```bash
cd backend
mvn spring-boot:run
```

Then:

- Health: `GET http://localhost:8080/api/v1/health` →
  `{"application":"FinAgent","version":"1.0.0","status":"RUNNING"}`
- Actuator health: `http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

## Database (Phase 2)

Schema is owned by **Flyway** (`backend/src/main/resources/db/migration/V1__init.sql`).
Hibernate runs with `ddl-auto=validate` against PostgreSQL — migrations are the single source of truth.

Run with PostgreSQL:

```bash
# start a local PostgreSQL 16 (Docker example)
docker run -d --name finagent-db -e POSTGRES_DB=finagent \
  -e POSTGRES_USER=finagent -e POSTGRES_PASSWORD=change-me -p 5432:5432 postgres:16

# activate the postgres profile
SPRING_PROFILES_ACTIVE=postgres SPRING_DATASOURCE_PASSWORD=change-me mvn spring-boot:run
```

Tables: `research_requests`, `research_request_tickers`, `research_results`, `portfolios`,
`portfolio_holdings`, `research_tool_executions`, `report_metadata`.

Tests use in-memory H2 (`test` profile, Flyway disabled); Phase 13 replaces them with
Testcontainers against real PostgreSQL.

## Tests

```bash
cd backend
mvn clean verify
# with real PostgreSQL/Testcontainers integration tests (needs Docker):
mvn clean verify -Ppostgres-integration
```

PostgreSQL integration tests (`PostgresResearchIT`, failsafe `*IT` naming)
boot ephemeral `postgres:16-alpine` via Testcontainers — no manual database
setup. See [`docs/postgres-integration.md`](docs/postgres-integration.md) and
[`docs/ci-cd.md`](docs/ci-cd.md).

```bash
cd frontend
npm install
npm test        # Vitest, mocked fetch — no backend needed
npm run build   # type-check + production bundle in frontend/dist/
```

## Financial news (Phase 4)

Retrieves recent financial news for a stock/company through a swappable `NewsDataProvider` port
(identical hexagonal pattern to market data).

- `GET /api/v1/stocks/{symbol}/news?from=YYYY-MM-DD&to=YYYY-MM-DD&limit=N` →
  `{ "symbol", "count", "articles": [ { "query", "title", "description", "url", "source",
  "imageUrl", "publishedAt" } ] }`
- Defaults: last 7 days, `limit` 20 (max 50). `from`, `to` and `limit` are optional query parameters.
- Providers: `stub` (default — offline, deterministic) | `newsapi`, selected with
  `FINAGENT_NEWS_PROVIDER`. The key is read from `NEWS_API_KEY` (environment only, never hardcoded).
- Rate limits: the NewsAPI free/developer tier allows ~100 requests/day. HTTP 429/426 responses and
  the `status=error/code=rateLimited` payload both map to `502 Bad Gateway` (rate-limit message);
  timeouts and retry-with-backoff are shared infrastructure (`FINAGENT_NEWS_TIMEOUT`,
  `FINAGENT_NEWS_RETRY_ATTEMPTS`, `FINAGENT_NEWS_RETRY_BACKOFF`).
- This subsystem is **pure news retrieval** — scored sentiment lives in Phase 9
  (`SentimentAnalysisService` + `analyze_news_sentiment` MCP tool), not here.

## MCP finance tools (Phase 5)

FinAgent runs an **MCP server** (Model Context Protocol, Streamable HTTP transport) that exposes the
existing finance services as typed tools at `http://localhost:8080/mcp`. The MCP layer is a pure
integration/interface layer — every tool delegates to the application services (never to external
APIs directly).

Tools: `get_stock_price` · `get_stock_history` · `get_stock_fundamentals` · `search_financial_news`
· `analyze_stock_risk` · `analyze_news_sentiment`.

```bash
# list the advertised tools
curl -s -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"1.0"}}}'
# then repeat the above with the Mcp-Session-Id header and body:
#   {"jsonrpc":"2.0","id":2,"method":"tools/list"}
#   {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_stock_price","arguments":{"symbol":"AAPL"}}}
```

Full details (inputs/outputs, registration, verification, security): see
[`docs/mcp-tools.md`](docs/mcp-tools.md).

## Configuration

All configuration is environment-variable driven (see [`.env.example`](.env.example)).
Copy `.env.example` to `.env` for local overrides — `.env` is git-ignored.

| Variable | Default | Purpose |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `SPRING_PROFILES_ACTIVE` | `dev` | Spring profile (`dev` = no DB, `postgres` = PostgreSQL) |
| `FINAGENT_APP_NAME` | `FinAgent` | Health endpoint application name |
| `FINAGENT_APP_VERSION` | `1.0.0` | Health endpoint version |
| `SPRING_DATASOURCE_*` | — | PostgreSQL connection (used with the `postgres` profile) |
| `FINAGENT_MARKET_PROVIDER` | `stub` | Market provider (`stub` | `alpha-vantage`) |
| `ALPHA_VANTAGE_API_KEY` | — | Alpha Vantage API key (env only; required for `alpha-vantage`) |
| `FINAGENT_NEWS_PROVIDER` | `stub` | News provider (`stub` | `newsapi`) |
| `NEWS_API_KEY` | — | NewsAPI.org key (env only; required for `newsapi`) |
| `FINAGENT_NEWS_TIMEOUT` | `5s` | News provider connect/read timeout |
| `FINAGENT_MCP_ENABLED` | `true` | MCP server (`/mcp`, Streamable HTTP) on/off — keep off when publicly exposed |
| `FINAGENT_RISK_RISK_FREE_RATE` | unset | Annual risk-free rate (e.g. 0.0425); unset → Sharpe ratio reported unavailable |
| `FINAGENT_RISK_TRADING_DAYS_PER_YEAR` | 252 | Annualization factor for daily data |
| `FINAGENT_RISK_DEFAULT_BENCHMARK` | SPY | Default benchmark for beta (real adapter connects later) |
| `SPRING_AI_OPENAI_API_KEY` | dummy placeholder | OpenAI API key (env only; required for real synthesis) |
| `FINAGENT_AI_MODEL` | `gpt-4o-mini` | Chat model for the research agent |
| `FINAGENT_AI_TEMPERATURE` | `0.2` | Synthesis temperature (low = factual, stable) |
| `FINAGENT_AI_MAX_TOKENS` | `800` | Max completion tokens per interpretation |
| `FINAGENT_SENTIMENT_POSITIVE_THRESHOLD` | `0.2` | Sentiment score at/above this → POSITIVE |
| `FINAGENT_SENTIMENT_NEGATIVE_THRESHOLD` | `-0.2` | Sentiment score at/below this → NEGATIVE |
| `FINAGENT_SENTIMENT_CONFIDENCE_HITS` | `5` | Lexicon hits yielding full sentiment confidence |

## Status

**Phase 1 complete** — project skeleton, configuration conventions, global exception handling,
health smoke endpoint, `mvn clean verify` green.

**Phase 2 complete** — JPA entities (`ResearchRequest`, `ResearchResult`, `Portfolio`,
`PortfolioHolding`, `ResearchToolExecution`, `ReportMetadata`), repositories, Flyway `V1__init.sql`
PostgreSQL schema (indexes, constraints, checks), `postgres` profile, H2-backed repository
integration tests + entity validation tests, `mvn clean verify` green (18 tests).

**Phase 3 complete** — `MarketDataProvider` port (quote/history/fundamentals), deterministic stub
provider + Alpha Vantage adapter (API key via `ALPHA_VANTAGE_API_KEY` env only), timeouts + retry
with backoff, typed provider errors (404 unknown symbol / 502 provider failure / 504 timeout),
`StockDataService`, and the `GET /api/v1/stocks/{symbol}` test endpoint.

**Phase 4 complete** — `NewsDataProvider` port (`searchNews`), deterministic news stub + NewsAPI.org
adapter (key via `NEWS_API_KEY` env only), rate-limit / timeout / malformed-response / empty-result
handling, `NewsDataService`, and `GET /api/v1/stocks/{symbol}/news` returning clean
`NewsArticle`/`NewsResponse` DTOs. No sentiment analysis in this phase.

**Phase 5 complete** — MCP server (Spring AI 1.1.8, Streamable HTTP at `/mcp`) exposing the
existing finance services as four typed tools (`get_stock_price`, `get_stock_history`,
`get_stock_fundamentals`, `search_financial_news`). Tools delegate strictly to the application
services; a full JSON-RPC integration test verifies advertising + invocation. `analyze_stock_risk`
is documented but intentionally deferred until the risk engine exists.

**Phase 6 complete** — Deterministic financial risk engine: return calculation, annualized volatility,
maximum drawdown, beta vs a benchmark, Sharpe ratio (when a risk-free rate is configured), portfolio
diversification (HHI + sector concentration), and a transparent composite risk score (LOW/MODERATE/HIGH).
All formulas are published in `docs/risk-methodology.md`; unavailable metrics are reported as such,
never fabricated. Exposed via the `analyze_stock_risk` MCP tool, which delegates to `RiskAnalysisService`.

**Phase 7 complete** — AI research agent (`ResearchAgent`: deterministic plan → execute via application
services with per-source degradation into data gaps → validated `FactBundle` → `ChatClient` synthesis
(prose only) → `SafetyGuard` numeric-consistency redaction). Rule-based planner constrained to the five
MCP tool names; `AgentException`/`FactBundleIncompleteException` mapped by the global handler.
Full details: see [`docs/research-agent.md`](docs/research-agent.md).

**Phase 8 complete** — Research orchestration & history: `POST /api/v1/research` → `202` + `researchId`
(persisted `PENDING`, after-commit dispatch to a bounded async pool — no broker), `GET
/api/v1/research/{id}` (poll `PENDING` → `RUNNING` → `COMPLETED` + structured result / `FAILED` +
safe `{code, message}`), `GET /api/v1/research` (newest-first paged history). Results persist in the
existing `research_results` table (guarded prose + deterministic metrics-snapshot JSON + news summary +
disclaimer); lifecycle columns (`started_at`, `completed_at`, `error_code`, `error_message`) arrive via
`V2__research_lifecycle.sql`. The `dev` profile (no database) excludes these endpoints — use the
`postgres` profile for them. Full details: see [`docs/research-orchestration.md`](docs/research-orchestration.md).

**Phase 9 complete** — Financial news sentiment analysis: pluggable `SentimentAnalyzer` port with a
deterministic lexicon baseline (`LexiconSentimentAnalyzer`, offline, configurable lexicon/thresholds —
per-article `(pos−neg)/(pos+neg)` in `[-1.0, +1.0]`, simple-mean aggregation, labels at ±0.20,
evidence-scaled confidence). Structured `SentimentResult` (label/score/confidence/counts/methodology;
`UNAVAILABLE` with `NO_ARTICLES`/`PROVIDER_ERROR`/`INSUFFICIENT_CONTENT` reasons — never confused with
`NEUTRAL`). The agent includes sentiment as grounded `FactEntry` facts only when the request calls for
it (price questions skip it; provider failures ground as `UNAVAILABLE`); the `analyze_news_sentiment`
MCP tool fetches through `NewsDataService` and scores; label/score/counts/confidence/methodology
persist in the existing `metrics_snapshot` JSON (no new tables). Full details: see
[`docs/sentiment-analysis.md`](docs/sentiment-analysis.md).

**Phase 10 complete** — Professional PDF research reports: `GET /api/v1/research/{id}/report.pdf`
→ `200 application/pdf` for `COMPLETED` jobs (`Content-Disposition: attachment;
filename="finagent-research-{id}.pdf"`), `404` unknown id, `409` for `PENDING`/`RUNNING`/`FAILED`.
`ResearchReportService` maps the persisted result to a snapshot and renders it with Apache PDFBox
3.0.3 (`PdfReportGenerator`: cover, executive summary, per-ticker market/fundamentals/risk tables,
news & sentiment, AI interpretation, gaps & limitations, mandatory disclaimer). Presentation only —
no research, AI, MCP, or network calls; missing values print "Unavailable", never fabricated; long
text wraps, tables break across pages with repeated headers, every page has header/footer/page
number. Full details: see [`docs/pdf-reports.md`](docs/pdf-reports.md).

**Phase 11 complete** — React research dashboard (`frontend/`: Vite 7 + React 18 + TypeScript strict +
Tailwind v4 + React Router 6, no state/data libraries). Routes `/` (dashboard + recent), `/research/new`
(validated form → 202 → details), `/research/:id` (2.5s polling while pending/running, full result:
summary, stock cards, market/risk/sentiment tables, headlines, interpretation, gaps, disclaimer, PDF
download), `/history` (paged, newest first). Centralized typed API client, friendly errors (never
stack traces), responsive + accessible, missing data shows "Unavailable". Backend change: minimal
`WebConfig` CORS (`FINAGENT_CORS_ALLOWED_ORIGINS`, default `http://localhost:5173`) + 3 contract
tests. `npm run build` green, 30 Vitest tests green at the time (42 as of Phase 17). Full details: see [`docs/frontend.md`](docs/frontend.md).

**Phase 12 complete** — Dockerized local stack (`docker-compose.yml`): PostgreSQL 16 → Spring Boot
backend (Flyway migrations, `validate`) → nginx-hosted React (same-origin `/api` proxy, SPA
fallback). Pinned images, health-gated startup, `pgdata` volume, secrets via `.env` only.
Full details: see [`docs/docker.md`](docs/docker.md).

**Phase 13 complete** — Real PostgreSQL integration tests via Testcontainers (`postgres:16-alpine`,
ephemeral per run, `@ServiceConnection` datasource injection, Flyway V1+V2 applied + verified,
Hibernate `validate`). `PostgresResearchIT` (12 tests: context/connection, migration history,
research PENDING→RUNNING→COMPLETED/FAILED persistence incl. long unicode snapshots, history
ordering, enum/unique/numeric/timestamp checks) runs under `mvn verify -Ppostgres-integration`
(requires Docker); the default `mvn clean verify` needs no Docker and stays green (264 tests at the time; 333 as of Phase 17).
Full details: see [`docs/postgres-integration.md`](docs/postgres-integration.md).

**Phase 14 complete** — GitHub Actions CI quality gate (`.github/workflows/ci.yml`):
`backend` (`mvn clean verify -Ppostgres-integration`, Java 21, real Testcontainers
PostgreSQL ITs) + `frontend` (`npm ci` → `npm test` → `npm run build`, Node 20) in
parallel, then `docker` (`docker compose config` + both image builds, no push).
Triggers on push/PR to `main`. No secrets, no deployment — validation only.
Full details: see [`docs/ci-cd.md`](docs/ci-cd.md).

**Phase 15 complete** — Production-readiness + security + architecture audit (no
features, no redesign). No critical issues; 3 high issues fixed (news retry
wiring, AlphaVantage HTTP-error mapping, malformed-payload guards) plus
synthesis-error sanitization, unused MCP-client dependency removal, PDF header
repeats, and `react-router-dom` 6.30.6 + `vitest` 3.2.7 (`npm audit` clean of
applicable advisories). Known by-design limits: no auth (stay private),
dev-default credentials. Full details: see
[`docs/production-readiness-audit.md`](docs/production-readiness-audit.md).

**Phase 16 complete** — Security hardening + authentication + API protection:
JWT access tokens (register/login/me, BCrypt-10, USER/ADMIN roles, `users` table
via Flyway V3), authenticated stocks/research/MCP APIs, ADMIN-only operations,
Swagger gated off in prod, rate limiting (429), `prod` profile with fail-fast
secret validation, Bearer-token React login/registration with protected routes.
Dev profile stays DB-less and open (local only). Full details: see
[`docs/security.md`](docs/security.md).

**Phase 17 complete** — Final production hardening: bounded AI synthesis timeout
(fail-fast, no hung jobs), full-Unicode PDFs (bundled DejaVu + Unifont fallback,
no runtime downloads), immutable security audit trail (Flyway V4, no secrets),
non-root frontend container, container-aware backend heap, weekly Dependabot,
verified security headers. Full details: see
[`docs/final-hardening.md`](docs/final-hardening.md).

## Final status (pre-GitHub packaging, verified 2026-09-05)

FinAgent is a **Personal Finance & Investment Research Agent** — a research/educational
tool, **NOT** a financial advisor. It does not do live trading, brokerage integration,
investment execution, or guaranteed predictions.

Major capabilities:

- market data retrieval (stub + Alpha Vantage adapter)
- financial news retrieval (stub + NewsAPI.org adapter)
- deterministic risk analysis (volatility, drawdown, beta, Sharpe, diversification, risk score)
- lexicon-based sentiment analysis
- MCP tool integration (Streamable HTTP at `/mcp`)
- AI-assisted research (Spring AI synthesis over grounded facts, safety-guarded)
- research persistence + async orchestration with status polling and history
- PDF report generation (PDFBox, full-Unicode)
- React dashboard (research submit, polling details, history, PDF download)
- JWT authentication (register/login, USER/ADMIN roles, rate limits)
- PostgreSQL + Flyway migrations (V1–V4, Hibernate `validate`)
- Docker Compose local stack (config present)
- GitHub Actions CI workflow (config present)

Test totals (verified 2026-09-05, incl. post-project hardening + verification): **437 total =
377 backend unit (surefire, H2) + 42 frontend (Vitest) + 18 PostgreSQL/Testcontainers
integration (`PostgresResearchIT` 12 + `PostgresAuthIT` 6)**.

### Locally verified

| Item | Result |
|---|---|
| Backend `mvn clean verify` (default profile, H2) | PASS — 377 tests, 0 failures |
| Frontend `npm test` (Vitest) | PASS — 42 tests, 8 files |
| Frontend `npm run build` (`tsc` + Vite) | PASS |
| PostgreSQL/Testcontainers IT sources | present and compile; container execution is Docker-gated (see below) |

### Configured but NOT yet executed

| Item | State |
|---|---|
| PostgreSQL/Testcontainers execution | implemented and source/test verified; local execution needs Docker, which is unavailable on this machine |
| Docker Compose stack | configured (`docker-compose.yml`, backend + frontend Dockerfiles); `docker compose` / image builds NOT executed locally |
| GitHub Actions CI | workflow configured (`.github/workflows/ci.yml`); remote execution NOT yet performed |
| Production deployment | NOT performed — no deployment target, no release |

### Production hardening (post-project task 1, verified 2026-09-05)

- Production profile (`SPRING_PROFILES_ACTIVE=prod`, `application-prod.yml`): fail-fast —
  refuses to start without a real JWT secret (≥ 32 bytes, non-default), a real OpenAI key,
  a non-default DB password, provider keys for non-stub providers, and now an explicit
  non-localhost, non-wildcard `FINAGENT_CORS_ALLOWED_ORIGINS` list. Swagger is off in prod
  (three layers: bean, properties, security-chain deny). Dev/test keep safe dummy defaults.
- Security headers: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: strict-origin-when-cross-origin` on API responses; HSTS
  (`max-age=31536000, includeSubDomains`) on HTTPS responses. No CSP on the API (JSON only);
  static-layer CSP/HSTS belong at the TLS terminator (the Compose nginx serves plain HTTP).
- CORS: env-driven origin list on `/api/**`, GET/POST only, no wildcard;
  credentials enabled (Task 3) so the browser sends/accepts the refresh cookie.
- Auth: HS256 JWT (1h TTL via `FINAGENT_AUTH_TOKEN_TTL`), minimal claims, role taken
  from the live user record; generic 401/403/429 JSON, no stack traces or secret material.
- Refresh tokens (Tasks 2–3): login/register set an HttpOnly `finagent_rt` cookie
  (`Secure` in prod, `SameSite=Lax`, `Path=/api/v1/auth`, `Max-Age` = TTL) and return
  the access JWT with `refreshToken: null` in JSON — the raw refresh token never
  appears in any body, log, or JS storage. `POST /api/v1/auth/refresh` rotates from
  the cookie (single-use, 14d default via `FINAGENT_AUTH_REFRESH_TTL`); replaying a
  consumed token is rejected and revokes the whole session family (reuse detection);
  `POST /api/v1/auth/logout` revokes the family (idempotent) and clears the cookie.
  Only SHA-256 hashes are stored (Flyway V5 `refresh_tokens`, unchanged — no new
  migration); raw tokens never touch the DB or logs. Access JWTs expire and cannot
  be revoked. Not OAuth2. A JSON-body token fallback remains for non-browser API
  clients only; the SPA never uses it.
- Refresh-token housekeeping (Task 4): scheduled bulk deletion of dead rows
  past a 7d retention grace (`FINAGENT_AUTH_REFRESH_RETENTION`) — expired rows
  (already unusable, tripwire spent) and old revoked rows only; used-but-live
  rows are never touched so reuse detection is intact. Hourly by default
  (`FINAGENT_AUTH_REFRESH_CLEANUP_INTERVAL`), pausable without restart
  (`FINAGENT_AUTH_REFRESH_CLEANUP_ENABLED`, explicitly on in prod), failures
  contained and logged by count (never token values/hashes). ADMIN-only
  `GET /api/v1/admin/auth/sessions` exposes counts only
  (`active/revoked/expired/families/lastCleanupAt/lastCleanupDeleted`) —
  anonymous 401, USER 403. No schema migration (V1–V5 untouched), no Redis,
  no JWT changes. Full details: `docs/security.md` §16.
- Actuator: only `health`/`info` exposed; `env`/`beans`/`mappings`/`configprops`/dumps stay denied.
- Rate limiting: in-memory fixed-window per instance (auth 10/min, research 30/min → 429);
  multi-instance limiting needs Redis (future work, no new dependency added in this task).
- Token storage: frontend keeps the short-lived access JWT in localStorage
  (documented tradeoff in `frontend/src/api/client.ts` and `docs/security.md`
  — not XSS-proof, not claimed immune); the long-lived refresh token is
  HttpOnly-cookie-only (Task 3), invisible to JavaScript.

Known production limitations / future work (not implemented): Redis-backed rate limiting,
TLS termination + static-layer CSP, refresh-token cleanup job for expired rows,
production deployment, Docker runtime verification
(Docker unavailable on this machine), live trading/brokerage (explicitly out of scope —
FinAgent is research-only).

Phase 17 is the final engineering phase. There is no Phase 18.
#   f i n a g e n t  
 