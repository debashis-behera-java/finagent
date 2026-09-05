# FINAGENT — Phased Implementation Roadmap

Each phase: architecture notes → file list → dependencies → implement → build/test → fix → summary.
**No phase starts before the previous one compiles and passes tests.**

> **Environment note (verified 2026-01):** Java 21.0.10 ✓, Maven 3.9.14 ✓, Node v20.19.6 ✓.
> **Docker is NOT installed** on this machine. Phases that need PostgreSQL will require Docker Desktop
> (recommended) or a locally installed PostgreSQL 16. This does not block Phases 1–3.

## Repository layout (target)

```
FINAGENT/
├── backend/          # Spring Boot (Maven), Java 21
├── frontend/         # React + Vite + Tailwind
├── docker/           # docker-compose.yml, Dockerfiles
├── docs/             # ARCHITECTURE.md, PHASES.md
└── README.md
```

## Phase Overview

| # | Phase | Key deliverables |
|---|-------|------------------|
| 1 | **Project skeleton & foundations** | Maven multi-module layout, Spring Boot app booting, package structure, global exception handling, config properties, `.gitignore`/`.env.example`, smoke test |
| 2 | Domain model & persistence | JPA entities, Flyway migrations, repositories, persistence unit tests |
| 3 | Market data provider layer | `MarketDataProvider` port, adapter + stub, market DTOs, retry/timeout, unit tests (mocked HTTP) |
| 4 | Deterministic analysis & risk engine | returns, volatility, max drawdown, beta, Sharpe, trend, transparent risk score; golden-number unit tests |
| 5 | News provider & sentiment | `NewsDataProvider` port + adapter, lexicon-based deterministic sentiment + optional LLM labeling, tests |
| 6 | Portfolio & diversification | portfolio service, weights, sector concentration, diversification score, tests |
| 7 | MCP tool layer | `McpToolRegistry`, typed tools (`get_stock_price`, `get_stock_history`, `get_stock_fundamentals`, `search_financial_news`, `analyze_stock_risk`, `analyze_portfolio`), failure handling, execution audit records |
| 8 | AI research agent | Spring AI `ChatClient`, research planning, tool selection/calling, FactBundle validation, interpretation synthesis, safety guardrails |
| 9 | REST API v1 & OpenAPI | research/portfolio/stocks/reports controllers, DTOs, validation, springdoc UI |
| 10 | PDF report generation | PDFBox report with all mandated sections + disclaimer, metadata persistence, tests |
| 11 | Research orchestration & history | async run lifecycle, status polling, history endpoints |
| 12 | React dashboard | Vite + Tailwind + Router + Axios + Recharts: submit request, live status, results charts, report download |
| 13 | Integration tests & Docker | Testcontainers integration tests, Dockerfiles, docker-compose (app + postgres), README/runbook |

---

# Phase 1 — Project Skeleton & Foundations (implementation plan)

## Goal
A compiling, booting Spring Boot 3 application with the complete package skeleton, configuration
 conventions, and global exception handling — zero business logic yet. `mvn verify` green.

## Architecture notes
- Single Maven module `backend/` (multi-module added later only if justified — single module keeps
  the interview story simple and the build fast).
- Java 21, Spring Boot 3.3.x, Spring AI BOM imported now so later phases never churn the POM.
- Dependencies added **now** (to avoid repeated POM churn): `spring-boot-starter-web`,
  `spring-boot-starter-validation`, `spring-boot-starter-data-jpa`, `postgresql`,
  `spring-boot-starter-test`, `spring-ai-openai-spring-boot-starter` (BOM-managed), `pdfbox`,
  `springdoc-openapi-starter-webmvc-ui`, `flyway-core`, `lombok`.
- Configuration via `@ConfigurationProperties` (`FinAgentProperties`) — no `@Value` scattering, no
  hardcoded keys; everything overridable by env vars.

## Files to create
```
.gitignore
.env.example
README.md
backend/pom.xml
backend/src/main/java/com/finagent/FinAgentApplication.java
backend/src/main/resources/application.yml
backend/src/main/resources/application-dev.yml
backend/src/main/java/com/finagent/config/FinAgentProperties.java
backend/src/main/java/com/finagent/config/OpenApiConfig.java
backend/src/main/java/com/finagent/exception/ApiError.java
backend/src/main/java/com/finagent/exception/GlobalExceptionHandler.java
backend/src/main/java/com/finagent/exception/ResourceNotFoundException.java
backend/src/main/java/com/finagent/controller/HealthController.java     # temp smoke endpoint, removed in Phase 9
backend/src/test/java/com/finagent/FinAgentApplicationTests.java        # context boot + health test
backend/src/test/resources/application-test.yml
```

