# Realtime GPS Server

A re-implementation of the **Realtime Location Service (RLS) GPS Server** as Spring Boot
microservices on Java 21.

## How this repository is built

The platform is delivered **one small, self-contained commit at a time**. Every commit:

- compiles and passes its tests (`./scripts/build.sh`),
- does one reviewable thing, and
- updates this README, so the README always describes exactly what exists at that commit.

Commits are grouped into milestones (`C1`, `C2`, …); individual commits are numbered within a
milestone (`C1.1`, `C1.2`, …). The **Commit log** at the bottom is the running history.

---

## Tech stack

| Concern | Choice |
|---|---|
| Language / runtime | Java 21 (LTS) |
| Framework | Spring Boot 3.5.15 |
| Distributed config & discovery | Spring Cloud 2025.0.3 + Consul |
| Build | Maven multi-module |
| Tests | JUnit 5, AssertJ, Testcontainers (`*IT`) |

---

## Building

Prerequisites: **JDK 21** and **Maven 3.9+**.

Maven on the development machine defaults to JDK 17, so use the wrapper script — it pins
`JAVA_HOME` to JDK 21 before delegating to Maven:

```bash
./scripts/build.sh
```

```bash
./scripts/build.sh -DskipTests package
```

If your JDK 21 is installed somewhere other than `C:/Program Files/Java/jdk-21`, set
`GPS_JAVA_HOME` first. Plain `mvn verify` works too when your own `JAVA_HOME` is already a JDK 21.

**Test conventions:** `*Test` = unit/slice tests (surefire, `mvn test`); `*IT` = Testcontainers
integration tests (failsafe, `mvn verify`). Coverage reports land in `target/site/jacoco`.

---

## Repository layout

```
realtime-gps/
├── pom.xml                  # parent POM: Java 21, Spring Boot/Cloud BOMs, surefire/failsafe/jacoco
├── libs/gps-common/         # shared library every service depends on
├── services/id-service/     # authentication and authorization
├── deploy/                  # docker-compose stack, MySQL init SQL, Consul KV seed files
├── scripts/                 # build.sh (JDK 21), up.sh / down.sh / seed.sh (local stack)
└── docs/images/             # architecture diagram from the original spec
```

---

## The system being built

![GPS Server Infrastructure](docs/images/gps-server-infrastructure.png)

The platform ingests high-volume user location pings, caches each user's last known position for
radius queries, streams every position through a message queue into durable storage, and serves
location history and per-user metadata — all behind an API gateway that authenticates every call.
The requirements are in [`GPS SERVER.md`](GPS%20SERVER.md).

```
                    ┌──────────────────────── Kong (:8000) ────────────────────────┐
   Clients ────────►│  rls_auth plugin ──► id-service /api/v1/internal/authenticate │
                    └───┬───────────────┬───────────────┬──────────────────────────┘
                        │               │               │
                 ping-service    history-service   metadata-service      id-service
                        │               ▲                │                    │
                 Redis  │               │ RabbitMQ       │ MySQL              │ MySQL
             (last loc, │               │ (sole          │ gps_metadata       │ gps_id
              GEO index)└──► RabbitMQ ──┘  consumer)     │                    │
                                         └──► MySQL gps_history

  Consul: configuration (KV) + service registry + DNS used by Kong to resolve upstreams
```

| Service | Responsibility |
|---|---|
| **Ping** | Receives all location pings. Buffers them in memory, publishes them to RabbitMQ in bulk, keeps each user's *last* location in Redis, answers radius queries. |
| **History** | The **only** RabbitMQ consumer. Writes locations to MySQL and serves location history. |
| **Id** | Companies, users, tokens, and the gateway's authenticate hook. |
| **Metadata** | Dynamic per-user JSON metadata with a MATCH/EXCEPT/ANY/ALL query language. |
| **Gateway (Kong)** | The only public entry point; restricted endpoints are never routed through it. |

## Milestones

