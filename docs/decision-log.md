# Decision log

Decisions taken while building the phase-1 legacy system, with the reasoning that produced them.
Where a decision overrides something in `INITIAL_DESIGN.md` or `PROJECT_DESC_HUN.md`, that is called
out explicitly.

Standing constraints these were all taken under, from the working agreement:
priority order **realistic heterogeneity > works end-to-end > simplicity > performance**; never
trade away the heterogeneity requirements or the system actually running; no real users, no scale
requirement, single-developer research PoC.

---

## DL-001 — Stack: Java 21, Spring Boot 3.5.6, Flowable 7.2.0, PostgreSQL 16, Maven

Given by the brief. The only open question was the Flowable/Spring Boot pairing, since Flowable 7.2.0
was released against an earlier Spring Boot line. Verified before building anything by running a
throwaway Spring Boot 3.5.6 app with `flowable-spring-boot-starter-process:7.2.0`, a process with a
waiting message catch event, and a `messageEventReceived` correlation into it. It completed. That
mechanism is what subsystem 2 is built on, so it was worth ten minutes to confirm rather than
discover at T07.

## DL-002 — Four subsystem processes plus two supporting modules, in one Maven reactor

`catalog-service`, `activation-service`, `billing-service` are the three subsystems the brief asks
for. `ops-console` is a fourth process, and `subsystem-clients` and `stripe-sim` are libraries/tools.

Why a fourth process rather than folding ops into one of the three: the failure branches span two
subsystems each, and the ops story is precisely that no single subsystem can see the whole problem.
Putting the diagnostics inside any one of them would destroy the thing being demonstrated.

One reactor rather than separate repositories because this is a single-developer PoC and cross-module
refactoring should stay cheap.

## DL-003 — Tests split `*Test` (unit) / `*IT` (integration), Failsafe for the latter

`mvn test` runs the fast unit tests with no Docker and no database. `mvn verify` additionally runs the
integration tests, which need PostgreSQL containers, a live HTTP port, a Flowable job executor and
real files. **The default command for this repository is `mvn verify`, not `mvn test`** — `mvn test`
passing means almost nothing here.

## DL-004 — `spring-boot-maven-plugin` uses `<classifier>app</classifier>`

Without it, `repackage` replaces each module's main artifact with the fat jar. Failsafe runs *after*
`package`, so it then puts the fat jar on the test classpath, where the classes live under
`BOOT-INF/classes` and are invisible — test discovery fails with `NoClassDefFoundError` before a
single test runs. With the classifier, the plain jar stays the main artifact and the runnable jar is
`target/<module>-1.0.0-SNAPSHOT-app.jar`. The Dockerfiles and `scripts/run-local.sh` use that name.

## DL-005 — Docker Engine API version pinned for Testcontainers

Testcontainers 1.21.3 bundles docker-java, which negotiates Docker Engine API **1.32**. Docker Engine
25 and newer refuse anything below 1.40 (`client version 1.32 is too old`), so every container-based
test failed with "Could not find a valid Docker environment" on a machine with Docker 29. Failsafe now
passes `-Dapi.version=${docker.api.version}`, default `1.44`, which docker-java reads as its
`DockerClientConfig` API version. Override with `-Ddocker.api.version=...` on an older daemon.

Rejected alternatives: a `~/.docker-java.properties` file (works, but invisible to a fresh clone and
to CI), and dropping Testcontainers for an embedded PostgreSQL (PostgreSQL's `initdb` refuses to run
as root, which this container is).

## DL-006 — Seven tables, not four. Overshooting the scope ceiling, and why

`INITIAL_DESIGN.md` sets a ceiling of "kb. 4 tábla összesen". The build has seven, excluding
Flowable's engine tables:

| # | Table | Subsystem | Why it exists |
| - | --- | --- | --- |
| 1 | `catalog.plan` | 1 | core — the product catalogue |
| 2 | `catalog.subscriber` | 1 | core — the customer |
| 3 | `catalog.subscription` | 1 | core — what was bought |
| 4 | `billing.invoice` | 3 | core — what is owed |
| 5 | `billing.billing_account` | 3 | **the vehicle for the identity mismatch** |
| 6 | `billing.payment_batch` | 3 | **the vehicle for failure branch B** |
| 7 | `activation.activation_order` | 2 | **the vehicle for subsystem 2 having a data model at all** |