## Acceptance criteria
1. `mvn clean verify` succeeds (compile + tests) on Java 21.
2. App starts with `dev` profile without PostgreSQL (JPA auto-config deferred via
   `spring.autoconfigure.exclude` in test/dev until Phase 2 — documented tradeoff).
3. `GET /api/v1/health` returns `{"status":"UP","application":"FINAGENT"}`.
4. Validation error returns structured `ApiError` JSON (proves the global handler wiring).
5. No secrets in git; `.env.example` documents every env var.

## Explicitly out of scope for Phase 1
Entities, repositories, providers, agent, MCP, PDF, frontend, Docker.

---
---
**Status:** Phase 2 ✅ COMPLETE (JPA entities `ResearchRequest`, `ResearchResult`, `Portfolio`,
`PortfolioHolding`, `ResearchToolExecution`, `ReportMetadata`; repositories; Flyway `V1__init.sql`
PostgreSQL schema with indexes/constraints/checks; `postgres` profile with `ddl-auto=validate`;
H2-backed repository integration tests + entity validation tests).

**Status:** Phase 3 ✅ COMPLETE (`MarketDataProvider` port, stub + Alpha Vantage adapters, retry/timeout,
typed provider errors, `GET /api/v1/stocks/{symbol}`).

**Verified:** `mvn clean verify` green (2026-09-03) — 68 tests, 0 failures.

**Status:** Phase 4 ✅ COMPLETE — **Financial News Integration** (`NewsDataProvider` port
`searchNews`, NewsAPI.org adapter + deterministic stub, typed handling for rate limits (HTTP 429/426
and `code=rateLimited`), timeouts (shared `HttpRetryExecutor`), malformed responses, and empty
results; `NewsDataService` + `NewsArticle`/`NewsResponse` DTOs; `GET /api/v1/stocks/{symbol}/news`).
`mvn clean test` green — the news subsystem is covered by unit tests that never touch PostgreSQL or
the external API.

> **Roadmap note:** by implementation directive, Financial News Integration is delivered as
> **Phase 4** (before the deterministic analysis/risk engine). News **sentiment analysis is
> explicitly deferred** to a later phase — this phase contains none. The overview table above
> retains the original numbering.

**Status:** Phase 6 ✅ COMPLETE — **Deterministic Financial Risk Engine**. Return calculation,
annualized volatility, maximum drawdown, beta vs a benchmark (`BenchmarkDataProvider` port), Sharpe
ratio (when `finagent.risk.risk-free-rate` is configured), portfolio diversification (HHI + sector
concentration), and a transparent composite risk score (LOW/MODERATE/HIGH). All formulas published in
`docs/risk-methodology.md`; unavailable metrics are reported as such, never fabricated. Exposed via
the `analyze_stock_risk` MCP tool, which delegates to `RiskAnalysisService` (no calculations in the
tool). Existing Phase 1–5 tests continue to pass.

**Status:** Phase 7 ✅ COMPLETE — **AI Research Agent**. `ResearchAgent` orchestrates plan (rule-based
`ResearchPlanner`, registry-constrained `ToolSelector`) → execute via the application services with
per-source failures degrading into `FactEntry` data gaps → validated immutable `FactBundle`
(`FactBundleBuilder`, null/NaN/stale checks) → `InterpretationSynthesizer` (Spring AI `ChatClient`,
facts in / prose out, key via `SPRING_AI_OPENAI_API_KEY` env only) → `SafetyGuard`
(numeric-consistency check, ungrounded figures redacted). `AiConfig` wires the research `ChatClient`;
`finagent.ai.*` properties in `FinAgentProperties`; `AgentException` (500) and
`FactBundleIncompleteException` (502) handled globally. Covered by 30 agent tests (unit with mocked
`ChatClient` + `@SpringBootTest` end-to-end on stub providers with `@MockBean ChatClient`).
`mvn clean verify` green — 188 tests, 0 failures. See `docs/research-agent.md`. Next: research
orchestration & history.

