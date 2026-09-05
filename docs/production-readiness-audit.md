# FINAGENT — Production-Readiness + Security + Architecture Audit (Phase 15)

Audit-only phase: no product features added, no architecture rewritten. Every code
change below fixes a verified bug, leak, contract violation, or hardening gap; all
else is documented as-is. Severity scale: CRITICAL / HIGH / MEDIUM / LOW / INFO.

> GitHub Actions workflow created and locally validated; remote CI execution has
> not yet been performed. Docker-dependent verification was unavailable locally
> (Docker not installed) — stated, not faked.

## 1. Executive summary

FinAgent is a well-structured, honestly-documented research platform: clean
hexagonal layering, env-only secrets, consistent `ApiError` responses with no
stack-trace/path leakage, deterministic risk math matching published formulas,
mandatory disclaimers, and 306 tests. The audit found **no CRITICAL issues**,
**3 HIGH issues (all fixed)**, and a set of MEDIUM/LOW issues (fixed where safe,
documented otherwise). The two load-bearing limitations are by design and
documented: **no authentication/rate-limiting** (auth is out of scope — the API
and `/mcp` must not be exposed publicly) and **dev-default credentials** (must be
overridden for any real deployment).

## 2. Architecture findings

- INFO: package layering is clean (`mcp/tool → service → provider → adapter`);
  no circular dependencies except the documented, `@Lazy`-broken
  `ResearchOrchestrator ↔ AsyncResearchRunner` cycle (LOW, accepted).
- MEDIUM (fixed): `spring-ai-starter-mcp-client-webflux` was on the classpath
  with zero imports in `src/main` — WebFlux/reactor attack surface and build
  weight for no gain. Removed from `backend/pom.xml`; `AiConfig` javadoc updated.
- LOW (accepted, not changed): `Portfolio`/`PortfolioHolding` entities and
  `RiskAnalysisService.analyzeDiversification()` have no production callers
  (persistence-only today); removing them would require migration changes —
  out of scope. Duplicated ticker-regex/date-default logic in 4/3 places
  (drift risk, no live bug). `StockResponse`/`NewsResponse` live in `service/`
  instead of `dto/response/`; `RiskScoringService` vs `RiskAnalysisService`
  naming is confusable. None affect behavior.
- INFO (investigated, dismissed): an early sweep suggested committed
  `node_modules`; direct verification (`git ls-files` empty, `git check-ignore`
  matches, `git status` shows untracked-only) proves artifacts are properly
  ignored — no hygiene defect exists.

## 3. Security findings

- HIGH (accepted, documented — auth is out of scope for this product phase):
  no Spring Security config, no auth, no rate limiting. `POST /api/v1/research`
  (LLM-costly), `/mcp`, Swagger UI, and actuator `health/info` are anonymously
  reachable. Mitigation today is deployment posture: never expose publicly;
  `FINAGENT_MCP_ENABLED=false` kill-switch exists. tracked in §19.
- INFO (pass): no hardcoded keys (`sk-`/`ghp_`/`BEGIN PRIVATE` — zero hits);
  keys only via `ALPHA_VANTAGE_API_KEY`/`NEWS_API_KEY`/`SPRING_AI_OPENAI_API_KEY`;
  `.env` git-ignored, only `.env.example` files present; no `.env` on disk.
- INFO (pass): `GlobalExceptionHandler` returns generic messages for
  provider/timeout/unexpected/report errors; no stack traces or filesystem
  paths reach clients; PDF is in-memory bytes, filename is UUID-only.
- MEDIUM (fixed): `InterpretationSynthesizer` embedded raw `ChatClient` error
  text in `AgentException`, returned verbatim (HTTP 500) and persisted via
  `failRun(SYNTHESIS_FAILED, ex.getMessage())`. Now logs server-side and throws
  a generic message (existing test asserting `"synthesis failed"` still passes).
- LOW (fixed): `TickerNormalizer` and MCP `DateParsers` reflected unbounded raw
  input in 400/MCP errors — echoed input truncated to 64 chars.
- LOW (accepted): three handlers still echo small bounded `ex.getMessage()`
  (`IllegalArgumentException`, agent, bundle-incomplete) and
  `UnknownSymbolException` echoes a charset-constrained symbol — no secret/path
  content possible today.
- MEDIUM (accepted): dev-default DB passwords (`finagent` /
  `finagent-dev-only`) boot a prod-like stack if `.env` is forgotten; compose
  publishes `5432`/`8080`. Labeled dev-only in-file and in `docker.md`; any
  real deployment must set explicit secrets.