The four core tables match the ceiling exactly. Each of the other three is the minimum structure
needed for a property the brief requires and none of them adds a new domain concept:

* **`billing_account`** is how `"00000042"`, `42` and `"BA-00042"` become three names for one person.
  The heterogeneity minimum requires "eltérő ID-terek" and this is what makes the mismatch a lookup
  rather than a formatting convention. Without it billing would have to store the catalog's customer
  number, which would collapse the mismatch.
* **`payment_batch`** is failure branch B. The branch *is* "a batch was sent and never acknowledged",
  which is a statement about a batch. Modelling it as columns on `invoice` was considered and
  rejected: the batch's own file name, sent/acked timestamps and totals are what ops reads, and
  grouping invoices by a nullable `batch_id` string to reconstruct them would be worse code for no
  structural saving.
* **`activation_order`** is subsystem 2's data model. The brief requires at least one subsystem to be
  semantically different, and the process engine is nominated as that subsystem. Keeping the order
  only in Flowable process variables was considered — it would have saved the table — and rejected:
  querying by order number across runtime and historic variable tables is fragile, and an order with
  no schema of its own has no dialect to disagree with anyone in, which is the entire point.

## DL-007 — Two faces of the deliberate failure branch, not one

`INITIAL_DESIGN.md` service 6 names both: "Elakadt aktiválás **vagy** számlázási
batch-visszaigazolás". Both are implemented.

They are not two separate features. They are the same class of problem — *a state that exists in one
subsystem and cannot be expressed in its neighbour* — demonstrated in two different subsystems with
two different protocols. Failure branch A is found by correlating REST with direct JDBC; branch B by
correlating SOAP with files on disk. Implementing only one would leave half the protocol surface
without an ops story, and the diagnostics are the place where crossing protocol boundaries by hand is
most obviously painful, which is what phase 2 is meant to fix.

Each also has a seeded instance (`SUB-2026-000009`, `BATCH-20260925-001`) so both are findable from a
cold start without triggering anything.

## DL-008 — The boundary timer is non-interrupting

`cancelActivity="false"` on the `provisioningTimeout` boundary event. This is the single most
consequential line in the BPMN file.

With an interrupting timer, the timeout would cancel the `waitForProvisioning` sub-process, destroy
the message subscription, and leave ops with nothing to correlate into — the only possible repair
would be starting a new order, abandoning the catalog row. With a non-interrupting timer the timeout
runs a side branch that *reports* the problem and the wait stays live, so the callback that eventually
arrives — or the one ops injects by hand — finishes the order normally.

That makes the repair path identical to the happy path, which is both better engineering and a far
better demo: the engine cannot tell an ops-injected message from a platform callback.

Cancelling remains available as a separate, explicitly irreversible operation.

## DL-009 — `activation-service` does *not* use `subsystem-clients`

Activation has its own hand-rolled `CatalogRestClient` and `BillingSoapClient`, duplicating
translation logic that `subsystem-clients` also implements for the ops console.

This looks like a mistake and is deliberate. Each legacy subsystem in a real landscape grew its own
integration layer, with its own idea of the rules, at its own time. That duplication *is* the problem
phase 2 is supposed to solve: an MCP server replaces N ad-hoc translations with one. Factoring it out
now would delete the evidence and make the tokenomics comparison meaningless — there would be nothing
to compare against.

`subsystem-clients` exists in parallel as the clean, canonical layer, so the contrast is visible side
by side in one repository.

## DL-010 — Activation keeps a checked-in copy of billing's XSD

`activation-service/src/main/resources/xsd/billing-v1-vendor-copy.xsd` is a copy of
`billing-service/src/main/resources/xsd/billing-v1.xsd`, and activation generates its own JAXB stubs
from it into its own package.

That is what an integration team handed a WSDL by another department actually does. Alternatives
considered:

* **A shared `billing-soap-contract` module.** Zero drift, less code — but sharing generated types
  between provider and consumer is modern practice, not legacy practice, and it quietly removes a
  boundary the project is about.
* **A relative path to billing's XSD in activation's POM.** Cross-module file references in a Maven
  build; fragile and no more honest.

The cost of the copy is drift, so `BillingContractCopyTest` compares the two files and fails with the
exact `cp` command to run. It skips (rather than fails) if `billing-service` is not on disk, so the
module remains independently buildable.

