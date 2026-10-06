# STATUS

_Updated: 2026-10-06 (gap 2.1 decided: DL-026; gap 1.3 implemented: system-owned payment closure; gap 1.4 decided: DL-025)_

## Latest: gap 2.1 decided (branch `bugfix/phase-2-gap-diagnostic-tool`, docs only)
* Decision record: `docs/decision-log.md` DL-026. Detection **and** remediation live on a fourth, ops-only
  MCP server (MCP#4) over `LandscapeDiagnostics`; MCP#1-#3 carry customer tools only; the customer persona
  never connects to MCP#4; `ops-console` is unchanged. The diagnosis is not a measured tokenomics
  transaction (decided with the user), which removed the reason for leaving the join to the agent.
* Found on the way, all recorded in DL-026: the design's placement on MCP#2/#3 cannot see branch A's
  catalog half (`SUB-2026-000009`); the orphan remedy "terminate in the catalog" has no tool (catalog REST
  `terminate` exists, `subsystem-clients` has no wrapper); the DL-025 unpaid-order rule has no bulk source in
  billing or activation. **No code changed**; `mvn verify` was not run because nothing buildable changed.
* Revisit trigger: if the diagnosis is ever put into the tokenomics measurement, reopen DL-026.
* Still open: 2.2 (tool count and granularity). Doc drift noticed and not fixed here: `phase2-seams.md` §1
  still says the shared layer lacks `POST /orders` and a subscriber wrapper (gaps 1.1 and 1.2 closed that),
  and §3 omits `payInvoice` from the customer-facing billing operations.

## Earlier: gap 1.3 — browserless `payInvoice` (branch `bugfix/phase-2-gap-payment-closure`)
* New billing SOAP operation `payInvoice` (both XSD copies updated together): confirms the
  PaymentIntent at Stripe, makes the same `OPEN` -> `SETTLEMENT_PENDING` transition as the webhook
  (one shared method in `PaymentService`), cuts the settlement batch. Returns at `SETTLEMENT_PENDING`
  with a `batchId`; `PAID` still needs the clearing house ack. `startPayment` and `/webhook/stripe`
  are unchanged. `BillingSoapClient.payInvoice` exposes it. See DL-024.
* Compose now runs `stripe-sim` (stateful); the official `stripe-mock` is the opt-in profile
  `official-stripe-mock`. The Stripe SDK is configured per request, and PaymentIntents use
  `automatic_payment_methods` because `stripe-mock:latest` had dropped `payment_method_types`
  (that broke `PaymentFlowIT`/`BatchSettlementIT` on a clean checkout).
* `scripts/demo.sh` no longer confirms at Stripe or posts a webhook; failure branch B uses `payInvoice`
  with the clearing house silenced.
* `mvn verify` green twice in a row: billing 34 ITs (6 new `PayInvoiceIT`), subsystem-clients 47 tests
  (3 new), activation 16 ITs. The activation drain helper now also retries a PostgreSQL deadlock
  (pre-existing intermittent: seen on the untouched baseline too).
* Verified by running it: `docker compose up --build` (all containers healthy, including the new
  `stripe-sim`), `scripts/demo.sh` full run 21+ checks exit 0, and the `happy`, `batch`, `stuck`
  submodes each exit 0 on a fresh stack. A raw `curl` SOAP `payInvoice` went `SETTLEMENT_PENDING` ->
  `PAID` in ~2s with no webhook in billing's log; a repeat call returned `changed=false`; an unknown
  invoice gave a `NOT_FOUND` fault.
* Gap 1.4 was decided afterwards (DL-025, see below). Still open: phase-2 section 2 decisions.


## Where we are
**Phase 1 is complete.** All fourteen tasks done, everything verified by actually running it.

* `mvn clean verify` — **BUILD SUCCESS, 145 tests**, no warnings.
* `docker compose down -v && docker compose up --build` from a clean slate — all six containers
  healthy.
* `scripts/demo.sh` — **21 checks, exit 0**, against the Docker stack *and* against the non-Docker
  `scripts/run-local.sh` stack. Same script, same checks, either way.
* The four read-only source documents are untouched since the user's own commits.
* Every relative link in every Markdown file resolves.
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
| T10 Docker compose | done |
| T11 non-Docker local path | done |
| T12 demo script | done |
| T13 docs | done |
| T14 CLAUDE.md + final pass | done |

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

* Docker verified from a clean slate (`docker compose down -v && docker compose up --build`): all six
  containers healthy, then the full demo green — catalogue browse, subscription in ~4s, add-on growing
  the allowance to 56320 MB, SOAP invoice query showing the 5990.01 artefact, Stripe payment,
  fixed-width settlement file, clearing-house ACK, invoice `PAID`, then both failure branches
  triggered, diagnosed through the ops console and repaired.

## Findings from actually running it under Docker (each is now a decision-log entry)
* **DL-020** The image build needs to trust the TLS-terminating proxy's CA, or Maven Central is
  unreachable from the build container (`PKIX path building failed`). `docker/ca/` is the opt-in hook;
  in this container run `cp /root/.ccr/ca-bundle.crt docker/ca/` first. Related: builds use
  `network: host` (the proxy is on 127.0.0.1) and the runtime images install nothing, because
  `apt-get` through such a proxy fails with "the repository is not signed".
* **DL-021** Health checks therefore speak HTTP through bash's `/dev/tcp`, with `CMD` not `CMD-SHELL`
  (the image's `/bin/sh` is dash).
* **DL-022** The `batch-exchange` directories must exist **in the image**, owned by the runtime user.
  Docker seeds an empty named volume from the image including ownership, so without them the volume is
  root-owned and `exportPaymentBatch` returns 502 "could not write settlement file". No test caught
  this — the tests use a temp directory the test JVM owns. The clearest example in this project of why
  green tests are not verification.
* **DL-019** Order numbers contain slashes, so they are query parameters and body fields, never path
  variables.
* Docker Hub rate-limits (429) on `load metadata`; pull base images separately with a retry first.
* The Docker daemon died once mid-build; restart with `nohup dockerd &`.

* Non-Docker path verified too. PostgreSQL 16 is installed in this container but not running:
  `pg_ctlcluster 16 main start`, then `su postgres -c "PGHOST=/var/run/postgresql
  scripts/create-local-databases.sh"`, then `scripts/run-local.sh`. The same
  `scripts/demo.sh` passed all 21 checks against it, and `scripts/stop-local.sh` stopped all five
  JVMs cleanly.

## Half-done
Nothing.

## Phase-2 alignment review (2026-09-30)
The implementation was reviewed against the user's full phase-2 design. Gaps are recorded in
`docs/phase2-gaps.md`: the shared client layer cannot place an order or create a subscriber, the
payment loop only closes because `demo.sh` posts the webhook by hand, and two decisions (invoice
timing vs. UCP checkout, where the diagnostic tool lives) are open. The doc drift is corrected:
`phase2-seams.md` §0 now records the user's full phase-2 design, and it and `architecture.md` no
longer claim a shared `CatalogRestClient`. The code gaps are not fixed.

## Phase-2 gap implementation start (2026-10-05)
Implemented the first two code gaps from `docs/phase2-gaps.md` in `subsystem-clients`:

* `ActivationRestClient` now supports `POST /activation/v1/orders` through `startOrder` and typed
  helpers (`startNewSubscription`, `startPlanChange`, `startAddon`), covering services 2 and 4.
* Added `CatalogSubscriberRestClient` to wrap `POST /api/v1/subscribers`, so a new customer can be
  onboarded through the shared layer before activation order placement.
* Added focused unit tests: `ActivationRestClientTest` and `CatalogSubscriberRestClientTest`.

## Gap 1.4 decided (2026-10-06, docs only)
Decision record: `docs/decision-log.md` DL-025. Keep activate-then-bill; the UCP checkout is held
`complete_in_progress` until the order is provisioned and the invoice is `SETTLEMENT_PENDING`, then
`completed`. Checkout quotes the billing-derived total. UCP and chat share one lifecycle (UCP charges
automatically, chat pays explicitly, both via `payInvoice`). A declined charge cancels the checkout and
goes to ops; no automatic compensation. **No code changed.**

Merged with the 1.3 work, so DL-024 and `payInvoice` are in the same tree as DL-025. Needed later, all
additive: an optional payment-method element on `payInvoice` (both XSD copies), a
distinguishable decline fault in billing, a fault-injectable decline in `stripe-sim`, an ops diagnostic
rule for provisioned-but-unpaid orders.

## Next action
Gaps 1.1-1.4 and 2.1 are closed (1.4 and 2.1 by decision only; DL-026 is merged into
`bugfix/phase-2-gaps`). The remaining work, in order, with the phase each item belongs to:

1. **Add the catalog terminate wrapper to `subsystem-clients`** (DL-026, criterion 5). A leftover
   phase-1 omission: the ops console recommends "terminate in the catalog" and nothing can do it. Small,
   and independent of step 2, so it can be done first.
2. **Decide tool count and granularity** (`docs/phase2-gaps.md` 2.2). The first phase-2 task, and it comes
   *before any MCP tool surface is defined*. Run it as research-then-interview with the user: research
   industry guidance and example agent architectures from primary sources (pin versions and dates), ask
   one question at a time, record the answer as the next free DL entry. A proposed starting point is
   written in 2.2; the user has not confirmed it, so do not build on it silently.
3. **Build the ops MCP server (MCP#4)**, phase 2, with the tool surface from step 2.
4. **Deferred to phase 3: the DL-025 unpaid-order rule** and its bulk billing query. It detects a declined
   UCP charge, so it waits for the UCP adapter.

If you are a fresh session picking this up, the useful entry points are:

* `CLAUDE.md` — conventions, the do-not-modify list, and the seven things that look like bugs and are
  the subject matter.
* `docs/semantic-mismatches.md` — the centrepiece: every data-model disagreement and the test pinning it.
* `docs/decision-log.md` — 26 entries; read DL-006, DL-009, DL-014 and DL-022 first.
* `docs/phase2-seams.md` — where MCP, the agents and UCP attach, and what they must not "fix".
* `docs/phase2-gaps.md` — what the seams doc gets wrong and what phase 2 is still missing.

Phase 2 (MCP servers, agents, tokenomics) and phase 3 (Google UCP) are **not** in this repository and
were never in scope for this task.

## Branch note
Work happens on `feat/legacy-system` (as requested). The harness-designated branch
`claude/busy-cray-97xi8a` is kept fast-forwarded to the same head so both instructions hold.
