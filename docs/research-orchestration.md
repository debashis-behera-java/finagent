# FINAGENT — Research Orchestration & History (Phase 8)

## What it does

Turns the synchronous Phase 7 agent into a persisted job workflow:

```
POST /api/v1/research  →  202 {researchId, status: PENDING}
GET  /api/v1/research/{id}  →  PENDING | RUNNING | COMPLETED (+result) | FAILED (+error)
GET  /api/v1/research?page=&size=  →  history, newest first (paged)
```

## Lifecycle

```
PENDING → RUNNING → COMPLETED
PENDING → RUNNING → FAILED
```

Transitions are enforced in `ResearchOrchestrator.transitionTo`: terminal states
(`COMPLETED`, `FAILED`) have no outgoing edges — no retry in this phase.
An illegal transition is an `IllegalStateException` (server bug, HTTP 500).

## Architecture

```
ResearchController (thin: HTTP + validation + status codes)
        │  submit / get / history
        ▼
ResearchOrchestrator (lifecycle owner, short transactions only)
        │  PENDING row + after-commit dispatch
        ▼
AsyncResearchRunner (@Async on the bounded researchExecutor pool)
        │  RUNNING → agent → COMPLETED/FAILED rows
        ▼
ResearchAgent (Phase 7, unchanged) → services → MCP tools → FactBundle → synthesis
        ▼
ResearchRequestRepository → research_requests / research_results (JPA/Flyway)
```

Key design points:

- **Async without a broker.** A local bounded `ThreadPoolTaskExecutor`
  (`finagent.research.*`: core 2, max 4, queue 50, `CallerRunsPolicy` backpressure,
  30s graceful shutdown). No Kafka/RabbitMQ/Redis.
- **After-commit dispatch.** `submitResearch` persists PENDING and registers the
  background run via `TransactionSynchronization.afterCommit` — the runner thread
  would otherwise race the committing transaction and silently never see the row.
  One registration per invocation, so a request can never execute twice.
- **No long transactions.** The runner holds no transaction across the agent call;
  every state change (`beginRun`/`completeRun`/`failRun`) is its own short
  transaction. Controllers never touch entities (mapping happens in read-only
  orchestrator transactions — required since `open-in-view` is false).
- **Failure capture.** Every throwable maps to a FAILED row: `NO_FACTS`
  (`FactBundleIncompleteException`), `INVALID_REQUEST` (planner validation),
  `SYNTHESIS_FAILED` (`AgentException`), `AGENT_FAILURE` (anything else, generic
  message). One failing job never affects another; a job can never stick in
  RUNNING (an `AsyncUncaughtExceptionHandler` logging safety net backs this up).

## API

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/v1/research` | `202` `{researchId, status, createdAt}` | `400` blank query / bad ticker / >10 tickers |
| `GET` | `/api/v1/research/{id}` | `200` status + `result` (COMPLETED) or `error{code,message}` (FAILED) | `400` malformed UUID, `404` unknown id |
| `GET` | `/api/v1/research?page=0&size=20` | `200` `{content[], page, size, totalElements, totalPages}` (newest first, no result payloads) | `400` bad paging (`size` 1–100) |

Request body: `{"query": "...", "tickers": ["AAPL"]}` — `tickers` optional
(the planner can extract `$SYM` mentions). History items omit `result`/`error`;
the detail endpoint includes them. Error responses never contain stack traces,
keys, headers, or system paths.

## Persistence

- Reuses the Phase 2 entities — no new tables. `V2__research_lifecycle.sql`
  (new migration, V1 untouched) adds `started_at`, `completed_at`,
  `error_code`, `error_message` to `research_requests` (all nullable,
  PostgreSQL-compatible; H2 tests use Hibernate `create-drop` as before).
- Results reuse `research_results`: guarded `interpretation` prose,
  deterministic `executiveSummary`, factual `sentimentSummary` (headline counts —
  no sentiment analysis), and `metrics_snapshot` JSON (tickers, per-ticker
  price/fundamentals/risk/news/gaps, guard verdict, disclaimer). No prompts,
  no chain-of-thought, no secrets.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `FINAGENT_RESEARCH_ENABLED` | `true` | Master switch (`false` in the DB-less `dev` profile) |
| `FINAGENT_RESEARCH_CORE_POOL_SIZE` | `2` | Executor core threads |
| `FINAGENT_RESEARCH_MAX_POOL_SIZE` | `4` | Executor max threads |
| `FINAGENT_RESEARCH_QUEUE_CAPACITY` | `50` | Queue before caller-runs backpressure |

## Observability

Logs per run: id + tickers at submit, `PENDING → RUNNING`, final transition
with duration in ms and error code on failure. Never logs keys, headers,
prompts, or full tool payloads.

## Testing (no PG/Docker/internet/LLM)

- `ResearchOrchestratorTest` (`@SpringBootTest`, H2, `@MockBean ResearchAgent`):
  submit→PENDING, latch-gated RUNNING observation, COMPLETED with persisted
  result JSON, all four FAILED codes (incl. no-leak assertion), 404, newest-first
  paged history, concurrent isolation, transition-rule unit checks.
- `ResearchControllerTest` (MockMvc): 202 shapes, all 400/404 paths, COMPLETED
  vs FAILED response shapes, history paging.
- `ResearchDisabledWithoutDatabaseTest` (`dev` profile): context boots without
  the workflow beans — the documented no-DB tradeoff, now covered by a test.

## Limitations

- No retry of FAILED jobs; no cancellation of RUNNING jobs.
- No per-user scoping/auth — history is global (auth arrives with a later phase).
- In-memory executor: queued/running jobs are lost on restart (a durable outbox
  is future work, not needed at this scale).
- The `dev` profile serves no `/api/v1/research*` endpoints (needs the
  `postgres`/`test` database); stock/MCP endpoints are unaffected.