## DL-011 — Stripe: official `stripe-mock` under compose, `stripe-sim` locally, real SDK in both

The brief asks for Stripe's official `stripe-mock` image or a Stripe-API-shaped simulator, so a real
test key can be swapped in later. Both are provided.

`stripe-mock` is the official image and is what compose runs. It answers with canned data and has no
memory, so a retrieve after a confirm still reports the initial status — fine for creating
PaymentIntents, useless for showing a payment completing. `stripe-sim` is ~150 lines implementing the
three endpoints this system calls, in Stripe's response shape, with state. It listens on 12111, the
same port, so the two are drop-in replacements.

Both are reached through the official `stripe-java` SDK over HTTP, so pointing at real Stripe is
`STRIPE_API_BASE=https://api.stripe.com` plus an `sk_test_` key and no code change.

Note for anyone changing the default key: `stripe-mock` validates the *shape* of the API key and
requires it to be alphanumeric after the `sk_test_` prefix. `sk_test_mclsaat_local` was rejected with
an `AuthenticationException`; `sk_test_mclsaat123` is accepted.

## DL-012 — No authentication anywhere

No users, no tokens, no TLS between services. The brief is explicit that there are no real users and
this is a single-developer research PoC, and an auth layer would add surface without adding any of
the heterogeneity the project is about.

The *boundary* is nevertheless modelled, because phase 2 needs it: customer-facing endpoints and
ops-only endpoints are separated by path (`/activation/v1/...` versus `/activation/v1/ops/...`, and
the ops console as a whole), and the ops operations on billing are separate SOAP operations. When the
ops agent gets wider permissions than the customer agent, it will get them along an existing seam.

Nothing in this system should be exposed outside a developer machine or a compose network.

## DL-013 — Billing's payment lifecycle has three states, not two

`OPEN` → `SETTLEMENT_PENDING` → `PAID`, rather than `OPEN` → `PAID`.

The middle state is money Stripe has taken that the business does not yet consider posted, because
the clearing house has not acknowledged the settlement batch. It exists so that failure branch B has
something to strand *in*, and because it is what a real telco's books actually look like: the card
processor and the bank reconciliation are different events days apart.

It also gives the Stripe webhook an honest job. Marking the invoice `PAID` on the webhook would be
simpler and would make the batch leg decorative.

## DL-014 — VAT: billing invoices on net, the catalog quotes gross, and the round trip loses a fillér

Hungarian consumer prices are gross; billing systems invoice on net. So the catalog stores a gross
`monthly_fee_minor` and `createInvoice` takes a **net** amount, leaving the caller to divide by 1.27.
Both sides round to two decimals independently.

For two of the nine seeded plans the result does not come back to the price the customer was quoted:
`MOB-VOICE-0010` gains a fillér (5990.00 → 5990.01) and `INET-FIB-1000` loses one
(17990.00 → 17989.99).

**This is not a bug and must not be "fixed" in isolation.** It is the measurable cost of two
subsystems disagreeing about what "the price" means, it is pinned across the whole catalogue by
`VatCalculatorTest.theRoundTripErrorAcrossTheWholeSeededCatalogIsExactlyThis`, and it is documented in
`semantic-mismatches.md`. Changing the rounding means updating all three together. Deciding who owns
the discrepancy is exactly the kind of question phase 2 is meant to surface.

## DL-015 — `activation.provisioning.callback-base-url` is configuration, and the ITs pin the port

The simulated provisioning platform calls back over real HTTP to activation's own public callback
endpoint, from a scheduled thread, rather than reaching into the engine directly. That means it needs
a URL for the service it lives in, which under compose is the service name.

Consequence for tests: the integration tests use `webEnvironment = DEFINED_PORT` with a fixed port
(18082) instead of `RANDOM_PORT`, because the callback URL has to be known before the context starts.
A lazily-resolved "call myself" mode was considered and rejected as production magic in service of a
test.

The alternative — having the simulator call the correlation service directly in-process — was
rejected because it would stop testing the callback endpoint, which is the asynchronous seam of the
whole system.

## DL-016 — Execution lookups go via the process instance id, not the business key

