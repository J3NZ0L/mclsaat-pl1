# STATUS

_Updated: 2026-09-29 (T08, T09)_

## Where we are
**T09 done** — all three subsystems, the canonical client layer and the ops console are implemented
and verified running together. 145 tests green.
* `catalog-service`: 17 integration tests green; REST and direct psql both read the same rows.
* `billing-service`: 43 tests green (15 unit + 28 integration over a real HTTP port, with the
  Stripe leg going through the real SDK to a `stripe/stripe-mock` Testcontainer and the batch leg
  writing real files to a real temp directory). All seven SOAP operations are implemented.
* Verified by hand against the running jars: the happy settlement path (export -> fixed-width
  file in the outbox -> clearing house ACK -> background poller -> invoice `PAID`, batch `ACKED`,
  ACK archived) **and** failure branch B (clearing house silenced -> batch strands in `SENT` with
  its invoice in `SETTLEMENT_PENDING` -> `listUnconfirmedBatches` reports it -> `reconcileBatch`
  with `RE_DRIVE_ACK` rebuilds the ACK from the sent file -> invoice `PAID`).
* `activation-service`: 31 tests green (15 unit + 16 Flowable integration on real PostgreSQL with a
  live job executor). Verified by hand with **all four services running together**:
  * happy path: `POST /orders` -> `RECEIVED` -> `AWAITING_PROVISIONING` -> callback ->
    `PROVISIONED` in ~4s, with every translation landing correctly — `customerRef 43` ->
    catalog `00000043` -> billing `BA-00043`; `mob.voice.0050` -> `MOB-VOICE-0050`;
    `+36209876543` -> `36209876543`; 50.000 GB -> 51200 MB; `20260929` -> `2026-09-29`;
    gross 9990.00 -> net 7866.14 + VAT 2123.86.
  * add-on variant: base subscription's effective allowance went 51200 -> 56320 MB.
  * failure branch A: `simulateStuck` order went `STUCK` with the wait intact, catalog left in `PA`,
    ops `stuck-orders` reported it as `repairable=true`, `force-provision` finished it normally with
    the hand-supplied ICCID, catalog went `AC`, invoice issued.
* `stripe-sim` is a verified drop-in for `stripe/stripe-mock`: the same `startPayment` SOAP call
  through the same Stripe SDK returned `pi_sim_00000000001` / 1299000 minor units, `confirm`
  reported `succeeded`, and the webhook moved `2026/INV/000002` to `SETTLEMENT_PENDING`.

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
| T04 billing-service (schema + SOAP) | done |
| T05 stripe-sim + payment start | done |
| T06 billing batch/EDI + ops SOAP ops | done |
| T07 activation-service (Flowable) | done |
| T08 subsystem-clients + mappers | done |
| T09 ops-console | done |
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

## Findings worth keeping
* The gross/net VAT seam produces a **real, measurable rounding artefact**. Of the nine seeded
  plan prices, seven survive gross -> net -> gross unchanged, `MOB-VOICE-0010` gains one fillér
  (5990.00 -> 5990.01) and `INET-FIB-1000` loses one (17990.00 -> 17989.99). This is pinned by
  `VatCalculatorTest.theRoundTripErrorAcrossTheWholeSeededCatalogIsExactlyThis` and is the
  headline example for `docs/semantic-mismatches.md`. Do not "fix" it without updating both.
* The billing SOAP contract already declares all seven operations (`startPayment`,
  `exportPaymentBatch`, `listUnconfirmedBatches`, `getPaymentBatch`, `reconcileBatch` are in the
  XSD/WSDL but not yet implemented in `BillingEndpoint`); T05 and T06 fill them in.
* `nillable="true"` on an `xs:int` makes xjc generate `JAXBElement<Integer>`; use plain
  `minOccurs="0"` for an optional int.
