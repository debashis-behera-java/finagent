# FINAGENT — Security Architecture (Phase 16)

Stateless JWT authentication + role authorization + rate limiting for the
research API. No server-side sessions, no external identity provider — the
single cookie in the system is the HttpOnly refresh-token cookie (Task 3,
§15); API authorization itself remains a stateless Bearer token.

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
- Lifetime 1h (`FINAGENT_AUTH_TOKEN_TTL`); renewed via refresh tokens (see §14 —
  Task 2 replaced the old re-authenticate-only design).
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

`/api/**` only, GET/POST only, no wildcard — origins from
`FINAGENT_CORS_ALLOWED_ORIGINS` (default `http://localhost:5173`).
Production must set explicit origins. Preflights (`OPTIONS`) are permitted
through the security chain (they carry no credentials).

Task 3: credentials are now **enabled** (`allowCredentials(true)`), because
the refresh token travels in the HttpOnly `finagent_rt` cookie — without it
browsers would neither send the cookie nor accept `Set-Cookie`. Consequences:

- Allowed origins stay explicit (never `*`): Spring rejects
  `allowCredentials(true)` combined with a wildcard at startup, and the prod
  validator refuses localhost/wildcard origins on the `prod` profile.
- Verified behavior (see `AuthRefreshCookieTest`): an allowed origin gets an
  echoed `Access-Control-Allow-Origin` plus
  `Access-Control-Allow-Credentials: true`; a disallowed origin is rejected
  fail-closed (403, no origin echo); preflight from an allowed origin
  succeeds with credentials.
- API authorization still uses the Bearer header; cookies carry only the
  refresh token, and only to `/api/v1/auth/*` (cookie `Path` restriction).

## 8. CSRF decision

CSRF protection stays **disabled by configuration, deliberately** — but the
reasoning changed with Task 3, so it is restated explicitly (HttpOnly alone
is NOT a CSRF defense; it only stops JavaScript token theft):

- The refresh-token cookie is `SameSite=Lax`: browsers withhold it on
  cross-site subrequests (fetch/XHR) AND on cross-site top-level form POSTs.
- The only cookie-bearing endpoints are `POST /api/v1/auth/refresh` and
  `POST /api/v1/auth/logout`. There is no state-changing GET anywhere in the
  API, so the one request class Lax still allows (top-level navigational GET)
  cannot mutate anything or rotate a session.
- API authorization (research, stocks, admin, MCP) uses the Bearer header,
  which a forged cross-site request cannot supply.

Net: a forged cross-site request either carries no credentials (Bearer
endpoints) or has its cookie withheld by the browser (cookie endpoints). No
CSRF-token framework is introduced — that would be a disproportionate
redesign for an attack with no reachable vector under this endpoint design.
If a cookie-bearing state-changing GET is ever added, or the cookie moves to
`SameSite=None`, this decision MUST be revisited and synchronizer CSRF tokens
(or an equivalent) introduced.

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
- Frontend stores the short-lived access JWT (1h) in localStorage: simple and
  reload-proof, but any XSS could read it — mitigated by zero HTML sinks (all
  content renders as React text) and the 1h lifetime. The long-lived refresh
  token is HttpOnly-cookie-only since Task 3 (§15), so XSS cannot steal the
  session credential; it could still abuse a live access token until expiry.
  This application is NOT claimed to be XSS-proof.
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

## 14. Refresh-token rotation (Task 2)

Supersedes the old "no refresh tokens" note in §2: login/register now
establish a token pair (short-lived access JWT in the JSON body + long-lived
single-use refresh token). Since Task 3 (§15) the refresh half of the pair
is delivered as an HttpOnly cookie, NOT in JSON — the service-layer pair
(`AuthService.AuthResult`) is unchanged; only the transport changed.

- Storage: `refresh_tokens` table (Flyway V5). Only the SHA-256 hash is stored —
  raw tokens exist solely in issuance responses, never in logs, errors, or JWT claims.