| # | Milestone | Commits | Status |
|---|---|---|---|
| C1 | Scaffolding + `gps-common` shared library | C1.1 – C1.8 | ✅ done |
| C2 | Local infrastructure (MySQL, Redis, RabbitMQ, Consul) | C2.1 – C2.4 | 🟡 written, not yet run¹ |
| C3 | Id service — company signup | C3.1 – C3.6 | ✅ done (ITs pending Docker¹) |
| C4 | Id service — users, tokens, internal authenticate | C4.1 – C4.6 | ⬜ planned |
| C5 | Kong gateway + `rls_auth` plugin | C5.1 – C5.5 | ⬜ planned |
| C6 | Ping service — Redis write path | C6.1 – C6.4 | ⬜ planned |
| C7 | Ping service — buffer → RabbitMQ bulk publish | C7.1 – C7.4 | ⬜ planned |
| C8 | Ping service — radius search | C8.1 – C8.2 | ⬜ planned |
| C9 | History service — queue consumer → MySQL | C9.1 – C9.4 | ⬜ planned |
| C10 | History service — history API | C10.1 – C10.2 | ⬜ planned |
| C11 | Metadata service — CRUD | C11.1 – C11.5 | ⬜ planned |
| C12 | Metadata service — MATCH/EXCEPT/ANY/ALL search | C12.1 – C12.5 | ⬜ planned |
| C13 | Observability, resilience, OpenAPI | C13.1 – C13.4 | ⬜ planned |
| C14 | End-to-end suite + load harness | C14.1 – C14.3 | ⬜ planned |

¹ Anything needing containers is unverified at runtime: the Docker daemon will not start on this
machine (its WSL disk image is missing). Compose definitions are validated with `docker compose config`
and shell scripts are syntax-checked; Testcontainers integration tests are written and compile, but
report as *skipped* rather than passing. They run for real as soon as Docker works.

---

## Running the local stack

Requires **Docker Desktop running**.

```bash
./scripts/up.sh
```

```bash
./scripts/down.sh
```

`up.sh` starts every container, waits for its health check, and re-seeds Consul KV. `down.sh -v`
also discards the MySQL/Redis/RabbitMQ data volumes for a clean slate.

---

## Shared library — `libs/gps-common`

Every service depends on this module, so cross-cutting behaviour is defined once.

### Vocabulary

[`GpsHeaders`](libs/gps-common/src/main/java/com/rls/gps/common/web/GpsHeaders.java) names the
headers exchanged between Kong and the services. The identity headers — `X-Company-Id`,
`X-User-Id`, `X-App-Key` — are **never** trusted from a client: the gateway strips whatever the
caller sent and re-injects the values it verified.

[`Identity`](libs/gps-common/src/main/java/com/rls/gps/common/security/Identity.java) is the
immutable caller identity carried on every downstream request. `isComplete()` (company **and** user
present, non-blank) is what "authenticated" means to a service.

### What a service gets for free

Depend on the module and the auto-configuration supplies all of this — no imports, no boilerplate:

| Capability | Type | Behaviour |
|---|---|---|
| `GET /api/v1/ping` | `PingController` | service name, status, build version; unauthenticated for probes |
| Gateway trust check | `GatewayTokenFilter` | constant-time shared-secret check; 401 if the request did not come through Kong |
| Identity injection | `IdentityFilter`, `@CurrentIdentity` | headers → `Identity` + MDC; 401 when an endpoint needs one and there is none |
| Correlation ids | `CorrelationIdFilter` | adopt/mint, MDC, response header, `traceId` in errors |
| Errors | `GlobalExceptionHandler`, `ProblemWriter` | RFC 7807 with a stable `code` |

Filter order is correlation id (`HIGHEST+10`) → gateway token (`+20`) → identity (`+30`), so even a
rejected request is logged with a trace id.

Configuration ([`GpsCommonProperties`](libs/gps-common/src/main/java/com/rls/gps/common/config/GpsCommonProperties.java)):

| Property | Default | Purpose |
|---|---|---|
| `gps.common.gateway.token` | *(empty — check disabled)* | shared secret Kong presents |
| `gps.common.gateway.skip-paths` | `/actuator/**`, `/api/v1/ping`, `/v3/api-docs/**`, `/swagger-ui/**`, `/error` | paths exempt from the check |

---

## Id service — `services/id-service`