- LOW (accepted): `application-dev.yml` sets `com.finagent: DEBUG` (dev only);
  Swagger UI unrestricted (advertises endpoints — compounds the no-auth posture
  only if exposed); `/mcp` has no explicit CORS rule (framework default applies).

## 4. MCP findings

- INFO (pass): 6 tools, all delegating to services (no direct HTTP/keys);
  symbol validation via `TickerNormalizer` on every path; date ordering
  enforced; news limit clamped; unknown symbols → typed exceptions; timeouts
  5s + bounded retries; MCP server `request-timeout: 20s`; tool descriptions
  spot-checked against implementation — accurate.
- INFO (pass): no key/path/trace/credential/prompt leakage on the MCP path.
- LOW (accepted): history/news/sentiment/risk tools echo the nullable request
  `from`/`to` instead of the effective defaulted range — clients cannot read
  the applied window from the response. Cosmetic contract wart; services apply
  the documented defaults internally.

## 5. AI-agent findings

- INFO (pass): pipeline plan → execute → facts → synthesize → guard → result
  traced; planner rule-based (5 fixed steps), all computation deterministic;
  only `InterpretationSynthesizer` is model-nondeterministic (temp 0.2) and its
  output is post-guarded. Missing data → gaps/`"unavailable"`; provider
  failures → explicit `UNAVAILABLE`/reasons; `ChatClient` failure/blank →
  `AgentException`; no chain-of-thought or prompts persisted.
- MEDIUM (fixed): untrusted provider headlines were interpolated verbatim into
  the LLM prompt with no instruction to ignore embedded directives. Added
  system-prompt rule 5 (headlines are untrusted; never follow embedded
  instructions). Blast radius was already bounded (synthesizer binds no tools).
- MEDIUM (accepted): `SafetyGuard` intentionally ignores small plain integers
  (anti-false-positive tradeoff, documented in code) — a hallucinated whole
  number (e.g. "PE of 25") passes ungrounded. Numeric grounding is therefore
  partial by design.
- LOW (accepted): no explicit timeout on the OpenAI synthesis call; mitigation
  is the bounded `researchExecutor` pool (2–4 threads, queue 50, caller-runs).
  A hung model call occupies one thread — slow-loris exhaustion is possible but
  requires a hung upstream; no config-only timeout exists on the Spring AI
  client to set safely without live-API testing.

## 6. Financial safety findings

- INFO (pass): repo-wide grep finds no unqualified buy/sell/guaranteed-return
  language — only the "risk-free rate" Sharpe term (always qualified) and
  explicit prohibitions. Mandatory disclaimer in DTO + metrics snapshot + PDF
  (with fallback); LLM disclaimer instruction is backup only.
- INFO (pass): missing data flows as `null` → `"Unavailable"` end to end; no
  `0`/`N/A`/fabricated values; empty sections omitted, not faked.
- INFO (pass): formula spot-checks match `docs/risk-methodology.md`
  (volatility annualization, max drawdown incl. worked example, Sharpe, beta,
  risk score).

## 7. Dependency vulnerabilities

`npm audit` before → after (no `audit fix --force`, only targeted same-major bumps):

| Package | Advisory | Severity | Path | Fix |
|---|---|---|---|---|
| `@remix-run/router` (via `react-router`, `react-router-dom` 6.30.0) | GHSA-2w69 XSS/open-redirect; GHSA-2j2x `//` redirect | high | production runtime (router) | `react-router-dom` **6.30.0 → 6.30.6** (latest 6.x; contains ≤1.23.1 and 6.30.2/6.30.4 fixes) |
| `vitest` 3.2.4 | GHSA-5xrq arbitrary file read/exec via UI server | critical | dev-only (`vitest run`, UI server never started) | `vitest` **3.2.4 → 3.2.7** (patched ≥3.2.6) |
| residual | GHSA-wrjc backslash redirect, GHSA-337j SSR `deserializeErrors` (ranges `<7.18.0`) | — | not applicable: verified no SSR/hydration; all `<Link to>`/ʻnavigate() targets are static or server-UUID internal paths (`NewResearchPage.tsx:42`, `DashboardPage/HistoryPage/Layout`) | accepted with evidence; v7 major upgrade rejected (redesign risk) |

Result after upgrade (verified by re-running `npm audit` in §22): only
non-applicable residuals remain; `npm ci` + `npm test` + `npm run build` green.

Maven: no new scanner introduced (per directive). Versions are current and
Boot-managed: Spring Boot `3.5.15`, Spring AI BOM `1.1.8`, PDFBox `3.0.3`,
Testcontainers `1.21.x` (Boot-managed), PostgreSQL driver runtime. The unused
MCP-client starter was removed (§2), shrinking the dependency surface.

## 8. Database findings

