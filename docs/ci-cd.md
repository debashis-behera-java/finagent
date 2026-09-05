# FINAGENT — CI/CD (Phase 14: CI only, no deployment)

Professional GitHub Actions quality gate: every commit must pass the same
automated checks before being considered healthy.

> GitHub Actions workflow created and locally validated; remote CI execution
> has not yet been performed (the workflow has not yet run on GitHub).

## 1. Workflow architecture

File: `.github/workflows/ci.yml` (`CI` workflow).

```
                    ┌──────────────┐
                    │ push / PR to │
                    │ main, master │
                    └──────┬───────┘
                           │
              ┌────────────┴────────────┐
              │                         │
      ┌───────▼────────┐      ┌─────────▼────────┐
      │ backend        │      │ frontend         │   (parallel)
      │ Java 21        │      │ Node 20          │
      │ mvn verify     │      │ npm ci/test/build│
      │ + Testcont. PG │      │                  │
      └───────┬────────┘      └─────────┬────────┘
              │                         │
              └────────────┬────────────┘
                           │ needs: [backend, frontend]
                  ┌────────▼────────┐
                  │ docker          │   (validation only)
                  │ compose config  │
                  │ + 2 image builds│   (no push, no deploy)
                  └─────────────────┘
```

No deployment step exists anywhere: no registry push, no AWS/Azure/GCP,
no Render/Railway, no Kubernetes. Phase 14 is CI validation only.

## 2. Trigger conditions

```yaml
on:
  push:
    branches: [main, master]
  pull_request:
    branches: [main, master]
```

`main` is the canonical branch per the phase directive; `master` is included
so the gate also runs on repositories whose default branch is still `master`.
Both push and pull-request events run the full gate.

## 3. Backend job

- Runner: `ubuntu-latest`.
- `actions/setup-java@v4`: Temurin `21`, Maven dependency caching.
- Working directory: `backend/`.
- Command: `mvn -B clean verify -Ppostgres-integration` (never `-DskipTests`).
  - Surefire: full unit suite (H2 in-memory, Flyway disabled via
    `application-test.yml`) — needs no Docker.
  - Failsafe (bound only by the `postgres-integration` Maven profile):
    `PostgresResearchIT` (`*IT` naming — excluded from default builds)
    boots ephemeral `postgres:16-alpine` via Testcontainers
    (`@ServiceConnection`, dynamic port): Flyway applies V1+V2, Hibernate
    `validate`s, repositories persist the research lifecycle (12 tests).
- Environment (explicit, deterministic, non-secret):
  `SPRING_AI_OPENAI_API_KEY=test-dummy-key-not-real`,
  `FINAGENT_MARKET_PROVIDER=stub`, `FINAGENT_NEWS_PROVIDER=stub`.
- On failure: uploads `backend/target/surefire-reports/` +
  `backend/target/failsafe-reports/` as the `backend-test-reports` artifact.
- Maven output is never hidden: `BUILD SUCCESS` / `BUILD FAILURE` shows in logs.

## 4. Frontend job

- Runner: `ubuntu-latest`.
- `actions/setup-node@v4`: Node `20` (matches the verified toolchain and the
  `node:20-alpine` build image), npm caching via `frontend/package-lock.json`.
- Working directory: `frontend/`. Runs in parallel with `backend`.
- Commands (exact local equivalents): `npm ci` (never `npm install` — the
  lockfile exists and is authoritative) → `npm test` (`vitest run`, mocked
  fetch, no backend needed) → `npm run build` (`tsc --noEmit && vite build`).
- Any failure (test, type-check, Vite build) fails the job; errors are never
  suppressed. On failure: uploads `frontend/dist/` when present for diagnosis.

## 5. PostgreSQL / Testcontainers

- No manually installed PostgreSQL is required: Docker is preinstalled on
  GitHub-hosted `ubuntu-latest` runners, and Testcontainers starts its own
  ephemeral `postgres:16-alpine` container per run (destroyed afterwards —
  nothing cached).
- The suite verifies the real chain: container starts → Flyway V1+V2 execute
  (asserted `[1, 2]`) → Spring Boot context starts on the `postgres` profile
  → Hibernate validates the schema (never creates) → repositories persist and
  query the research lifecycle → 12 integration tests pass.
- H2 is never substituted: the IT class is `@ActiveProfiles("postgres")` with
  `@ServiceConnection` injection; Flyway stays enabled on that path.

## 6. Docker validation

- Job `docker` runs after `backend` + `frontend` (`needs:`), since image
  builds are only meaningful once the applications compile and test green.
- `docker/setup-buildx-action@v3` enables BuildKit builds.
- `docker compose config` fails the job on any invalid Compose file.
- `docker build ./backend` and `docker build ./frontend` validate both
  multi-stage images without pushing (no registry credentials needed or used).

## 7. Secrets policy

- Zero secrets in the workflow YAML, Dockerfiles, source, or frontend env
  files. CI needs none: stub providers are offline/deterministic and the AI
  key is a non-functional dummy (context loads; real synthesis would fail
  fast, and no test attempts it).
- If secrets are ever needed (e.g. future deployment), they must come from
  GitHub Actions Secrets — never echoed, never printed, never committed.
  `.env` stays git-ignored (see `.env.example` for the full variable list).

## 8. Local equivalent commands

```bash
# backend — unit suite only (no Docker needed)
cd backend
mvn clean verify

# backend — unit suite + real PostgreSQL/Testcontainers ITs (Docker needed)
mvn clean verify -Ppostgres-integration

# frontend (Node 20)
cd frontend
npm ci
npm test
npm run build

# compose validation (Docker needed)
docker compose config

# image builds (Docker needed, validation only — no push)
docker build ./backend
docker build ./frontend
```

## 9. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Backend job fails with "Could not find a valid Docker environment" | Runner without Docker, or local Docker Desktop stopped | Use `ubuntu-latest` (Docker preinstalled); locally, start Docker Desktop and re-run with `-Ppostgres-integration` |
| `npm ci` fails on lockfile mismatch | `package.json` edited without `package-lock.json` | Run `npm install` locally once, commit the updated lockfile |
| `docker compose config` fails | YAML edit broke `docker-compose.yml` | Validate locally; check indentation and `${VAR:-default}` syntax |
| Frontend build fails on type error | `tsc --noEmit` gate caught it (by design — no suppression) | Fix the TypeScript error, don't bypass the gate |
| Surefire green but failsafe red | Unit code fine; migration/schema drift vs `postgres` profile | Compare `db/migration/*.sql` against entities (`ddl-auto=validate`) |

## 10. Future deployment possibilities (NOT implemented)

Phase 14 deliberately stops at CI. If a later phase adds continuous
deployment, the natural shape is a separate `cd.yml` (or a `deploy` job
gated on `main` + manual approval) that reuses these validated artifacts,
pushes versioned images to a registry via GitHub Actions Secrets, and
deploys to the chosen platform. None of that exists yet, and the README
makes no deployment claims.
