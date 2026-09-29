# Plan — Legacy ISP/Telco Enterprise System (Phase 1)

Base for the modernization experiment in `PROJECT_DESC_HUN.md`. This phase builds the
**legacy system only**. MCP servers, agents, Google UCP and tokenomics measurement are
later phases and are explicitly out of scope here — but every seam they will need is
designed in (see "Phase-2 seams").

## 0. Non-negotiables (from the source docs)

| Requirement | Where it is satisfied |
| --- | --- |
| 3 heterogeneous subsystems | `catalog-service`, `activation-service`, `billing-service` |
| >= 1 asynchronous interaction model | `activation-service`: Flowable embedded, async job executor, message correlation, boundary timers |
| >= 1 semantically different data model | all three differ: ID spaces, units (MB/GB), money representation, enum vocabularies, date formats |
| >= 3 kinds of API | (1) REST/JSON, (2) SOAP/WSDL, (3) batch fixed-width EDI-style file exchange, (4) direct JDBC |
| Embedded process engine (library, not a deployment) | Flowable 7 as a Spring Boot library inside `activation-service` |
| ~4 core tables | 4 core + 2 supporting — see decision log `DL-006` |
| 1 happy path + 1 deliberate failure branch | happy path = start/activate subscription; failure branch = stuck activation **and** unconfirmed billing batch (two faces of the same ops story) |
| Stripe simulated, real key swappable | `stripe/stripe-mock` in Docker; `stripe-sim` module for the non-Docker path; Stripe Java SDK in both cases |

## 1. Target architecture

```
                     ops-console (8080)                     <-- internal ops + demo driver
                     │   uses subsystem-clients
                     │   (canonical model + semantic mappers)
         ┌───────────┼────────────────────────┬──────────────────────┐
         │ JDBC      │ REST                   │ SOAP                 │ files
         v           v                        v                      v
  catalog-service   activation-service   billing-service      batch outbox/inbox
     (8081)             (8082)               (8083)             (shared volume)
  REST + JDBC       Flowable embedded    SOAP + batch + Stripe
  catalogdb         flowabledb           billingdb                    │
                         │  REST                  │ HTTP (Stripe SDK) │
                         └──> catalog             └──> stripe-mock / stripe-sim (12111)
                         │  SOAP
                         └──> billing
                    network-sim (inside activation-service, mocked external SIM/line provisioning)
```

### Subsystem 1 — Catalog & Subscription Registry (`catalog-service`, port 8081)
* APIs: **REST/JSON** (service-to-service + customer browse) and **direct JDBC** (used by `ops-console`).
* DB: `catalogdb`, schema `catalog`.
* Legacy semantics: zero-padded `CHAR(8)` customer numbers, 2-char status codes, 1-char
  service kind, `Y`/`N` flags, data allowance in **MB**, money in **HUF minor units (fillér) as BIGINT**.

### Subsystem 2 — Activation Process (`activation-service`, port 8082)
* APIs: **REST/JSON** + **asynchronous message correlation** (callback endpoint that correlates
  into a waiting process instance).
* Engine: **Flowable 7 embedded** (`flowable-spring-boot-starter-process`), async job executor on.
* DB: `flowabledb` (Flowable engine tables + schema `activation` for its own order table).
* Legacy semantics: order numbers `ORD/2026/0000001`, plan keys as dotted lowercase
  `offerId` (`inet.fib.0500`), data allowance in **GB as decimal**, money in **HUF major units
  as decimal**, dates as `yyyyMMdd` strings, customer ref as **plain integer**, its own
  status vocabulary.
* One BPMN process, three variants via `changeType`: `NEW_SUBSCRIPTION`, `PLAN_CHANGE`, `ADDON`.

### Subsystem 3 — Billing & Payment (`billing-service`, port 8083)
* APIs: **SOAP 1.1 / contract-first WSDL** (invoice query, invoice creation, payment start,
  ops batch operations) + **batch fixed-width EDI-style file exchange** (payment settlement
  outbox / acknowledgement inbox) + **Stripe REST** via the official Stripe Java SDK.
* DB: `billingdb`, schema `billing`.
* Legacy semantics: own ID space (`BA-00042`, `2026/INV/000001`), `customer_ref` as a plain
  integer, money as **NUMERIC(12,2) major units**, 27% VAT computed here, Stripe amounts back
  in **integer minor units**.

### Supporting modules
* `subsystem-clients` — library: `CatalogJdbcClient`, `CatalogRestClient`, `ActivationRestClient`,
  `BillingSoapClient`, `BatchFileClient`, a canonical model (`Money`, `DataVolume`,
  `CanonicalPlan`, …) and `SemanticMappers`. **This is the phase-2 seam**: MCP servers and the
  UCP adapter will sit on exactly this layer.
* `ops-console` — internal, ops-only REST API: detect and resolve both failure branches;
  also the driver used by the demo script.
* `stripe-sim` — minimal Stripe-API-shaped simulator for the non-Docker path.

## 2. The six services