Authentication and authorization: companies (tenants), and later users, tokens and the gateway's
authenticate hook. Runs on **two ports** — `8081` public (Kong proxies here) and `9081` internal
(restricted endpoints, never routed by Kong).

### `POST /api/v1/company/signup` — restricted

Registers a tenant. Requires the `sret` header and is served **only on the internal port**.

```bash
curl -i -X POST http://localhost:9081/api/v1/company/signup \
  -H 'sret: local-dev-server-secret' \
  -H 'Content-Type: application/json' \
  -d '{"name":"Acme Logistics","contactEmail":"ops@acme.example"}'
```

```json
{
  "companyId": "0a9f…",
  "name": "Acme Logistics",
  "contactEmail": "ops@acme.example",
  "appKey": "ak_8Fq2…",
  "appSecret": "as_9Xk1…",
  "status": "ACTIVE",
  "createdAt": "2026-09-16T10:12:05.123Z"
}
```

**`appSecret` is shown exactly once.** Only its BCrypt hash is stored, so a lost secret means
re-issuing, never recovering.

| Outcome | Status | `code` |
|---|---|---|
| Registered | 201 | — |
| Missing or wrong `sret` | 403 | `invalid_server_secret` |
| No server secret configured | 503 | `server_secret_not_configured` |
| Name already taken (case-insensitive) | 409 | `company_already_exists` |
| Invalid body | 400 | `validation_failed` |
| Called on the public port | 404 | `not_found` |

### Configuration

| Property | Source | Default |
|---|---|---|
| `gps.id.internal-port` | `INTERNAL_PORT` env | `9081` |
| `gps.id.server-secret` | Consul KV `config/id-service/data` | *(blank → signup refused)* |
| datasource host/port/credentials | `MYSQL_*` env | `localhost:3306`, `gps_id` |

---

## Commit log

### C1.1 — Maven reactor and JDK-pinned build script

Creates the multi-module reactor: parent POM on Java 21 with the Spring Boot 3.5.15 parent and the
Spring Cloud 2025.0.3 BOM, a surefire/failsafe split so unit and integration tests run in different
phases, JaCoCo coverage, and the empty `gps-common` module the next commits fill in.
`scripts/build.sh` exists because Maven here runs on JDK 17 by default.

*Verify:* `./scripts/build.sh` → `BUILD SUCCESS`.

### C1.2 — Original specification and architecture diagram

Adds the source requirements to the repository so the implementation can be checked against them:
the specification document, its PDF original, and the infrastructure diagram (extracted from the
PDF) that the README now shows. Also records the milestone breakdown this build follows.

*Verify:* nothing to run — documentation only.

### C1.3 — Shared identity and header vocabulary

The types every later commit speaks in: `GpsHeaders` (the Kong ↔ service header contract),
`Identity` (company + user + app key, with blank-safe `isComplete()`), and `RequestPaths` (request
path relative to the context path, for filter pattern matching). No Spring wiring yet — these are
plain types with unit tests.

*Verify:* `./scripts/build.sh test` → 6 tests in `gps-common`.

### C1.4 — RFC 7807 error model

Every failure in every service now renders as `application/problem+json` with a stable,
machine-readable `code`, a `timestamp`, and (from C1.5) a `traceId`:

```json
{
  "type": "https://docs.rls.gps/errors/validation_failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "code": "validation_failed",
  "errors": ["count: must be greater than or equal to 1", "name: must not be blank"],
  "timestamp": "2026-09-14T13:22:05Z"
}
```

- `ApiException` / `ApiExceptions` — services throw `ApiExceptions.notFound("company_not_found", …)`
  and the status and code travel with the exception.
- `GlobalExceptionHandler` — extends Spring's `ResponseEntityExceptionHandler`, so framework
  failures (405, 415, malformed JSON) get the same shape. Bean Validation failures list every
  offending field. Unexpected exceptions become a generic `internal_error`; the stack trace goes to
  the log, never to the client.
- `ProblemWriter` — the same rendering for servlet filters, which run outside the advice chain.
- `GpsCommonAutoConfiguration` — registers the above through
  `META-INF/spring/…AutoConfiguration.imports`, so a service gets it by depending on the module.