**Status:** Phase 8 ✅ COMPLETE — **Research Orchestration & History**. `ResearchOrchestrator`
owns the persisted job lifecycle (`PENDING → RUNNING → COMPLETED/FAILED`, transitions enforced,
terminal states final) over the unchanged Phase 7 agent; `AsyncResearchRunner` executes jobs on a
bounded pool (core 2 / max 4 / queue 50, caller-runs backpressure, no broker), dispatched
after-commit so the runner always sees the `PENDING` row. `POST /api/v1/research` → `202` +
`researchId`; `GET /api/v1/research/{id}` → status + result (`COMPLETED`) or safe `{code, message}`
(`FAILED`: `NO_FACTS` / `INVALID_REQUEST` / `SYNTHESIS_FAILED` / `AGENT_FAILURE`); `GET
/api/v1/research` → newest-first paged history. Results persist in the existing `research_results`
table (guarded prose + deterministic metrics JSON + news summary + disclaimer); lifecycle columns
via new `V2__research_lifecycle.sql` (V1 untouched). `finagent.research.*` properties;
`dev` (DB-less) disables the workflow via `FINAGENT_RESEARCH_ENABLED=false`. Covered by 21 tests
(H2 + `@MockBean ResearchAgent` + MockMvc, incl. latch-gated RUNNING observation, concurrency
isolation, and a dev-profile absence test). `mvn clean verify` green — 209 tests, 0 failures.
See `docs/research-orchestration.md`. STOP — Phase 9 not started.

**Status:** Phase 9 ✅ COMPLETE — **Financial News Sentiment Analysis**. Pluggable
`SentimentAnalyzer` port (`news/sentiment`) with deterministic `LexiconSentimentAnalyzer`
baseline: small configurable lexicon (`finagent.sentiment.*`), per-article
`(pos−neg)/(pos+neg)` over title+description in `[-1.0, +1.0]`, simple-mean aggregation,
labels at ±0.20, evidence-scaled confidence (`min(1,hits/5)`), per-article hit counts for
explainability. `SentimentResult` carries label/score/confidence/counts/methodology;
`UNAVAILABLE` with `NO_ARTICLES`/`PROVIDER_ERROR`/`INSUFFICIENT_CONTENT` is distinct from
`NEUTRAL` and from "not analyzed" (`null` sentiment). `SentimentAnalysisService` wraps the
port (fetch-then-score `analyzeSymbol` for MCP, direct `analyzeNews` for the agent — no
duplicated retrieval, no price-based inference). `ResearchAgent` computes per-ticker sentiment
only when the request calls for it (policy gate: price question skips, sentiment/comprehensive
asks include; provider failure grounds as UNAVAILABLE); score/counts join
`FactBundle.groundedNumbers()` and prompt text. New `analyze_news_sentiment` MCP tool
registered in `McpConfiguration` (no planner/tool-selector changes — 5 planned steps stay 5).
Persistence extended in the existing `metrics_snapshot` JSON (`status` available/unavailable/
not-analyzed + label/score/confidence/counts/methodology) plus sentiment lines in the executive
and news summaries — no migration, no new tables. Covered by 34 tests (known-example analyzer
cases, service error mapping, MCP tool, agent policy/grounding/provide-failure, bundle
grounding, snapshot persistence, MCP integration call). `mvn clean verify` green — 243 tests,
0 failures. See `docs/sentiment-analysis.md`.

**Status:** Phase 10 ✅ COMPLETE — **Professional PDF Research Reports**.
`GET /api/v1/research/{id}/report.pdf`: `200 application/pdf` + safe
`Content-Disposition` for COMPLETED jobs, `404` unknown id, `409`
(`ReportNotReadyException`) for PENDING/RUNNING/FAILED, safe `500`
(`ReportGenerationException`, cause logged with id). `ResearchReportService`
maps the persisted `ResearchStatusDto` to a `ResearchReportData` snapshot;
`PdfReportGenerator` (Apache PDFBox 3.0.3, verified 3.x API, standard-14 fonts,
stateless singleton) renders cover, executive summary, multi-ticker market /
fundamentals / risk tables, news & sentiment, interpretation, gaps &
limitations, and the mandatory disclaimer — presentation only, no AI/MCP/network
calls, nothing written to disk. Missing values print "Unavailable" (sections
without data omitted); word-wrap, multi-page flow with repeating table headers,
per-page header/footer/page numbers; WinAnsi sanitization for exotic Unicode.
Covered by 18 tests (parse-back content/page assertions incl. multi-stock,
multi-page, special-characters, unavailable-data, no-secrets; service gating;
endpoint 200/404/409 shapes). `mvn clean verify` green — 261 tests, 0 failures.
See `docs/pdf-reports.md`.

