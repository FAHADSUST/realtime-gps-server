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
├── pom.xml              # parent POM: Java 21, Spring Boot/Cloud BOMs, surefire/failsafe/jacoco
├── libs/gps-common/     # shared library every service depends on
└── scripts/build.sh     # builds with JDK 21 regardless of Maven's default JDK
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
| C2 | Local infrastructure (MySQL, Redis, RabbitMQ, Consul) | C2.1 – C2.4 | ⬜ planned |
| C3 | Id service — company signup | C3.1 – C3.6 | ⬜ planned |
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

<!-- next-commit-log-entry -->