- Secrets: raw tokens are 32 bytes from `SecureRandom` (Base64URL); UUIDs are
  row/family identifiers only, never secret material.
- Rotation (`POST /api/v1/auth/refresh`, public + rate-limited): the presented
  token is marked used and exactly one replacement in the same family is minted,
  atomically. A token works once.
- Reuse detection: replaying a consumed token rejects the request (generic 401 —
  missing/expired/revoked/reused are indistinguishable) and revokes the whole
  family (theft signal). Concurrent double-use serializes on a pessimistic row
  lock, so two valid replacements are impossible.
- Expiration: `finagent.auth.refresh-token-ttl` (`FINAGENT_AUTH_REFRESH_TTL`,
  default 14d) — never hardcoded.
- Logout (`POST /api/v1/auth/logout`, idempotent): revokes the whole family,
  ending the session chain. Stateless access JWTs cannot be revoked — they
  expire (1h); revocation applies to refresh tokens only.
- Frontend: unchanged (no auto-refresh logic exists). The refresh token is
  returned to the SPA under the same localStorage tradeoff as the access token
  (§12), with a longer lifetime — rotation, reuse detection, and logout are the
  mitigations. (Superseded for browsers by the Task 3 HttpOnly-cookie
  migration, §15; the JSON-body token remains only as a non-browser fallback.)

## 15. HttpOnly refresh cookie (Task 3)

Browser model after this task:

- **Access JWT**: short-lived (1h), Bearer header, still in `localStorage`
  (smallest safe migration — decision A, §9 tradeoff documented; NOT
  XSS-proof, NOT claimed immune).
- **Refresh token**: HttpOnly cookie `finagent_rt`, never in JSON, never in
  JS storage/state/URLs/logs, never in the database (hash-only, unchanged).

Cookie attributes (`RefreshTokenCookies`; config in `finagent.auth`):

| Attribute | Value | Rationale |
|---|---|---|
| `HttpOnly` | always `true` | JS (and XSS payloads) cannot read the session credential |
| `Secure` | `true` in prod (`application-prod.yml`), `false` dev/test (`FINAGENT_AUTH_COOKIE_SECURE`) | browsers drop `Secure` cookies over plain HTTP — dev must stay usable; prod is HTTPS-only (HSTS) |
| `SameSite` | `Lax` default (`FINAGENT_AUTH_COOKIE_SAME_SITE`) | same-origin dev proxy + same-site/reverse-proxy prod layouts work; cross-site POST presentation is withheld (CSRF analysis §8). `None` only for genuine cross-site deployments AND requires `Secure` |
| `Path` | `/api/v1/auth` | cookie is presented only to auth endpoints, never to research/stocks/admin traffic |
| `Max-Age` | = refresh TTL (14d default) | cookie expiry tracks server-side session expiry; no second lifetime |
| Name | `finagent_rt` | opaque label, no secret material in the name |

Endpoint behavior:

- `POST /register`, `POST /login`: set the cookie via `Set-Cookie`; JSON
  body carries `refreshToken: null` explicitly (older clients fail visibly
  instead of misreading the shape) plus the unchanged access JWT and
  `refreshExpiresIn` (a lifetime is not a secret).
- `POST /refresh`: reads the cookie (no body needed). Rotation semantics
  unchanged (single-use, family reuse-kill, pessimistic locking, generic
  401s). The response sets the replacement cookie and its body contains no
  raw token. A JSON-body token is still accepted as a **fallback for
  non-browser API clients** (cookie wins when both are present; both absent
  → generic 401). The SPA never uses the body path.
- `POST /logout`: revokes the presented token's family (idempotent, silent
  on unknown/blank — no validity oracle) and clears the cookie with
  `Max-Age=0` under the same name/path/flags (mismatched attributes would
  leave the original cookie alive). Stateless access JWTs still cannot be
  revoked — they expire within the hour; no revocation is claimed.
