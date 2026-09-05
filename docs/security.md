# FINAGENT — Security Architecture (Phase 16)

Stateless JWT authentication + role authorization + rate limiting for the
research API. No sessions, no cookies, no external identity provider.

## 1. Authentication architecture

```
register/login (public, rate-limited)
  → AuthService (BCrypt verify, normalize email)
  → JwtService issues HS256 token (1h)
  → client sends `Authorization: Bearer <token>`
  → JwtAuthenticationFilter verifies signature+expiry, reloads user,
    sets Authentication (role read live from DB)
  → AuthorizationFilter enforces the matrix below
```

Endpoints (`AuthController`, absent in the DB-less dev profile):

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | public, rate-limited | create USER (201 + token) |
| `POST` | `/api/v1/auth/login` | public, rate-limited | authenticate (200 + token) |
| `GET` | `/api/v1/auth/me` | Bearer | current profile |

Login failures always return generic `Invalid email or password` (401) —
never revealing email existence. Passwords are never returned, never logged.

## 2. JWT design

- Algorithm HS256, secret from `FINAGENT_AUTH_JWT_SECRET` (≥32 bytes; dev/test
  placeholders refused by the prod validator).
- Claims: `sub` (user id), `email`, `role`, `iat`, `exp`. Nothing else — no
  passwords, keys, secrets, or research content.
- Lifetime 1h (`FINAGENT_AUTH_TOKEN_TTL`), no refresh tokens (deliberate:
  keeps the token boundary small; users re-authenticate).
- Validation is stateless (signature + expiry, no DB); the filter then reloads
  the user row, so role changes/deletions take effect without waiting for
  expiry (one cheap indexed lookup per request).
- The filter authenticates **async dispatches too** (`shouldNotFilterAsyncDispatch
  → false`): servlet async (used by the MCP server's SSE streaming) re-enters
  the security chain on another thread, and the default skip would arrive
  anonymous, fail authorization, and break the committed response stream.

## 3. Password hashing

BCrypt (`BCryptPasswordEncoder`, cost 10, `FINAGENT_AUTH_BCRYPT_STRENGTH`).
Rules enforced server-side: 8–72 chars (72 = BCrypt truncation limit),
letter + digit. Rate limiting runs BEFORE BCrypt so attackers can't burn CPU.

## 4. Role model

`USER` (default; the only registrable role — the request has no role field) and
`ADMIN` (assigned out-of-band, e.g. direct DB update). No self-promotion
endpoint exists. `GET /api/v1/admin/users` (ADMIN only) lists accounts without
password material, so the boundary is real and testable.

## 5. Authorization matrix

| Path | Rule |
|---|---|
| `POST /api/v1/auth/register`, `POST /api/v1/auth/login` | public (rate-limited) |
| `GET /api/v1/health`, `/actuator/health`, `/actuator/info`, `/error`, CORS preflights | public |
| `/api/v1/auth/me`, `/api/v1/stocks/**`, `/api/v1/research/**` | authenticated (any role) |
| `/mcp`, `/mcp/**` | authenticated (any role) |
| `/api/v1/admin/**` | `ADMIN` only |
| `/swagger-ui/**`, `/v3/api-docs/**` | public iff `finagent.swagger.enabled`, else denied |
| everything else | denied |

When `finagent.auth.enabled=false` (DB-less dev profile only) the API stays
open exactly like pre-Phase-16, with a loud startup warning. Never use dev in
production.

## 6. Rate limiting

In-memory fixed-window limiter (`RateLimitService`): per client IP + group
(AUTH: 10/min; research creation: 30/min; all env-tunable). Bounded
(10k buckets max, stalest evicted, windows reset in place — no leak, no
background threads). Rejections are 429 JSON (`Too Many Requests`).
**Single-instance only** — each node counts separately; distributed
deployments need a shared store (Redis). Auth checks run before BCrypt and
before validation-independent work; the research filter renders 429 directly
(filters can't reach `RestControllerAdvice`).

## 7. CORS

Unchanged mechanism, tightened posture: `/api/**` only, GET/POST only, no
credentials, no wildcard — origins from `FINAGENT_CORS_ALLOWED_ORIGINS`
(default `http://localhost:5173`). Production must set explicit origins.
Preflights (`OPTIONS`) are permitted through the security chain (they carry no
credentials). Authenticated browser calls use the Bearer header, not cookies.

## 8. CSRF decision

CSRF protection is **disabled by configuration, deliberately**: authentication
is Bearer header tokens, never cookies — a forged cross-site request carries
no credentials to abuse, so there is no CSRF attack surface. If cookie auth is
ever introduced, CSRF tokens must be re-enabled.

## 9. MCP protection

`/mcp` requires a Bearer token like any API (verified: anonymous → 401,
bogus → 401, valid → full tool flow). External MCP clients must register an
account and pass the JWT. Kill-switch `FINAGENT_MCP_ENABLED=false` remains.
Keep `/mcp` off public networks; it spends server-side provider quota.

## 10. Swagger protection

`finagent.swagger.enabled` (default true; **false in prod** via
`application-prod.yml`). Disabled = OpenAPI bean absent + springdoc switches
off + security-chain deny (three layers; authenticated callers get 403).
Dev/test keep it open.

## 11. Production secret requirements (`prod` profile)

`ProductionSecretValidator` (runs only on `prod`) refuses startup when any of
these hold — messages name the variable, NEVER its value:

- JWT secret missing / default / placeholder-ish / <32 bytes
- OpenAI key missing or the dummy value
- DB password missing or in the known-default list
- non-stub market/news provider without its API key

Required prod env: `FINAGENT_AUTH_JWT_SECRET` (generate: `openssl rand -base64 48`),
`SPRING_AI_OPENAI_API_KEY`, `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`,
`FINAGENT_CORS_ALLOWED_ORIGINS` (explicit), provider keys only when enabled.

## 12. Security limitations (honest)

- No brute-force account lockout (rate limiting only); no refresh-token
  rotation; no token revocation list (deletion/role change is the revocation
  path — enforced live since roles load from DB).
- In-memory limiter is per-instance; no WAF; dev defaults are insecure by
  design (prod validator is the guardrail, not the defaults).
- Frontend stores the JWT in localStorage: simple and reload-proof, but any
  XSS could read it — mitigated by zero HTML sinks (all content renders as
  React text), short 1h lifetime, and no refresh tokens. A httpOnly-cookie
  design would need CSRF + backend changes (future work).
- `research_tool_executions`/`report_metadata` remain write-dead (Phase 15);
  no security event audit trail yet.

## 13. Local development setup

```bash
# DB-less dev (auth OFF, API open — local only)
cd backend && mvn spring-boot:run

# Full stack with auth (needs Docker Postgres)
docker run -d --name finagent-db -e POSTGRES_DB=finagent \
  -e POSTGRES_USER=finagent -e POSTGRES_PASSWORD=change-me -p 5432:5432 postgres:16
SPRING_PROFILES_ACTIVE=postgres SPRING_DATASOURCE_PASSWORD=change-me mvn spring-boot:run

# Register + call
curl -s -X POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"analyst@example.com","password":"Secret123"}'
TOKEN=... # from .accessToken
curl -s localhost:8080/api/v1/stocks/AAPL -H "Authorization: Bearer $TOKEN"
```

Frontend: open the dev server, register at `/register`, sign in at `/login`;
protected routes bounce anonymous visitors to `/login` automatically.
