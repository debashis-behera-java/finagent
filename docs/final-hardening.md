# FINAGENT — Final Production Hardening (Phase 17)

Last engineering phase: genuine production-readiness defects fixed, everything
else documented. No features, no redesign, no IAM/cloud/trading work.

> GitHub Actions remote execution has not yet been performed. Docker is
> unavailable on this machine — image builds and container runs remain pending
> (stated, not faked). Dockerfiles were statically validated (YAML/syntax,
> port and user review).

## 1. Production hardening

- AI synthesis calls are time-bounded (fail fast, no hung jobs).
- PDFs render full Unicode via bundled licensed fonts (no runtime download).
- Immutable security audit trail for auth/research/admin events.
- Both production images run non-root; backend heap is container-aware.
- Weekly Dependabot checks (Maven/npm/GitHub Actions, no auto-merge).
- Production profile verified secret-tight; dev/test dummies intact.

## 2. AI timeout

`InterpretationSynthesizer` runs the model call on a dedicated bounded pool
(`synthesisExecutor`: 2–4 threads, queue 20, caller-runs) under
`finagent.ai.synthesis-timeout` (default 60s, env `FINAGENT_AI_SYNTHESIS_TIMEOUT`).
On timeout the in-flight call is interrupted (`Future.cancel(true)`) and the
job fails safe as `SYNTHESIS_FAILED` with a generic message — no hung research
threads, no leaked pool capacity, no internal details exposed. No retries: a
timeout is definitive for that run; the user resubmits. Tested with a
never-answering mock (fails in ~100ms, not 30s).

## 3. PDF Unicode

- Primary: DejaVu Sans 2.37 family (Latin, Greek, Cyrillic, Armenian,
  Georgian, currency symbols, punctuation) — embedded subsets.
- Fallback: GNU Unifont 14.0.01 (~12 MB) for CJK, Indic, Arabic, Hebrew, Thai,
  symbols — per-run font switching with matching width accounting.
- Both bundled under `backend/src/main/resources/fonts/` with licenses
  (`LICENSE` + `README.txt`); no runtime download, no OS-font dependency.
- Unmappable-anywhere glyphs are dropped; controls stripped; never `?`,
  never a crash. WinAnsi+sanitize remains as the offline fallback if resources
  are ever missing.
- Side fix (found by testing): `fittingPrefix` was O(n²) font-metric calls —
  a 500-char token took the suite to 6 minutes. Now binary search; the hostile
  suite runs in ~2s. Long-token handling is a hard break, not a hang.

## 4. Audit trail

`audit_events` (Flyway V4, additive): UUID, `occurred_at`, `event_type`
(closed enum: register, login success/failure, research submitted/completed/
failed, admin listing), `user_id` (nullable, NO foreign key — rows survive
account deletion), `result` (SUCCESS/FAILURE/DENIED, CHECKed), `metadata`
(small JSON, truncated at 2000). Recorded via `AuditService` in its own
transaction (`REQUIRES_NEW`, failures swallowed to a warn log — the audit
store can never break a request). Failed logins store a SHA-256 email hash,
never the address; passwords/tokens/keys/bodies are unrepresentable by API
design. Wired into `AuthService`, `ResearchOrchestrator` (submitter id from
the request thread; async completions unattributed by design), and
`AdminController`. No retention scheduler — retention/archival is a documented
future operational concern; nothing is kept in application memory.

## 5. Container security

- Frontend: `nginxinc/nginx-unprivileged:1.27-alpine` (non-root `nginx` user),
  listens 8080 → host 80 mapping unchanged for users; security headers added
  in nginx (`nosniff`, `Referrer-Policy`, `SAMEORIGIN` framing); static `dist`
  only, no secrets.
- Backend: already non-root (`finagent`); added `-XX:MaxRAMPercentage=75.0`;
  config/secrets arrive at runtime via env, nothing baked in. `curl` retained
  for the Compose actuator healthcheck (minimal, documented).

## 6. Dependency security

- `npm audit`: only 2 remaining moderates (react-router v7-line advisories;
  fix = breaking major 7.x — rejected; verified non-applicable: no SSR, all
  navigation targets static or server-UUID). `react-router-dom` 6.30.6 is the
  latest 6.x; `vitest` 3.2.7 has no advisories (5.x major rejected as redesign
  risk). No `audit fix --force`.
- Maven: Boot-managed current versions (Boot 3.5.15, Spring AI BOM 1.1.8,
  PDFBox 3.0.3, Testcontainers 1.21.x); jjwt 0.12.6 pinned explicitly. No new
  scanners.
- Dependabot: `.github/dependabot.yml` — weekly Maven/npm/Actions checks, no
  auto-merge (CI gate must pass).

## 7. JWT security

Model unchanged from Phase 16 and re-verified: 1h HS256, minimal claims,
≥32-byte env secret, signature+expiry validation, role loaded live from DB
(revocation path = delete/demote), `alg=none` and foreign-key tokens rejected
(tested), malformed/expired covered (tested). No refresh tokens, no OAuth2,
no revocation database — documented limitations, not gaps.

## 8. Rate limiting

Re-verified: fixed-window per IP+group, auth 10/min + research 30/min,
bounded (10k buckets, stalest evicted), expiry tested, 429 keeps the ApiError
shape on both the controller path (advice) and the filter path (direct render
— filters can't reach `RestControllerAdvice`). Covered endpoints: register,
login, research creation. Distributed deployments still need a shared limiter
(documented; Redis deliberately not introduced).

## 9. Production configuration

- `application-prod.yml`: postgres (no defaults — missing env fails fast),
  Flyway + `validate`, Swagger triple-off, auth/research on, INFO logging.
- `ProductionSecretValidator` unchanged and unit-covered; dev/test keep
  deterministic dummies; tests never need real secrets.
- `.env.example` documents every Phase 16/17 variable with generation
  guidance (`openssl rand -base64 48`).
- Compose: frontend 80→8080 mapping + `:8080` healthcheck updated for the
  unprivileged image; secrets via optional `.env`, never baked in.

## 10. Known limitations

No account lockout; no token revocation list; per-instance limiter; localStorage
JWT (XSS tradeoff documented); dev defaults insecure by design; no synthesis
retries; fallback PDF glyphs unstyled; audit unattributed on async paths;
retention unscheduled; no SSR-applicable router fixes without v7; no deployment.

## 11. Docker verification status

Docker unavailable locally; image builds, `compose config` execution, and the
smoke test remain pending. Static validation performed: Dockerfiles reviewed
(base pins, USER, COPY scope, EXPOSE, ENTRYPOINT), nginx `listen 8080`
consistent with the `80:8080` mapping and `:8080` healthcheck, compose YAML
parses with the expected services.

## 12. GitHub Actions verification status

Workflow reviewed (Java 21, Node 20, Maven/npm caches, `verify
-Ppostgres-integration` covering V1–V4 + all `*IT`s, frontend test/build,
compose validation, image builds, no secrets, no deploy) with only the
migration-count comment updated. Remote execution still pending — no pass
claimed. Dependabot config added (weekly, no auto-merge).
