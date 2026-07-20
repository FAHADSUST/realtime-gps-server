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

## Commit log

### C1.1 — Maven reactor and JDK-pinned build script

Creates the multi-module reactor: parent POM on Java 21 with the Spring Boot 3.5.15 parent and the
Spring Cloud 2025.0.3 BOM, a surefire/failsafe split so unit and integration tests run in different
phases, JaCoCo coverage, and the empty `gps-common` module the next commits fill in.
`scripts/build.sh` exists because Maven here runs on JDK 17 by default.

*Verify:* `./scripts/build.sh` → `BUILD SUCCESS`.
