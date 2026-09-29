# STATUS

_Updated: 2026-09-29 (T03)_

## Where we are
**T03 done** — subsystem 1 (`catalog-service`) is implemented, tested (17 integration tests green) and verified running for real against a PostgreSQL container.

## Environment facts verified this session
* Java 21.0.10, Maven 3.9.11, Docker 29.3.1 + compose v5.1.1, psql client 16.13.
* The Docker daemon is **not running at container start** — start it with
  `nohup dockerd > /tmp/dockerd.log 2>&1 &` and wait ~8s.
* Maven Central reachable. Docker Hub reachable; already pulled:
  `postgres:16-alpine`, `stripe/stripe-mock:latest`, `eclipse-temurin:21-jre-jammy`.
* No Conductor/crewmarshal plugin is installed (`ListPlugins` -> empty), so the workflow
  discipline is followed manually: plan -> task -> implement -> verify by running -> commit/push.
* Sonatype `repo1.maven.org` rate-limits raw `curl` metadata queries; let Maven resolve instead.

## Task board (see docs/superpowers/PLAN.md section 4)
| Task | State |
| --- | --- |
| T01 plan, branch, draft PR | done |
| T02 Maven skeleton | done |
| T03 catalog-service | done |
| T04 billing-service (schema + SOAP) | todo |
| T05 stripe-sim + payment start | todo |
| T06 billing batch/EDI + ops SOAP ops | todo |
| T07 activation-service (Flowable) | todo |
| T08 subsystem-clients + mappers | todo |
| T09 ops-console | todo |
| T10 Docker compose | todo |
| T11 non-Docker local path | todo |
| T12 demo script | todo |
| T13 docs | todo |
| T14 CLAUDE.md + final pass | todo |

## Stack decisions validated by actually running them
* Spring Boot **3.5.6**, Java 21, Maven reactor with 6 modules.
* Flowable **7.2.0** (`flowable-spring-boot-starter-process`) works with Spring Boot 3.5.6 —
  smoke-tested a process with a waiting `intermediateCatchEvent` + `messageEventReceived`
  correlation on H2 and it completed. This is the async/correlation mechanism subsystem 2 needs.
* `com.stripe:stripe-java` **29.4.0**, Testcontainers **1.21.3** resolve from Maven Central.
* Two build traps, both already fixed in the parent POM - do not undo them:
  1. `spring-boot-maven-plugin:repackage` must use `<classifier>app</classifier>`. Without it the
     fat jar replaces the module's main artifact and Failsafe puts it on the test classpath, where
     the classes sit under `BOOT-INF/classes` and are invisible (`NoClassDefFoundError` at test
     discovery). The runnable jar is therefore `target/<module>-1.0.0-SNAPSHOT-app.jar`.
  2. Testcontainers' bundled docker-java negotiates Docker Engine API **1.32**, which Docker 25+
     refuses ("client version 1.32 is too old"). Failsafe passes
     `-Dapi.version=${docker.api.version}` (default `1.44`) to fix it.
* Tests split by name: `*Test` = fast unit tests (`mvn test`), `*IT` = integration tests needing
  Docker/Postgres/Flowable (`mvn verify`, via Failsafe). **Use `mvn verify`, not `mvn test`.**

## Half-done
Nothing. The module POMs currently carry only the dependencies needed so far; each task adds its own.

## Next action
T04: `billing-service` part 1 — Flyway schema (`billing_account`, `invoice`, `payment_batch`),
seed data, contract-first XSD/WSDL plus the Spring-WS endpoint for `GetInvoices` / `CreateInvoice`.

## Branch note
Work happens on `feat/legacy-system` (as requested). The harness-designated branch
`claude/busy-cray-97xi8a` is kept fast-forwarded to the same head so both instructions hold.