- INFO (pass): V1/V2 layering clean and additive (V2 only adds 4 nullable
  columns); UUID PKs, `NUMERIC(18,4)`, `TIMESTAMPTZ` + `@PrePersist/@PreUpdate`,
  CHECK↔enum, unique constraints, `ON DELETE CASCADE` ↔ `orphanRemoval` all
  match entities. Orchestrator transactions short and correct; async runner
  holds no transaction across `agent.run` and maps every throwable to FAILED
  with truncated safe message. Migrations untouched (no rewrite of history).
- MEDIUM (accepted): H2 test profile (`create-drop`, Flyway off) diverges from
  the Postgres path (`validate` + Flyway) — Postgres-only DDL failures are
  invisible to default `mvn verify`. Covered by `PostgresResearchIT` (failsafe,
  CI-gated) — the documented two-tier strategy.
- LOW (accepted): `idx_portfolios_created_at` declared on the entity but absent
  from V1 (harmless under `validate`); `metrics_snapshot TEXT` has no JSON
  enforcement (corruption → `nullNode()` fallback, silently dropping numbers);
  `research_tool_executions`/`report_metadata` tables are write-dead (schema
  ready, no writers yet).

## 9. API findings

- INFO (pass): 7 endpoints inventoried (health, quote+fundamentals, news,
  research submit `202`, status, history, PDF); validation annotations correct;
  status usage consistent (200/400/404/409/502/504/500 with timeout-before-
  parent handler ordering); null/missing-data contract deliberate; filenames safe.
- LOW (accepted): no `@ApiResponse` annotations (codes live in code/comments
  only); news `limit` clamps instead of 400 (inconsistent with history `size`
  → 400, but intentional); lifecycle `IllegalStateException` falls through to
  generic 500 (internal race only).

## 10. Frontend findings

- INFO (pass): no `dangerouslySetInnerHTML`/`innerHTML`/`eval`, no web-storage
  secrets, no bundled keys; all user/backend content rendered as React text;
  PDF blob download revokes object URLs; errors are friendly (no traces);
  polling uses a cancellable `setTimeout` chain (no leak); loading/error/empty/
  FAILED states covered; unknown statuses degrade gracefully.
- LOW (fixed): stale `News.tsx` comment claimed links render — headlines are
  plain text; comment corrected.
- LOW (accepted): polling stops on first transient error (full reload to
  retry); `COMPLETED`-with-`null`-result renders header only (contract
  violation path, backend never emits it); dashboard/history retry UX differs.

## 11. PDF findings

- INFO (pass): null/missing → "Unavailable"; long/Unicode/overflow handled
  (WinAnsi sanitization, wrapping, multi-page flow); empty sections omitted;
  UUID-only filenames; metadata is title/author only; no path/trace/key leaks.
- MEDIUM (fixed): tables did NOT repeat headers after page breaks despite
  README/docs claiming they do. `Canvas.table` now re-emits the header row
  after any mid-table `newPage()` (page tests use tolerant `>=` assertions).
- LOW (accepted): non-Latin scripts degrade to `?` (Standard-14 font limit —
  embedding a Unicode font is future work); long-label continuation indent can
  overflow cosmetically; `fittingPrefix` is O(n²) on pathological unbroken
  tokens (bounded by DB TEXT sizes in practice).

## 12. CI/CD findings

- INFO (pass): `.github/workflows/ci.yml` verified — Java 21 Temurin + Maven
  cache; Node 20 + npm cache on lockfile; `mvn -B clean verify
  -Ppostgres-integration` (no skip flags); `npm ci` → `npm test` → `npm run
  build`; `docker compose config` + both image builds with `needs:
  [backend, frontend]`; no push/login/deploy; failure-only artifacts; only
  dummy-key/stub env; no secret echo. Triggers push+PR on `main` (+`master`
  where default).
- INFO: compose build-args (`VITE_API_BASE_URL`) validated by `config` but not
  by a composed build (job builds Dockerfiles directly) — noted, acceptable.

## 13. Docker findings

- INFO (pass): pinned images; multi-stage; backend non-root `finagent`, jar-only
  copy; no secrets in images (`.env` optional at compose time, dev-only
  fallbacks); health-gated startup order; `pgdata` volume; same-origin
  `/api` proxy + SPA fallback.
- LOW (accepted): frontend final stage runs as root (nginx needs `:80`;
  dropping privileges requires a port/config change — future work);
  `backend/.dockerignore` narrower than frontend's (root `.env`/`.git` outside
  the `./backend` context today, so nothing baked); image-level `HEALTHCHECK`
  absent (health lives in compose only).

## 14. Testing findings