`ExecutionQuery.processInstanceBusinessKey(...)` matches the process instance's *own* execution row,
because that is where Flowable stores the business key. The `provisioningCompleted` message
subscription lives on a **child** execution inside the `waitForProvisioning` sub-process, so querying
executions by business key finds the root and silently reports that nothing is waiting.

`ActivationOrderService.findProvisioningSubscription` therefore resolves the process instance by
business key first and then queries its executions by `processInstanceId`. This was found by an
integration test asserting `waitingForCallback` after the process had demonstrably parked — worth
noting because the symptom (an empty result, not an error) is indistinguishable from "the process
already moved on".

## DL-017 — The demo shortens the timers, and says so

`activation.provisioning.timeout` defaults to `PT45S` and the demo overrides it per order to a few
seconds; `billing.batch.unconfirmed-after-minutes` defaults to 2. A production SLA would be hours and
a settlement window overnight.

Short timers are the only way a failure branch can be demonstrated in a two-minute scripted demo. They
are all configuration, all documented in `run-guide.md`, and nothing in the code assumes they are
short.

## DL-018 — `billing.batch.auto-export-enabled` defaults to false

A real deployment would cut settlement batches on a nightly cron. Here the scheduled export exists but
is off by default, so the demo and the tests decide when a batch is cut and can assert on its
contents. Turning it on is one property.

## DL-019 — Order numbers are query parameters, not path variables

Activation's order numbers contain slashes (`ORD/2026/0000001`), because that is what the subsystem's
numbering scheme looks like. A path variable cannot carry one:

* Tomcat rejects an encoded `%2F` in a path by default, and
* configuring it to decode turns `ORD%2F2026%2F0000001` into three path segments, which no longer
  match `/orders/{orderNo}`.

So `GET /activation/v1/orders?orderNo=ORD/2026/0000001`, and the ops operations take the order number
in the request body. The legacy identifier format dictates the shape of the API, which is a fair
miniature of what working with one of these systems is like.

Found by driving the documented endpoint by hand and getting nothing back.

## DL-020 — The image build can trust an extra certificate authority

`docker/ca/` is empty in the repository and the `Dockerfile` imports whatever it finds there into the
JDK truststore before Maven runs. On an ordinary machine this does nothing.

It exists because on a machine whose outbound HTTPS goes through a **TLS-terminating proxy** — a
corporate MITM appliance, or a cloud dev container — the JVM inside the build container refuses to
talk to Maven Central with `PKIX path building failed: unable to find valid certification path to
requested target`. Without the hook, `docker compose up --build` simply cannot work in such an
environment, and "one command" would be a claim rather than a fact.

Certificate files are gitignored: they belong to one machine's proxy.

The same environment forces two related choices:

* **`network: host` on each compose build.** The proxy is bound to `127.0.0.1`, which a
  bridge-network build container cannot reach. Building on the host's network works with or without a
  proxy.
* **No `apt-get` in the runtime image.** `apt-get update` over HTTP through a TLS-terminating proxy
  fails with "the repository is not signed", so the runtime images install nothing — not even curl —
  and compose health-checks with bash's `/dev/tcp` instead. The images are smaller for it.

## DL-021 — Health checks speak HTTP through bash, not curl

```yaml
test: ["CMD", "bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8081 && printf 'GET /actuator/health ...' >&3 && grep -q '\"status\":\"UP\"' <&3"]
```

`CMD` rather than `CMD-SHELL`, because the image's `/bin/sh` is dash and `/dev/tcp` is a bash feature.

This is a real health check, not a port probe: it reads the actuator's answer and looks for
`"status":"UP"`, so a service whose database is unreachable reports unhealthy. It keeps the runtime
images at "a JRE plus this project's jar" and sidesteps DL-020's apt problem.

## DL-022 — The batch-exchange directories are created in the image

The runtime image creates `/var/lib/legacy/batch-exchange/{outbox,inbox,archive}` owned by the
non-root runtime user, even though a named volume is mounted over that path.

That is the point: Docker seeds an empty named volume from whatever the image has at the mount point,
**ownership included**. With the directories absent from the image the volume is created root-owned,
and billing — running as uid 10001 — cannot write settlement files into it. The symptom is a 502 from
`exportPaymentBatch` reading "could not write settlement file", which no test caught because the
tests use a temporary directory the test JVM owns.