*Verify:* `./scripts/build.sh test` → `ProblemDetailErrorsTest` (4 tests).

### C1.5 — Correlation ids across every hop

`CorrelationIdFilter` adopts the id Kong sends (`X-Correlation-Id`, falling back to
`X-Request-Id`) or mints a UUID, publishes it to the logging MDC for the whole request, echoes it
on the response, and — because `GlobalExceptionHandler` and `ProblemWriter` read the same MDC key —
stamps it into every error body as `traceId`.

That is what makes a single request traceable across Kong → ping → RabbitMQ → history, and lets a
user quote one id in a bug report.

*Verify:* `./scripts/build.sh test` → `CorrelationIdFilterTest` (3 tests).

### C1.6 — Identity propagation into controllers

`IdentityFilter` turns the gateway's headers into an immutable `Identity` request attribute and
pushes `companyId`/`userId` into the MDC, so every log line for the request says who it was for.
`IdentityArgumentResolver` then injects it straight into controller methods:

```java
@GetMapping("/api/v1/history")
List<Location> history(@CurrentIdentity Identity caller) { ... }
```

A request without a complete identity is rejected with a 401 `identity_required` problem *before*
the controller body runs, so no endpoint can forget the check. `@CurrentIdentity(required = false)`
opts an endpoint into anonymous access.

*Verify:* `./scripts/build.sh test` → `IdentityResolutionTest` (4 tests).

### C1.7 — Shared `GET /api/v1/ping` endpoint

The spec requires this endpoint on all four services, so it ships in the shared library rather than
being copy-pasted four times. It reports the service name (`spring.application.name`), status and
build version, and stays unauthenticated so Consul, Kong and load balancers can probe it:

```json
{ "service": "id-service", "status": "UP", "version": "0.1.0", "timestamp": "2026-09-14T13:22:05Z" }
```

*Verify:* `./scripts/build.sh test` → `PingEndpointTest`.

### C1.8 — Gateway trust boundary

Downstream services trust `X-User-Id` only because Kong verified it. That is safe only if a service
cannot be called directly, so `GatewayTokenFilter` requires a shared secret
(`gps.common.gateway.token`, seeded from Consul KV) that only Kong knows. The comparison is
constant-time, failures render as a 401 `gateway_token_invalid` problem, and health/doc paths
(`gps.common.gateway.skip-paths`) stay open so probes keep working.

The check disables itself when no token is configured, which keeps local runs and tests simple
without weakening a deployed environment.

This completes the shared library. Filter order: correlation id → gateway token → identity, so even
a rejected request is logged with a trace id.

*Verify:* `./scripts/build.sh test` → 22 tests in `gps-common`.

### C2.1 — MySQL with a schema per service

Starts the local infrastructure stack with MySQL 8 — the stand-in for RDS in the original design.
[`01-schemas.sql`](deploy/mysql/init/01-schemas.sql) runs on first boot and creates one schema and
one user per service: `gps_id`, `gps_history`, `gps_metadata`.

Separate schemas mean no service can read another's tables by accident and each keeps its own Flyway
history. (Ping needs no schema — its state lives in Redis and the queue.)

`scripts/up.sh` starts the stack and waits for health checks; `scripts/down.sh` stops it, with `-v`
to discard the data volumes.

*Verify:* `./scripts/up.sh` → `gps-mysql` healthy, then
`docker exec gps-mysql mysql -uroot -prootpw -e "SHOW DATABASES"` lists the three schemas.

### C2.2 — Redis for last-known locations

Redis is where the ping service will keep each user's *last* position plus the geo index that
answers radius queries. Two deliberate settings:

- `--maxmemory-policy noeviction` — the default LRU policy would let Redis silently drop geo
  entries under pressure, which shows up as a user disappearing from a radius result rather than as
  an error. Failing loudly is better.
- `--appendonly yes` — a restart during development shouldn't wipe the cache.

The original spec pinned Redis 5.0.6; this uses Redis 7 so radius queries can use `GEOSEARCH`
(`GEORADIUS`, deprecated in 6.2, is the fallback if you must stay on 5.x).