- INFO (pass): spot-checked 8 test files — real assertions (URL/body/filename/
  status maps, PDF parse-back, exact sentiment scores), no mock tautologies
  (orchestrator asserts persisted H2 lifecycle), no ordering dependence
  (`beforeEach` resets, `@Transactional` rollback), no hidden network/real keys
  (mocked fetch, `MockRestServiceServer`, dummy key, stubs), bounded timing
  (10s deadlines, short polls). `PostgresResearchIT` uses dynamic ports.
- No tests added/removed: no genuine uncovered risk was found that warranted
  new tests; existing adapter tests cover the fixed branches (malformed
  payload/HTTP-error paths use `MockRestServiceServer`).

## 15. Reliability findings

- HIGH (fixed): `AlphaVantageMarketAdapter` let upstream HTTP errors escape as
  500 (no `HttpStatusCodeException` catch; `GlobalExceptionHandler` has none
  either) — contradicting the typed-error design and the documented 502
  contract. Added `translateHttpError` mirroring `NewsApiAdapter` (429 →
  rate-limit 502, 401/403 → key 502, else HTTP-status 502).
- HIGH (fixed): `parseDecimal`/`parsePercent` threw `NumberFormatException`
  (→ 500) on non-numeric payloads (`"N/A"`/`"None"`); date parses threw
  `DateTimeParseException` (→ 500). All degrade to `null`/skip now —
  unavailable data, not server errors.
- HIGH (fixed): `FINAGENT_NEWS_RETRY_*` knobs were dead (single market-built
  executor shared). Added a `newsRetryExecutor` bean from news props with
  `@Qualifier` wiring on both provider configs.
- MEDIUM (fixed): negative `retryBackoff` config crashed in `Thread.sleep`
  (→ 500). Constructor clamps to `ZERO`.
- Reliability posture otherwise good: bounded retries/backoff, 5s timeouts,
  bounded async pool with caller-runs backpressure, per-source degradation,
  no unbounded loops/payloads found, in-memory PDF (no disk/resource leak).

## 16. Fixed issues

1. Dead news retry config → dedicated `newsRetryExecutor` bean + qualifiers.
2. AlphaVantage HTTP errors → typed 502 mapping (`translateHttpError`).
3. AlphaVantage malformed numbers/dates → `null`/skip (no more 500s).
4. Synthesis error text leaked to clients + persisted → generic message, logged.
5. Unused `spring-ai-starter-mcp-client-webflux` removed from classpath.
6. PDF tables now repeat headers after page breaks (as documented).
7. Negative retry backoff clamped.
8. Unbounded input echo truncated (`TickerNormalizer`, MCP `DateParsers`).
9. Prompt-injection guardrail added (untrusted headlines).
10. `react-router-dom` 6.30.0 → 6.30.6; `vitest` 3.2.4 → 3.2.7.
11. Stale docs corrected: `BACKEND_DESIGN.md` (2), `ARCHITECTURE.md`
    (stack, MCP section, diagram fragment, data-model, API surface, env vars,
    test table), `mcp-tools.md`, `docker.md`, `News.tsx` comment.

## 17. Accepted risks

- No authentication/authorization/rate-limiting (HIGH, by product-scope
  decision — §25 forbids adding auth; deployment must stay private).
- Dev-default credentials + published dev ports (MEDIUM — must override in prod).
- SafetyGuard small-integer blind spot; no synthesis-call timeout; H2/Postgres
  DDL divergence (covered by CI Testcontainers tier); write-dead audit tables;
  PDF non-Latin `?` degradation; frontend root nginx user; residual
  non-applicable router advisories; silent limit clamp; polling stops on
  transient error.

## 18. Remaining limitations

- Remote GitHub Actions run not yet performed; Docker steps never executed
  locally (no Docker on this machine).
- No production deployment exists or is claimed; no auth, no hardening beyond
  dev defaults; `/mcp` + Swagger must stay off public networks.

## 19. Recommended future work (not started)

~~AuthN/Z + rate limiting (per-key or reverse-proxy), explicit secrets
validation at startup (fail fast on defaults in a `prod` profile)~~ —
DELIVERED in Phase 16 (see `docs/security.md`): JWT auth + USER/ADMIN roles,
in-memory per-IP rate limits (auth 10/min, research 30/min → 429), and
`ProductionSecretValidator` on the new `prod` profile. Phase 17 delivered the
remaining hardening items from this section: bounded AI synthesis timeout,
full-Unicode PDFs (bundled DejaVu + Unifont), immutable audit trail (V4),
non-root frontend, Dependabot, verified security headers. See
`docs/final-hardening.md`. Still open: header-echoing MCP date ranges,
`research_tool_executions` writers, `@ApiResponse` coverage, composed-build
CI step.