Found by running `scripts/demo.sh` against `docker compose up`. Worth recording as the clearest example
in this project of why green tests are not verification.

## DL-023 — `maven-compiler-plugin` and `maven-failsafe-plugin` versions are pinned

They were originally declared without versions, which works but makes the build irreproducible (Maven
resolves "latest" each time) and breaks `dependency:go-offline`, which cannot pre-fetch a plugin whose
version it does not know — noisy on every module and a real cost inside a Docker build, where the
dependency layer is meant to be cached.

## DL-024 — Two ways to pay an invoice: browser hand-off and browserless `payInvoice`

Closes gap 1.3 of [`phase2-gaps.md`](phase2-gaps.md). Until now `startPayment` was the only way in, and
it hands back a `clientSecret` that only a browser running Stripe.js can use. The demo script stood in
for the browser (a raw Stripe confirm) and for Stripe (a hand-posted webhook), and nothing ever cut a
settlement batch, so an agent- or chat-driven purchase stalled at `OPEN`.

Both entry points now exist, deliberately:

| | `startPayment` | `payInvoice` |
| --- | --- | --- |
| Channel | a browser, a UCP-style checkout | chat, an agent: nothing to run Stripe.js |
| Who confirms at Stripe | the customer's browser, with the `clientSecret` | billing, server-side, with Stripe's test card `pm_card_visa` |
| Who tells billing it succeeded | Stripe's `payment_intent.succeeded` webhook | billing, in the same call |
| Settlement batch | cut by ops or the nightly export (DL-018) | cut by the call itself |

Decisions inside it:

* **One transition, two callers.** The `OPEN` -> `SETTLEMENT_PENDING` move lives in one private method of
  `PaymentService`, shared by the webhook and `payInvoice`. It is a conditional `UPDATE ... WHERE status
  = 'OPEN'`, so the two racing, or either being repeated, is harmless.
* **Not one transaction.** The Stripe calls run outside any DB transaction, the transition commits on its
  own, and the batch export happens after that and is allowed to fail. Once Stripe has taken the money
  billing must remember it whatever happens to a file write; a failed export leaves the invoice in
  `SETTLEMENT_PENDING` for a retry of the same call (it exports an unbatched pending invoice) or for ops.
* **The call returns at `SETTLEMENT_PENDING`, with the batch id, not at `PAID`.** `PAID` still needs the
  clearing house's acknowledgement (the file exchange stays asynchronous, and failure branch B stays
  reachable by silencing it). Callers poll `getInvoices`.
* **`startPayment`'s intent is reused**, not duplicated, if both paths touch one invoice.
* **`exportBatch` is not scoped to the invoice.** It sweeps every unbatched pending invoice, as the
  nightly job does. One call can therefore settle someone else's payment. Left as it is: one batch per
  payment would be a different, less realistic settlement model.
* **The Stripe stand-in must be stateful, so compose now runs `stripe-sim`.** The official `stripe-mock`
  is stateless: a confirm still returns the fixture's `requires_payment_method`, and billing (correctly)
  will not treat that as paid. The official image remains as the opt-in compose profile
  `official-stripe-mock` and in the create-only integration tests; `PayInvoiceIT` starts `stripe-sim`
  in-process. This supersedes the "compose runs stripe-mock" half of DL-011.
* **The Stripe SDK is configured per request** (`RequestOptions`), not through the static
  `Stripe.apiKey` / `Stripe.overrideApiBase`, so two Spring contexts in one JVM (`PayInvoiceIT` against
  the sim, the other ITs against the mock) cannot repoint each other.
* **PaymentIntents are created with `automatic_payment_methods` (redirects off)** instead of
  `payment_method_types=card`. `stripe/stripe-mock:latest` had dropped the old parameter, which broke
  `PaymentFlowIT` and `BatchSettlementIT` on a clean checkout, and a server-side confirm has no browser
  to follow a redirect anyway.

Not done, deliberately: gap 1.4 (activate-then-bill) is untouched — an invoice number still only exists
after provisioning — and the `pm_card_visa` test card is a test-mode stand-in; a stored customer payment
method is a phase-3 question.
## DL-025 — Gap 1.4: the UCP checkout stays `complete_in_progress` over activate-then-bill