- Frontend: `credentials: 'include'` on all API calls; `refreshSession()` /
  `logoutSession()` send no token from JS; `signOut()` ends the server
  family best-effort and always drops local state.

Local development: unchanged — Vite proxies `/api` same-origin, plain HTTP,
`Secure=false`, `SameSite=Lax` all work with no extra setup. Production
checklist: HTTPS, `FINAGENT_AUTH_COOKIE_SECURE=true` (forced by
`application-prod.yml`), explicit `FINAGENT_CORS_ALLOWED_ORIGINS`, and
`SameSite=None` + `Secure` ONLY if the frontend is genuinely cross-site
(which re-opens the CSRF analysis in §8).

## 16. Refresh-token housekeeping + session observability (Task 4)

Authentication semantics are unchanged: no new token types, no JWT changes,
no rotation/revocation logic changes, no schema migration (V1–V5 untouched —
the existing `user_id`/`family_id` indexes already serve the new count
queries; the cleanup is a predicate DELETE needing no new index at this
table size).

### Lifecycle recap

`issue` (login/register) → `active` → `rotate` marks the row used and mints
a same-family successor → replay of a used row revokes the whole family
(theft signal) → `logout` revokes the family. Every row carries
`expires_at` (creation + 14d default). Rotation checks expiry FIRST, then
revoked, then used — this ordering is what makes the cleanup below safe.

### Cleanup policy (`RefreshTokenCleanupService`)

One bulk `DELETE` per run — no token entity (and no hash) is ever loaded
into memory; only the deleted-row count returns. A row is deleted only when
dead AND past the retention grace (`finagent.auth.refresh-token.retention`,
default 7d):

- `expires_at <= now − retention`: `rotate()` rejects these as expired
  before consulting `used_at`/`revoked_at`, so the replay tripwire is
  already spent — unknown-401 vs expired-401 is the same generic 401.
- `revoked_at <= now − retention`: presenting a revoked row changes no
  state (the family is already dead) — deleting it is observationally
  identical.

NEVER deleted: used-but-unexpired rows (the live replay tripwires) and the
currently usable token of every family (active ⇒ neither expired nor
revoked). Concurrent rotations only touch live rows, which the predicate
cannot match — job and rotation are disjoint by construction. Single
transaction per run; the table grows at most ~2 rows per login/rotation, so
one hourly statement is bounded in practice (no batching layer, no Redis).

### Scheduler (`RefreshTokenCleanupScheduler`, `SchedulingConfig`)

Spring `@Scheduled` on the framework default single-threaded scheduler —
no custom thread pool. `cleanup-interval` default 1h
(`FINAGENT_AUTH_REFRESH_CLEANUP_INTERVAL`), `cleanup-enabled` default true
(`FINAGENT_AUTH_REFRESH_CLEANUP_ENABLED`, re-checked every firing so
operators can pause without a restart). Production sets
`cleanup-enabled: true` explicitly in `application-prod.yml` so the feature
is never silently disabled. Every run logs the deleted-row count (never
token values, never hashes); any database failure is caught, logged by
message only, and contained — the app keeps serving and the next run
retries. Absent in the DB-less dev profile (same `auth.enabled` condition
as all auth beans).

### Observability (`GET /api/v1/admin/auth/sessions`, ADMIN only)

Fits the existing `/api/v1/admin/**` → `hasRole("ADMIN")` matcher; no
`SecurityConfig` change. Returns counts only —
`{active, revoked, expired, families, lastCleanupAt, lastCleanupDeleted}` —
viewed under audit event `ADMIN_SESSION_STATS_VIEWED`. `active` = currently
usable; `revoked`/`expired` are independent counters (a row can be both),
not a partition; `lastCleanupAt` is null before the first run. Anonymous →
401, USER (mock or genuine token) → 403, ADMIN → 200. No token values, no
hashes, no secrets in the response (asserted by tests against live DB
content) or in logs.
