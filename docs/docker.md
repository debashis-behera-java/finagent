# FINAGENT — Dockerized Local Environment (Phase 12)

## 1. Architecture

```
                ┌──────────────────────────────────────────────┐
                │              Docker Compose network           │
                │                                               │
browser ──►     │  frontend (nginx:80) ──/api/*──► backend:8080 │
:80             │       │                           │            │
                │   static React             postgres:5432      │
                │    (same-origin)          (Flyway migrations) │
                └──────────────────────────────────────────────┘
```

MCP stays in-process inside the backend (no separate container — same as
Phases 5–7). The browser only ever talks same-origin to nginx, which reverse-
proxies `/api/` to the backend; React Router deep links (`/research/new`,
`/research/{id}`, `/history`) fall back to `index.html` via `try_files`.

## 2. Services

| Service | Image | Ports | Healthcheck |
|---|---|---|---|
| `postgres` | `postgres:16-alpine` (pinned) | `5432:5432` | `pg_isready -U … -d finagent` (5s/×10) |
| `backend` | built: Maven 3.9 + Temurin 21 → `eclipse-temurin:21-jre`, non-root `finagent` | `8080:8080` | `curl -sf /actuator/health` (10s/×5, 90s grace for JVM + Flyway) |
| `frontend` | built: `node:20-alpine` → `nginx:1.27-alpine` static | `80:80` | busybox `wget /` (15s/×3) |

Readiness is ordered with `depends_on: { condition: service_healthy }` —
startup order is never assumed. Only `health`/`info` actuator endpoints are
exposed (existing `application.yml`).

## 3. Ports

- `:80` — dashboard (nginx). Open this in the browser.
- `:8080` — backend API + Swagger UI (direct access, debugging).
- `:5432` — PostgreSQL (psql/debugging; drop the mapping if it conflicts).

## 4. Volumes

Named volume `pgdata → /var/lib/postgresql/data`: data survives
`docker compose down` / `up`. **Destructive reset:**
`docker compose down -v` deletes the volume (documented as destructive —
all research history is lost).

## 5. Environment variables

Secrets come from `.env` (copy `.env.example`; file optional at compose time,
never baked into images):

| Variable | Compose default | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | forced `postgres` | PG profile (`validate` + Flyway) |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/finagent` | service-name host (never localhost inside containers) |
| `SPRING_DATASOURCE_USERNAME` | `finagent` | PG user (matches `POSTGRES_USER`) |
| `SPRING_DATASOURCE_PASSWORD` | `finagent-dev-only` | **dev-only fallback — set a real one in `.env`** (matches `POSTGRES_PASSWORD`) |
| `SPRING_AI_OPENAI_API_KEY` | `test-dummy-key-not-real` | real key in `.env` enables AI synthesis; dummy boots with stubs |
| `FINAGENT_CORS_ALLOWED_ORIGINS` | `http://localhost` | direct `:8080` debugging (proxied traffic needs no CORS) |
| `FINAGENT_MCP_ENABLED` | `true` | in-process MCP server (local use; don't expose publicly) |
| `VITE_API_BASE_URL` | empty (same-origin) | set to a backend URL only for non-proxied hosting (+ matching CORS) |
| `NEWS_API_KEY`, `ALPHA_VANTAGE_API_KEY` | via `.env` | server-side only; providers default to `stub` |

## 6. Startup / shutdown / logs / reset

```bash
cp .env.example .env          # then edit: passwords, optional API keys
docker compose up --build     # full stack; no Maven/npm needed
docker compose ps             # postgres + backend + frontend healthy
docker compose logs -f backend
docker compose down           # stop, keep data
docker compose down -v        # ⚠ destroy all data
```

## 7. Health checks & Flyway behavior

Backend boots on the `postgres` profile: Flyway runs `V1__init.sql` +
`V2__research_lifecycle.sql` automatically, Hibernate uses `ddl-auto=validate`
(never create/update). Verify after boot:

```bash
curl http://localhost:8080/actuator/health            # {"status":"UP"}
docker compose exec postgres psql -U finagent -d finagent \
  -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"
```

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| backend restarts, `Connection refused` to postgres | wait for `postgres (healthy)`; compose orders this automatically |
| port already allocated | another PG/nginx running — stop it or remap ports in `docker-compose.yml` |
| frontend shows "Cannot reach backend" | `docker compose ps` — backend must be healthy; check `logs backend` |
| research stays PENDING | normal without `SPRING_AI_OPENAI_API_KEY`? No — stubs run offline; check backend logs |
| `Keystore was tampered` / auth failure on PG | password changed with old volume: `down -v` (destructive) and re-`up` |

## 9. Logging & security notes

Compose logs contain no keys/passwords (services log ids and statuses only).
Images: pinned versions, minimal JRE/alpine runtimes, non-root backend,
`.dockerignore` excludes `target/`, `node_modules/`, `dist/`, `.env*`, IDE
files. This is a reproducible **local** environment, not production hardening.

## 10. Limitations

- Docker Desktop required (unavailable on some dev machines — then use the
  native `mvn`/`npm` workflows in `README.md`).
- First backend build downloads Maven dependencies (network + several minutes).
- No Testcontainers suite here (Phase 13); H2 tests remain the automated gate.
  (Superseded: Phase 13 added `PostgresResearchIT` — see `postgres-integration.md` —
  and Phase 14 runs it in CI. The statement above describes the Phase 12 scope.)