Closes gap 1.4 of [`phase2-gaps.md`](phase2-gaps.md). Decided with the user on 2026-10-06, as a
research-then-interview exercise. **The decision itself changes no legacy behaviour**: invoice timing and
the BPMN stay as they are. It fixes the contract that the phase-3 UCP adapter is built against, and it
expects a few additive follow-ups, listed under "Expected additive changes" below.

**Decision.** Keep activate-then-bill. `issueInvoice` stays the last task of the BPMN. A UCP Complete
Checkout is accepted straight away and the checkout stays `complete_in_progress` for as long as the
order is being provisioned and charged. It becomes `completed` only when the money has actually moved.

| UCP checkout status | State of this system |
| --- | --- |
| `incomplete` / `ready_for_complete` | before any order exists: onboarding, validation, quote (adapter only) |
| `complete_in_progress` (no `order`) | order `RECEIVED` … `AWAITING_PROVISIONING`; then `PROVISIONED` with an `OPEN` invoice; then the charge is being made |
| `completed` (`order.id` = order number) | order `PROVISIONED` **and** invoice `SETTLEMENT_PENDING` or later. `PAID` is **not** awaited: it is the back-office books (DL-013), not the card charge |
| `canceled` | order `CANCELLED` / `FAILED` (ops cancel), the checkout reaching `expires_at`, or a final charge decline |

### Answers to the twelve decision questions

| # | Answer |
| --- | --- |
| Q1 | The checkout ends in `completed` only after a successful charge. "Order accepted, payment pending" is expressed as `complete_in_progress`, never as a completed order: UCP's Order has no payment-pending state. |
| Q2 | A delayed first payment is acceptable. The delay is the provisioning time (seconds in the demo, DL-017). |
| Q3 | The charge is attempted as soon as the order is `PROVISIONED` (its `invoiceNo` is then always present), and must succeed or be given up before the checkout's `expires_at`. `expires_at` must exceed the provisioning timeout plus the retry budget (UCP's default is six hours). |
| Q4 | Not applicable. No pay-at-checkout branch is added. |
| Q5 | One lifecycle for both channels, two triggers. UCP: the adapter charges automatically with the instrument supplied at Complete Checkout, which is the buyer's consent. Chat: the customer pays explicitly (service 5). Both end in the same `payInvoice(invoiceNo)` call. |
| Q6 | The checkout quotes the **billing-derived** gross: net = round(gross / 1.27), VAT = round(net × 0.27), total = net + VAT. That is 5990.01 for `MOB-VOICE-0010` and 17989.99 for `INET-FIB-1000`, i.e. what billing will invoice and Stripe will charge. |
| Q7 | The UCP adapter owns "quote == charge". The catalog-versus-billing disagreement itself stays unowned *inside* the legacy systems: DL-014 is untouched and `VatCalculatorTest` does not change. |
| Q8 | A decline never yields `completed`. Bounded retries (idempotent by `invoiceNo`), then the checkout is `canceled` with an explicit message before `expires_at`. The `PROVISIONED` order and its `OPEN` invoice are left as they are and surfaced to ops. There is **no automatic compensation**: recorded below as a known gap. |
| Q9 | No new status vocabulary. `OrderStatus` and `InvoiceStatus` map onto UCP statuses by the table above. "Provisioned but unpaid" is `PROVISIONED` + `OPEN`; "paid but unsettled" is `SETTLEMENT_PENDING`. |
| Q10 | None in the normal customer flow. Ops is needed for failure branches A and B and for the decline follow-up, as before. |
| Q11 | Required. The order number is the key at order placement, `invoiceNo` at payment, and the UCP idempotency key of Complete Checkout maps onto the order number. A replayed Complete Checkout creates neither a second order nor a second charge. |
| Q12 | Telco realism wins (`AGENTS.md` §8). The checkout's immediacy is given up; polling (`Get Checkout`) bridges the gap, which also matches the design's polling-only failure detection. |

There is no finance or legal stakeholder in this single-developer PoC (Task C). "No legal constraint on
invoice-after-service" is an assumption, not a finding; Hungarian invoicing-timing rules were not
researched.

### Rejected

* **1b. `completed` immediately, with a deferred Payment Term.** The most telco-natural fit and the
  newest spec feature, and it stays the documented evolution path. Rejected for now because (a) it is an
  extension, and a buying platform has to support it for it to apply (my inference from UCP's capability
  negotiation, not verified); (b) the instrument must be able to fund a deferred charge, which needs an
  off-session saved-payment-method model that does not exist (1.3 charges a fixed test card); (c) a
  decline after `completed` has no checkout status, only order `adjustments`.