* **stripe-mock validates the shape of the API key**: it must be alphanumeric after the
  `sk_test_` prefix. `sk_test_mclsaat_local` was rejected with an `AuthenticationException`;
  `sk_test_mclsaat123` works. The default in `application.yml` is already correct.
* `pkill -f <pattern>` inside a Bash tool call can match the call's **own** command line (the
  pattern usually appears in the script text) and kill the shell, which surfaces as exit 144.
  Use `scratchpad/stopjars.sh`, which requires `comm == "java"` so the calling shell cannot match.
* The fixed-width settlement format is pinned by offset in `BatchFileFormatTest`; the records are
  HDR 61 / DTL 85 / TRL 24 bytes outbound and ACK 57 / RES 35 inbound. Money in the file is an
  implied-two-decimals integer, which is the **fourth** representation of the same figure
  (catalog fillér BIGINT -> activation decimal HUF -> billing NUMERIC(12,2) -> Stripe minor
  units / batch-file implied decimals).
* `stripe-sim` ids carry a random suffix. Without one a restart resets the counter and reissues an
  id an older invoice already holds, which breaks billing's lookup-by-payment-intent.
* **Order numbers contain slashes**, so they cannot be path variables: Tomcat rejects an encoded
  `%2F` by default and decoding it splits the order number into three path segments. Activation
  therefore takes `orderNo` as a query parameter (`GET /activation/v1/orders?orderNo=...`) and in the
  body for the ops operations.
* `ExecutionQuery.processInstanceBusinessKey(...)` only matches the process instance's **own**
  execution row. The message subscription sits on a child execution inside the sub-process, so
  looking it up by business key silently reports that nothing is waiting. Resolve the instance first,
  then query by `processInstanceId`.
* An unhandled `BpmnError` thrown from an async service task **rolls the transaction back**, taking
  any status the delegate wrote with it. The rejection path therefore has an error boundary event and
  a separate `markRejected` delegate.
* Flowable polls for due timer jobs and sleeps ~10s between polls by default, so a `PT5S` timer fired
  at t+13s. `flowable.process.async.executor.default-timer-job-acquire-wait-time: PT1S` brings it to
  ~t+6s, which a two-minute demo needs.
* The Flowable integration tests use `DEFINED_PORT` (18082), not `RANDOM_PORT`, because the simulated
  provisioning platform calls back over real HTTP and needs a URL before the context starts. They
  also drain leftover process instances between tests, retrying on optimistic-locking collisions,
  because the shared job executor otherwise invokes mocks in the middle of the next test.

* `subsystem-clients`: 41 tests green (canonical value types, semantic mappers, and the
  cross-subsystem correlation in `LandscapeDiagnostics` with the clients mocked).
* `ops-console`: 13 tests green (`@WebMvcTest` over both controllers and the exception advice).
* Verified by hand with **all five processes running**: `/ops/v1/diagnostics/overview` reported all
  four interface styles reachable and found 3 stuck activations + 1 unconfirmed settlement.
  `stuck-activations` correctly distinguished two repairable `STUCK_PROCESS` findings from the seeded
  `ORPHANED_PENDING_SUBSCRIPTION` (`SUB-2026-000009`, no order behind it).
  `force-provision` through the console drove a stuck order to `PROVISIONED` with an invoice.
  `unconfirmed-batches` printed the fixed-width settlement records verbatim and correctly separated
  the repairable batch from the seeded one whose file was never written — and `reconcile` on the
  latter came back as HTTP 409 `BILLING_ILLEGAL_STATE`, which is the honest answer.

## Half-done
Nothing.

## Next action
T10: Docker — per-module Dockerfiles and a `docker-compose.yml` (postgres with three databases,
stripe-mock, the four services, a shared batch-exchange volume), verified by actually running
`docker compose up`.

## Branch note
Work happens on `feat/legacy-system` (as requested). The harness-designated branch
`claude/busy-cray-97xi8a` is kept fast-forwarded to the same head so both instructions hold.
