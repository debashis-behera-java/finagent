# FINAGENT — PostgreSQL Integration Testing (Phase 13)

## 1. Why Testcontainers exists

H2 (`test` profile, `create-drop`) proves mappings but cannot prove PostgreSQL
compatibility: `UUID`/`TIMESTAMPTZ`/`NUMERIC` handling, real CHECK/UNIQUE/FK
constraints, Flyway migrations, and `ddl-auto=validate` are only exercised
against the real engine. Testcontainers starts an ephemeral PostgreSQL 16 per
test-class run — no manual `docker run postgres`, no permanent databases, no
locally installed server.

## 2. PostgreSQL version

`postgres:16-alpine`, pinned in `PostgresResearchIT` (same image family as
`docker-compose.yml`). Never `latest`.

## 3. How the container is started

`com.finagent.repository.PostgresResearchIT` (failsafe `*IT` naming):

```java
@Testcontainers
@SpringBootTest
@ActiveProfiles("postgres")
@Transactional
class PostgresResearchIT {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("finagent");
```

One shared container per class; Testcontainers starts it, waits for readiness,
and removes it afterwards. `@Transactional` rolls every test back — order-
independent, reproducible, no cleanup code.

## 4. Datasource injection

`@ServiceConnection` (Spring Boot 3.1+ mechanism via `spring-boot-testcontainers`)
overrides `spring.datasource.url/username/password` from the running container,
including its dynamically mapped port — nothing is hardcoded, production
configuration is untouched. The reused `postgres` profile contributes Flyway
(enabled), `ddl-auto=validate`, and the OpenAI dummy key for context boot.

## 5. Flyway behavior

Flyway is **enabled** (never `enabled=false` here): on context start it applies
`V1__init.sql` + `V2__research_lifecycle.sql`, then Hibernate validates the
mappings against the migrated schema. Tests assert `flyway.info().applied()`
is exactly `[1, 2]` and `flyway_schema_history` has ≥2 successful rows, plus a
per-table smoke query over all seven tables.

## 6. Hibernate validation

`ddl-auto=validate` in the active profile — context startup itself is the
validation proof (any mapping/schema drift fails fast before any test runs).

## 7. Integration test scope (12 tests)

Context + connection (real PG 16, `finagent` database); Flyway history;
migrated tables; research lifecycle PENDING→RUNNING→COMPLETED with result
(executive/interpretation/long unicode metrics JSON/sentiment round-trip) and
FAILED with error code/message; newest-first history + status filter queries;
enum-as-string via native query; unique-result-per-request constraint;
`NUMERIC(18,4)` precision; `TIMESTAMPTZ` round-trip. All use the real entities
and repositories — no fakes.

## 8. Required Docker/container runtime

Testcontainers needs Docker. Without it the suite errors at container startup
(`Could not find a valid Docker environment`) — observed on the dev machine,
which has no Docker (see §11).

## 9. Commands

```bash
cd backend
mvn clean verify                                  # default: 333 H2/mock tests, no Docker needed
mvn verify -Ppostgres-integration                 # + Testcontainers suite (needs Docker)
```

Surefire's default includes (`*Test`) never match `*IT`, so the container
suite cannot break the normal build; the `postgres-integration` profile binds
maven-failsafe (`integration-test` + `verify`).

## 10. Troubleshooting

| Symptom | Fix |
|---|---|
| `Could not find a valid Docker environment` | install/start Docker Desktop, re-run with the profile |
| Image pull slow on first run | expected — `postgres:16-alpine` downloads once, then cached |
| `Password authentication failed` | stale `pgdata` volume from compose (`down -v`, destructive) — unrelated to tests (own container) |
| IT passes locally, fails in CI | ensure the CI runner provides Docker (e.g. docker-in-docker service) |

## 11. H2 tests vs PostgreSQL tests

| | H2 (`test` profile) | Testcontainers (`postgres` profile) |
|---|---|---|
| Engine | in-memory H2 | real PostgreSQL 16 |
| Schema | Hibernate `create-drop` | Flyway V1+V2, Hibernate `validate` |
| Speed | milliseconds | seconds + image pull once |
| Docker needed | no | yes |
| Catches | mapping/query bugs | type/constraint/migration/precision bugs |

Both stay: fast feedback by default, real-engine proof on demand.