*Verify:* `./scripts/up.sh redis` → `gps-redis` healthy, `docker exec gps-redis redis-cli ping`
returns `PONG`.

### C2.3 — RabbitMQ for the location pipeline

The queue between ping (publisher) and history (the spec's single consumer). The management UI is
published at <http://localhost:15672> (`gps` / `gps_pw`) so you can watch depth and throughput while
load testing.

**Topology is owned by the services, not by this file.** Exchanges, queues, bindings and
dead-letter arguments are declared by the ping and history services (C7, C9), so the same
declarations apply in Docker Compose and in Testcontainers tests. Splitting them — definitions file
here, `@Bean`s there — is how you end up with a `PRECONDITION_FAILED` on a mismatched argument. This
file only provides the broker and its credentials.

*Verify:* `./scripts/up.sh rabbitmq` → `gps-rabbitmq` healthy;
`docker exec gps-rabbitmq rabbitmq-diagnostics -q ping` reports `Ping succeeded`.

### C2.4 — Consul for configuration and discovery

The last piece of infrastructure. Consul holds service configuration in its KV store and acts as
the service registry; Kong will later resolve upstreams through its DNS on port 8600.

`scripts/seed.sh` pushes every `deploy/consul/kv/<name>.yml` to `config/<name>/data` — exactly where
Spring Cloud Consul Config looks for it (`config/application/data` for shared settings,
`config/<spring.application.name>/data` for per-service overrides). Consul runs in `-dev` mode, so
its KV is in-memory and `up.sh` re-seeds on every start, which also makes the seed idempotent by
construction.

**The split that matters:** infrastructure *endpoints* (database host, broker host) come from
environment variables set by Compose, because they differ between a container and a JVM running on
your machine. *Behaviour and secrets* (gateway token, `sret`, tuning knobs) come from Consul KV, so
one set of values serves both. Mixing the two is how you get a service that works in Docker and
fails on localhost.

This completes the local infrastructure stack:

| Component | Port(s) | Credentials | Notes |
|---|---|---|---|
| MySQL | 3306 | `root`/`rootpw`, per-service users | schemas `gps_id`, `gps_history`, `gps_metadata` |
| Redis | 6379 | — | `noeviction`, AOF on |
| RabbitMQ | 5672, UI 15672 | `gps`/`gps_pw` | topology declared by the services |
| Consul | 8500 (UI), 8600 (DNS) | — | `-dev` mode, KV re-seeded by `up.sh` |

*Verify:* `./scripts/up.sh` → four healthy containers and `Seeded 1 key(s)`; the value is visible at
<http://localhost:8500/ui/dc1/kv/config/application/data> or via
`curl -s localhost:8500/v1/kv/config/application/data?raw`.

### C3.1 — Id service skeleton

The first service. It registers with Consul, reads its configuration from Consul KV, ships a
Dockerfile and a Compose entry, and already answers `GET /api/v1/ping` — inherited from
`gps-common`, not written again here.

Deliberately **no database yet**: a skeleton without JPA boots without any container, so this commit
is fully testable on a machine with no Docker, and the persistence layer arrives next with its own
tests. Consul is configured with `fail-fast: false` and an `optional:` config import for the same
reason — a developer running one service on their laptop shouldn't need the whole stack.

*Verify:* `./scripts/build.sh -pl services/id-service -am test` → `IdServiceApplicationTest` (2 tests:
ping identifies the service, actuator health is UP).

### C3.2 — Companies table and persistence

Adds JPA, Flyway and the `companies` table — the tenant every user, location and metadata row will
hang off.

- **Flyway owns the schema, Hibernate only checks it.** `ddl-auto: validate` means a drift between
  entity and migration fails at startup instead of silently altering a production table.
- `app_secret_hash` stores a BCrypt hash; the plaintext secret never reaches the database.
- The unique index on `name` is case-insensitive by collation (`utf8mb4_0900_ai_ci`), so
  "Acme Logistics" and "acme logistics" cannot both exist — the database enforces it, not just the
  service.

**Integration tests** (`*IT`) now run the real application against a real MySQL via Testcontainers.
They are marked `@Testcontainers(disabledWithoutDocker = true)`, so on a machine with no Docker they
**skip** rather than fail — `mvn verify` stays honest instead of red for an environmental reason.
The datasource is wired with `@DynamicPropertySource` rather than `@ServiceConnection` because the
latter resolves the container image while the test context is built, before JUnit evaluates the skip
condition, which turns a skip into an error.

*Verify:* `./scripts/build.sh -pl services/id-service -am verify` → `CompanyRepositoryIT` (4) and
`IdServicePingIT` (2). With Docker running they pass; without it they report as skipped.

### C3.3 — Restricted endpoints on a separate port

The spec calls `/api/v1/company/signup` and `/api/v1/internal/authenticate` *restricted*: they must
not be reachable through the gateway. Routing rules alone would enforce that by convention — one
careless Kong route and a restricted endpoint is public.

Instead the service opens a **second HTTP connector** (`gps.id.internal-port`, default 9081) and
[`PortAccessFilter`](services/id-service/src/main/java/com/rls/gps/id/web/PortAccessFilter.java)
splits the surface by the port the request arrived on:

| Path | Public port (8081) | Internal port (9081) |
|---|---|---|
| `/api/v1/company/**`, `/api/v1/internal/**` | **404** | served |
| everything else (`/api/v1/user/**`, …) | served | **404** |
| `/api/v1/ping`, `/actuator/**` | served | served |

Kong is only ever pointed at 8081, so a restricted endpoint is unreachable through it even if
someone adds a matching route. Mismatches return **404, not 403** — an endpoint you may not reach
shouldn't confirm that it exists.

*Verify:* `./scripts/build.sh -pl services/id-service -am verify` → `PortAccessFilterTest` (5 tests,
no Docker needed).

### C3.4 — The `sret` server-secret guard

The spec requires every company-registration request to carry an `sret` header.
[`ServerSecretGuard`](services/id-service/src/main/java/com/rls/gps/id/security/ServerSecretGuard.java)
checks it against `gps.id.server-secret`, seeded into Consul KV at `config/id-service/data`.

Two decisions worth stating:

- **Constant-time comparison** (`MessageDigest.isEqual`). String equality returns as soon as it hits
  a differing byte, which leaks the length of the matching prefix to anyone who can time requests.
- **Fails closed.** If no secret is configured the endpoint rejects *everything* with 503 and the
  service logs an error at startup. The tempting alternative — "no secret configured, so skip the
  check" — turns a missing config value into an open registration endpoint.

*Verify:* `./scripts/build.sh -pl services/id-service -am verify` → `ServerSecretGuardTest` (4 tests).

### C3.5 — App key and secret generation

[`CredentialGenerator`](services/id-service/src/main/java/com/rls/gps/id/security/CredentialGenerator.java)
mints the credential pair a company uses to register its users: `ak_…` (18 random bytes) as the
public identifier, `as_…` (32 random bytes) as the secret — `SecureRandom`, base64url, no padding.

The prefixes are not decoration: they make a leaked credential identifiable at a glance in a log or
a support ticket, and they make "you pasted the key where the secret goes" a one-look diagnosis.
Secret length is capped below BCrypt's 72-byte truncation limit, so the whole secret is actually
hashed.

*Verify:* `./scripts/build.sh -pl services/id-service -am test` → `CredentialGeneratorTest` (3 tests,
including 1000 generated values with no collisions).

### C3.6 — `POST /api/v1/company/signup`

Ties the milestone together: the restricted endpoint validates its body, passes the `sret` header to
the guard, generates the credential pair, stores the company with a BCrypt-hashed secret, and
returns the secret once.

Two details worth calling out:

- **The duplicate-name check is belt *and* braces.** `existsByNameIgnoreCase` gives a clean 409, and
  the unique index still catches the race where two requests pass that check simultaneously — the
  `DataIntegrityViolationException` is translated into the same 409 rather than a 500.
- **BCrypt cost 12** rather than the default 10. Company secrets are verified rarely, so the extra
  cost lands where it's affordable and hurts an offline attacker.

Completes the company-registration milestone: seven integration tests cover the happy path, both
`sret` failures, duplicates, validation, key uniqueness, and the 404 on the public port.

*Verify:* `./scripts/build.sh -pl services/id-service -am verify` → 12 unit tests pass; 13 integration
tests run with Docker (currently reported as skipped).

### C4.1 — Users table, scoped to a company

`V2__users.sql` plus the `User` entity and repository.

- **Usernames are unique per company, not globally** (`UNIQUE (company_id, username)`). Two customers
  must both be able to have a `driver-1` without discovering each other's existence — a global
  unique index would leak that and cause support tickets nobody can fix.
- **A foreign key to `companies`**, so a user cannot outlive its tenant.
- **Every finder is company-scoped.** There is deliberately no `findById(userId)` in use: the
  repository exposes `findByIdAndCompanyId`, so a bug that loses the tenant filter fails to compile
  rather than quietly returning another customer's user.
- The company is stored as an id, not a JPA association — they are separate aggregates, and the id
  is exactly what the platform passes around in headers.

Test cleanup moved into `AbstractIdServiceIT`: users are deleted before companies, since the reverse
order trips the new foreign key.

*Verify:* `./scripts/build.sh -pl services/id-service -am verify` → `UserRepositoryIT` (5 tests, with
Docker).

### C4.2 — `POST /api/v1/user/signup`

The first public endpoint. It cannot require a user token — there is no user yet — so it
authenticates with the **company's** app key and secret, which Kong will exempt from `rls_auth`.

```bash
curl -i -X POST http://localhost:8081/api/v1/user/signup \
  -H 'Content-Type: application/json' \
  -d '{"appKey":"ak_…","appSecret":"as_…","username":"driver-1","password":"s3cret-password","displayName":"Driver One"}'
```

- **Credentials go in the body, not in headers.** `X-App-Key` is an identity header the gateway owns
  and overwrites, so a client cannot use it to present credentials without creating an ambiguity
  about who set it.
- **An unknown app key and a wrong secret return the identical 401**, and the unknown-key path still
  performs a BCrypt comparison against a decoy hash. Otherwise the status code or the response time
  would tell an attacker which app keys exist.
- Duplicate usernames are caught by the check *and* the unique index, like company names.

| Outcome | Status | `code` |
|---|---|---|
| Created | 201 | — |
| Unknown app key or wrong secret | 401 | `invalid_company_credentials` |
| Company suspended | 403 | `company_suspended` |
| Username taken in that company | 409 | `user_already_exists` |
| Invalid body | 400 | `validation_failed` |

*Verify:* `./scripts/build.sh -pl services/id-service -am verify` → `UserSignupIT` (8 tests, with Docker).

### C4.3 — Access tokens (issue and verify)

[`TokenService`](services/id-service/src/main/java/com/rls/gps/id/token/TokenService.java) mints and
checks the JWTs clients present to the gateway. HS256 via JJWT 0.12.7 (pinned in the parent POM — the
Spring Boot BOM manages neither JJWT nor Nimbus).

| Claim | Meaning |
|---|---|
| `sub` | user id |
| `cid` | company id |
| `ak` | app key |
| `iss` | `rls-id-service`, required on verification |
| `iat` / `exp` / `jti` | issued-at, expiry (default 1 h), unique token id |

- **HS256, not RS256.** The Id service is both the only issuer and the only verifier — the gateway
  asks it rather than checking signatures itself — so a shared secret avoids handing every component
  a key to fetch and rotate.
- **Claims stay minimal.** Anything richer (roles, company name) can go stale between issue and use.
- **Unsigned tokens cannot slip through.** `parseSignedClaims` rejects `alg: none` outright, and
  there's a test that forges exactly that.
- **Verification takes an injected clock**, so expiry is tested by moving time rather than sleeping.
- **No default signing key.** If `gps.id.jwt.secret` is unset the service generates a random one and
  logs a loud warning; a shipped default key would let anyone mint valid tokens. A configured secret
  shorter than 32 bytes fails startup instead of silently weakening HS256.

*Verify:* `./scripts/build.sh -pl services/id-service -am test` → `TokenServiceTest` (9 tests: round
trip, expiry either side of the boundary, foreign key, tampered payload, `alg: none`, wrong issuer,
garbage input).

<!-- next-commit-log-entry -->