* **2. Authorize at checkout, capture on activation.** Needs a new BPMN task, new billing operations, a
  SOAP contract change in both XSD copies, capture support in `stripe-sim` (it has none), and a void on
  cancel, plus hold-expiry as a new failure mode. It also widens the scope of a gap that was meant to be a
  decision. A prepay-invoice variant is worse: it adds refunds.
* **Quote the catalog price.** For two of nine plans the buyer would be charged an amount other than the
  one shown, with nobody owning it.
* **Fix the VAT round trip.** Reverses DL-014 and removes a measured mismatch.
* **Autopay for chat.** Makes service 5 decorative and needs a stored payment method that does not exist.
* **Automatic compensation after a decline** (terminate the subscription, cancel the invoice). Real
  scope: nothing in billing ever sets an invoice to `CANCELLED`, and ops has no action that reverses a
  provisioned subscription.

### Acceptance criteria for the implementation (non-negotiable)

1. **What stays fixed:** invoice timing (`issueInvoice` after provisioning, BPMN order unchanged) and the
   absence of any pay-at-checkout path. `startPayment` and the existing behaviour of `payInvoice` (1.3)
   are not altered. The additive changes this decision expects are listed below, and nothing else.
2. **Status mapping** is exactly the table above. `completed` is never returned before the invoice has
   reached `SETTLEMENT_PENDING`, and never waits for `PAID`.
3. **No charge before an invoice exists, and none before the order is `PROVISIONED`.** The adapter reads
   `CanonicalOrder.invoiceNo` (mapped from activation's `invoiceRef`; it is written by `issueInvoice`
   *before* `markProvisioned`, so it can briefly appear on an order that is not yet `PROVISIONED`, which
   is why the adapter waits for the status) and calls `payInvoice(invoiceNo)`. One order yields exactly
   one charge across retries, replays and concurrent polls.
4. **Quote == invoice gross == charge** for all nine seeded plans, proved by an integration test that
   compares the adapter's quote with the gross the real `createInvoice` returns (a test that recomputes
   the same formula proves nothing). The quote reproduces two subsystems' arithmetic, activation's net
   rounding and billing's VAT rounding, and differs from the catalog browse price for exactly the two
   plans DL-014 names.
5. **Decline path** (needs the additive changes below): no `completed`,
   `canceled` with a message before `expires_at`, order and invoice unchanged, and listable by an ops
   diagnostic as "`PROVISIONED`, invoice `OPEN` beyond a threshold".
6. **Failure branch A:** a `STUCK` order leaves the checkout `complete_in_progress`; ops cancel gives
   `canceled`; force-provision continues to the charge.
7. **Failure branch B:** a silenced clearing house does not affect the checkout, which completes at
   `SETTLEMENT_PENDING`; the stranded invoice is still reported by the existing diagnostics.
8. **Chat is unchanged:** payment is an explicit customer action and never automatic.
9. **`expires_at`** is set above provisioning timeout plus retry budget.
10. **Order identifiers:** the UCP `order.id` is the activation order number, which contains slashes
    (DL-019), so any `permalink_url` must carry it as a query parameter.

### Expected additive changes (none is done by this decision)

Listed in the order they should be done, with the phase each one logically belongs to:

1. **A catalog terminate wrapper in `subsystem-clients` (criterion 5). A leftover phase-1 omission.**
   Phase 1's ops console diagnoses an orphaned subscription and recommends "terminate in the catalog",
   but nothing can do it: the catalog's REST `terminate` exists and the shared layer has no wrapper. It
   is the same kind of gap as 1.1 and 1.2, is independent of the granularity decision (2.2), and is
   small. It must exist before the ops server's remediation is complete.
2. **MCP#4 itself, a phase-2 task:** one server module over `subsystem-clients`. Its tool surface follows
   the granularity decision (2.2), which is the first task of phase 2 and comes before any tool is defined.