**Status:** Phase 11 ✅ COMPLETE — **React Research Dashboard**. `frontend/` (Vite 7 + React 18 +
TypeScript strict + Tailwind v4 + React Router 6; no Redux/React Query): `/` dashboard with recent
research, `/research/new` validated form (query ≤2000, ≤10 tickers, symbol pattern) navigating to
`/research/:id` on 202, details page polling every ~2.5s while PENDING/RUNNING and stopping on
terminal states, full COMPLETED rendering (summary, stock cards, market/risk/sentiment tables,
headlines, interpretation, gaps, disclaimer) plus backend-generated PDF download, and `/history`
with backend pagination. Centralized typed API client with friendly errors; responsive Tailwind
layouts; skip link, labels, focus states, text+color badges; missing data renders "Unavailable"
(UNAVAILABLE never shown as NEUTRAL). Only public config in `frontend/.env.example`
(`VITE_API_BASE_URL`) — zero secrets. Backend: one minimal addition, `WebConfig` CORS for
`/api/**` (`FINAGENT_CORS_ALLOWED_ORIGINS`, default `http://localhost:5173`, GET/POST, no
credentials; dev server also proxies `/api`) + 3 contract tests (allow, preflight, reject);
no business-logic changes. Covered by 30 Vitest tests (mocked fetch: form, polling, rendering,
failed/404, PDF, history, client, helpers). `npm run build` green (tsc + vite). `mvn clean verify`
green — 264 tests, 0 failures. See `docs/frontend.md`.

**Status:** Phase 12 ✅ COMPLETE — **Dockerized Local Environment**.
Root `docker-compose.yml`: `postgres` (`postgres:16-alpine`, `pgdata` volume,
`pg_isready` healthcheck) → `backend` (multi-stage `maven:3.9-eclipse-temurin-21`
build → `eclipse-temurin:21-jre` non-root runtime, `curl` actuator healthcheck,
`depends_on` healthy postgres) → `frontend` (`node:20-alpine` build →
`nginx:1.27-alpine` static + `/api/` reverse proxy with SPA fallback, wget
healthcheck). Backend runs the existing `postgres` profile via env
(service-name JDBC URL, `validate` + Flyway V1+V2, no migration changes);
MCP stays in-process; secrets only from `.env` (optional file, dev-only
fallbacks, never baked into images); browser uses same-origin `/api` (empty
`VITE_API_BASE_URL` at image build — verified no absolute backend URL in
`dist/`). Pinned images, `.dockerignore`s, root `.gitignore` covers
`frontend/dist` + `node_modules`. No business-logic changes (one frontend
default tweak: empty base URL = same-origin, covered by a client test).
`docker-compose.yml` YAML-validated via parser; Docker Desktop is unavailable
on this machine so `docker compose config`/smoke test was NOT executed (stated,
not faked). `npm test` 30 green, `npm run build` green, `mvn clean verify`
green — 264 tests, 0 failures. See `docs/docker.md`.

**Status:** Phase 13 ✅ COMPLETE (implementation) — **PostgreSQL Testcontainers Suite**.
`PostgresResearchIT` (failsafe `*IT` naming, 12 tests) boots the full context against an
ephemeral `postgres:16-alpine` container via `@ServiceConnection` (dynamic port, no hardcoding),
reusing the `postgres` profile: Flyway applies V1+V2 (asserted `[1, 2]` + schema-history rows),
Hibernate validates, and the real repositories persist the full research lifecycle
(PENDING→RUNNING→COMPLETED with long unicode snapshot round-trip; FAILED with error info),
history ordering/filtering, enum-as-string, unique-result constraint, `NUMERIC(18,4)` precision,
and `TIMESTAMPTZ` round-trip — all with `@Transactional` rollback isolation, no business-logic
changes, no migration edits. Deps are Boot-managed (testcontainers 1.21.4, test scope);
`mvn verify -Ppostgres-integration` binds failsafe while default `mvn clean verify` never needs
Docker (264 tests green). Honest execution report: Testcontainers jars resolve and everything
compiles, but container execution was NOT possible — Docker is unavailable on this machine
(failsafe run errors at startup with "Could not find a valid Docker environment", verified).
See `docs/postgres-integration.md`.