| # | Service | Subsystem | Protocol |
| - | --- | --- | --- |
| 1 | Browse plans / tariffs | catalog | REST `GET /api/v1/plans` (+ JDBC view via ops) |
| 2 | Start subscription (activate) — happy path | activation | REST `POST /activation/v1/orders` |
| 3 | Poll activation/order status | activation | REST `GET /activation/v1/orders/{orderNo}` |
| 4 | Plan change / add-on (data pack, roaming) | activation | same endpoint, `changeType` variant |
| 5 | Invoice query + start payment | billing | SOAP `GetInvoices`, `StartPayment` (+ Stripe) |
| 6 | Detect + resolve stuck activation / unconfirmed batch | ops | REST `/ops/v1/**` (internal only) |

## 3. Deliberate failure branches

**A. Stuck activation.** The mocked external SIM/line provisioning system can be told not to
call back (`"simulateProvisioningTimeout": true`, or the seeded stuck order). The Flowable
receive task's boundary timer fires, the order goes to `STUCK`, and the catalog subscription
stays in `PA` (pending activation) — an inconsistency spanning two subsystems.
*Detection:* `GET /ops/v1/diagnostics/stuck-activations` — correlates Flowable's waiting
instances (REST) with catalog's stale `PA` rows (direct JDBC).
*Resolution:* `POST /ops/v1/remediation/activation/{orderNo}/force-provision` — injects the
missing correlated message (with a manually supplied ICCID) so the process completes normally;
or `.../cancel` to roll the subscription back to `TE`.

**B. Unconfirmed billing batch.** Billing writes a fixed-width settlement batch to the outbox
and waits for the clearing house acknowledgement file. When acknowledgement is suppressed, the
batch stays `SENT` and its invoices stay `SETTLEMENT_PENDING` — money taken by Stripe but never
posted.
*Detection:* `GET /ops/v1/diagnostics/unconfirmed-batches` (over SOAP + reading the outbox files).
*Resolution:* `POST /ops/v1/remediation/billing/batches/{batchId}/reconcile` — re-drives the
acknowledgement from the outbox file so the invoices become `PAID`.

## 4. Task breakdown

Each task ends with: build green, behaviour verified by actually running it, `STATUS.md` updated,
commit + push.

* **T01** Plan + STATUS + branch + draft PR. *(this task)*
* **T02** Maven multi-module skeleton, parent POM, version pinning, `mvn -q verify` on empty modules.
* **T03** `catalog-service`: Flyway schema (`plan`, `subscriber`, `subscription`), seed data,
  JDBC-flavoured repositories, REST API, tests.
* **T04** `billing-service` part 1: Flyway schema (`billing_account`, `invoice`, `payment_batch`),
  seed data, contract-first XSD/WSDL + Spring-WS endpoint for `GetInvoices` / `CreateInvoice`, tests.
* **T05** `stripe-sim` module + `StripeGateway` in billing (`StartPayment`, payment-succeeded
  callback), wired to stripe-mock/stripe-sim, tests.
* **T06** `billing-service` part 2: batch outbox writer (fixed-width), acknowledgement inbox
  poller, clearing-house simulator with suppressible ACK, ops SOAP operations, tests.
* **T07** `activation-service`: Flowable embedded, BPMN process (+ variants), delegates calling
  catalog REST and billing SOAP, order table, REST API, message correlation endpoint, mocked
  provisioning system, boundary timer -> `STUCK`, process tests.
* **T08** `subsystem-clients`: canonical model + semantic mappers + the four clients; mapper unit
  tests (the mismatch table is executable here).
* **T09** `ops-console`: diagnostics + remediation endpoints for both failure branches, tests.
* **T10** Docker: per-module Dockerfiles, `docker-compose.yml` (postgres, stripe-mock, 4 services,
  shared batch volume), one-command `docker compose up`; verified by actually running it.
* **T11** Non-Docker local path: `scripts/run-local.sh` (embedded/looked-up Postgres + stripe-sim),
  verified by actually running it.
* **T12** `scripts/demo.sh`: end-to-end happy path + both failure branches, run and captured.
* **T13** Docs: `docs/architecture.md`, `docs/api-reference.md`, `docs/run-guide.md`,
  `docs/decision-log.md`, `docs/semantic-mismatches.md`, `docs/phase2-seams.md`.
* **T14** `CLAUDE.md` (incl. the do-not-modify list), README, final verification pass, PR ready.

## 5. Phase-2 seams (designed for, not built)

* `subsystem-clients` is the single place an MCP server needs to depend on; each of the six
  services maps 1:1 to a future MCP tool.
* Every canonical<->legacy translation lives in `SemanticMappers`, so the tokenomics experiment
  ("raw heterogeneous schemas in the prompt" vs. "MCP layer does the translation") can measure
  exactly the work this class does.
* `StartPayment` returns a Stripe client secret, which is the natural UCP checkout hand-off point.
* Ops endpoints are already separated from customer-facing ones, so the ops agent's wider
  permissions map onto an existing boundary.

## 6. Working agreement

* Priority order: realistic heterogeneity > works end-to-end > simplicity > performance.
* Never traded away: the heterogeneity requirements; the system must really run.
* Read-only files: `PROJECT_DESC_HUN.md`, `INITIAL_DESIGN.md`, `TODOS.md`, `PROGRESS_DIARY.md`.
* Commit + push after every task; `docs/superpowers/STATUS.md` updated on every push.