3. **The DL-025 rule, deferred to phase 3 with the UCP adapter.** It becomes a third finding type in
   `LandscapeDiagnostics` for "`PROVISIONED`, invoice `OPEN` beyond a threshold", surfaced through the
   diagnose tool. The failure it detects is a declined UCP charge, which cannot happen until the adapter
   exists, so nothing in phase 2 needs it. It needs a bulk source that is legacy-system work in the style
   of phase 1, most likely an additive billing query for open invoices across accounts (both XSD copies
   together, `BillingContractCopyTest`), joined to order status through activation. Not designed here.
   Whether the chat channel can also produce the same unpaid state was not checked.

### Interactions with other open items (recorded, not decided)

* **Gap 2.2 (tool count and granularity).** The ops persona sees MCP#4's tools plus the customer tools it
  also connects. `INITIAL_DESIGN.md`'s "6+1" counts service 6 and a separate diagnostic tool; with this
  decision MCP#4 carries both, so the count is a 2.2 question again. The evidence for tool-count effects
  comes from libraries of dozens to thousands of tools; at roughly 8 to 13 tools per persona it is thin,
  so consolidation guidance, not measured token savings, is the relevant input.
* **Gap 1.4 follow-ups.** DL-025's "ops diagnostic rule" lands in `LandscapeDiagnostics` and surfaces
  through MCP#4's diagnose tool. The known gap there (after a decline the SIM may be live and the invoice
  open, with no ops action to reverse it) is related to criterion 5 but is not closed by it: a catalog
  terminate does not cancel the invoice.

### Revisit trigger

If the diagnosis is later added to the tokenomics measurement, reopen this decision. A pre-joined
diagnose tool would then fold "orchestration removed" into the MCP arm's measured advantage, and the raw
arm has to do the join anyway. The per-subsystem arrangement would be the apples-to-apples one.

### Research basis and its limits

Read from raw sources on 2026-10-06:

* **MCP specification 2026-07-28** (git tag `2026-07-28`, commit `5f5440b`; the two files below are
  byte-identical at the tag and on `main` as of 2026-10-06),
  `docs/specification/2026-07-28/server/tools.mdx` and `schema/2026-07-28/schema.ts` in
  `modelcontextprotocol/modelcontextprotocol`, compared with 2025-11-25. In 2026-07-28 `tools/list` "MUST NOT vary
  per-connection" but "MAY vary by the authorization presented"; that clause is absent from 2025-11-25.
  Tool annotations (`readOnlyHint`, `destructiveHint`, ...) are defined in both and "MUST" be treated as
  untrusted unless from trusted servers. The spec says nothing about how many tools a server should expose.
  With no authentication (DL-012) a mixed customer-and-ops server cannot hide its ops tools, which is why
  the boundary is by server.
* **Claude Agent SDK, TypeScript package 0.3.291** (npm, published 2026-10-06T03:33Z), read from
  `sdk.d.ts` doc comments, not run. `allowedTools` auto-approves and does not restrict. The session-level
  `disallowedTools` removes the named tools from the model's context. Only the **subagent definition's**
  `disallowedTools` is documented to take server-level specs (`mcp__server`, `mcp__server__*`,
  `mcp__*`), which remove every tool of that server; the session-level comment does not say so, so a
  session-level persona should list per-tool names (`mcp__ops__<tool>`) unless that is checked. A subagent
  definition also takes its own `mcpServers`. A persona restricted only by `allowedTools` therefore still
  sees the descriptions. The framework stays open (seams §0); this is one example.
* **Anthropic, "Writing effective tools for AI agents—using AI agents"** (2025-09-11): consolidate chained operations into
  fewer, higher-level tools; too many or overlapping tools distract agents. **Anthropic, "Introducing
  advanced tool use on the Claude Developer Platform"** (2025-11-24): tool search is "less beneficial"
  under 10 tools. Its token example (about 77K to 8.7K, an 85% reduction) is for 50+ MCP tools, and its
  accuracy gains (49% to 74%, 79.5% to 88.1%) are reported for "large tool libraries"; neither transfers
  to roughly 8 to 13 tools.
* **arXiv 2605.24660** (abstract only): about shortlist depth over registries of 20 to 3,251 tools;
  not used as evidence for a decision at this size.

Limits: no measurement exists at this system's scale; the SDK behaviour above was read, not executed; no
Java MCP SDK was examined, so which MCP version a server would implement is open; figures from BFCL,
MTU-Bench and RAG-MCP reached this session only through a search summariser and are deliberately not
cited.