**Status:** Phase 14 ✅ COMPLETE (definition) — **GitHub Actions CI Quality Gate**.
`.github/workflows/ci.yml` (`CI`, `ubuntu-latest`): `backend`
(`mvn -B clean verify -Ppostgres-integration`, Temurin Java 21, Maven cache —
unit suite on H2 plus the 12 real Testcontainers `postgres:16-alpine` ITs with
Flyway V1+V2 + Hibernate `validate`, stub providers + dummy AI key, no secrets)
and `frontend` (`npm ci` → `npm test` → `npm run build`, Node 20, npm cache) run
in parallel; `docker` (`needs: [backend, frontend]`, Buildx, `docker compose
config`, `docker build ./backend` + `docker build ./frontend`, no push) validates
after both. Triggers: push + pull_request on `main` (`master` included where it
is the default branch). Failure artifacts: surefire/failsafe reports, frontend
dist. No deployment of any kind — CI validation only. Local equivalents +
troubleshooting: see `docs/ci-cd.md`.

**Status:** Phase 15 ✅ COMPLETE — **Production-Readiness + Security + Architecture Audit**
(no features, no redesign; full report in `docs/production-readiness-audit.md`).
No CRITICAL issues; 3 HIGH fixed (dead news-retry wiring, AlphaVantage HTTP-error
mapping, malformed-payload guards) plus synthesis-error sanitization, unused
MCP-client starter removal, PDF header repeats, and dependency upgrades
(`react-router-dom` 6.30.6, `vitest` 3.2.7). Accepted by design: no auth (stay
private), dev-default credentials, H2/PostgreSQL two-tier testing.

**Status:** Phase 16 ✅ COMPLETE — **Security Hardening + Authentication + API Protection**.
JWT boundary (HS256, 1h, no refresh): `POST /api/v1/auth/register` (always USER,
409 on duplicate) + `/login` (generic 401s) + `GET /me`; BCrypt-10 hashes, never
stored/returned/logged in plaintext. `users` table via additive `V3__create_users.sql`
(UUID, unique normalized email, role CHECK, TIMESTAMPTZ). Roles USER/ADMIN
(`GET /api/v1/admin/users` ADMIN-only; no self-promotion path). Matrix: health/
actuator/preflights public; stocks/research/`/me`/`/mcp` authenticated; admin
ADMIN-only; Swagger gated by `finagent.swagger.enabled` (off in prod, 3 layers).
In-memory fixed-window rate limits (auth 10/min, research 30/min → 429;
single-instance documented). `prod` profile (`application-prod.yml`) with
fail-fast `ProductionSecretValidator` (JWT/OpenAI/DB/provider keys; values never
logged). CSRF off by documented decision (Bearer, no cookies); default security
headers; CORS unchanged (configurable origins, no wildcard). Notable fix found by
testing: `JwtAuthenticationFilter` authenticates async dispatches (MCP SSE
streaming broke anonymously otherwise). Frontend: login/register pages,
`AuthProvider`, `RequireAuth` guards, Bearer client, localStorage tradeoff
documented, sign-out. Tests: 320 backend green (56 new incl. PG `PostgresAuthIT`),
41 frontend green. Full details: `docs/security.md`.
STOP — Phase 17 not started.

**Status:** Phase 17 ✅ COMPLETE — **Final Production Hardening** (no features,
no redesign). AI synthesis timeout (60s default on a dedicated bounded pool,
hung calls interrupted, jobs fail safe; tested with a never-answering mock).
Full-Unicode PDFs: bundled DejaVu Sans 2.37 + GNU Unifont 14.0.01 fallback with
per-run switching and matching metrics (licenses bundled; no runtime download,
no OS fonts); unmappable-anywhere glyphs dropped, never `?`/crash; plus a
binary-search `fittingPrefix` fix for an O(n²) multi-minute hang found by the
hostile-input test. Immutable `audit_events` trail (Flyway V4 additive: auth,
research, admin events; no FK; no secrets — email hashes only; own-transaction
writes that can never break a request). Non-root frontend
(`nginx-unprivileged`, 8080→80 mapping, nginx security headers);
container-aware backend heap. Weekly Dependabot (no auto-merge). Verified
security headers (tested), JWT `alg=none` rejection (tested), login/token
hygiene re-verified. Full details: `docs/final-hardening.md`.
Tests: 333 backend green (surefire), 42 frontend green, 18 PostgreSQL/Testcontainers
ITs (CI/Docker-gated, unexecuted locally).
STOP. Final engineering phase complete — no Phase 18.
