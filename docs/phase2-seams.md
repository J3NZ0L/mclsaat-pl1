# Phase-2 seams

Phase 1 (this repository) is the legacy system. Phases 2 and 3 of `PROJECT_DESC_HUN.md` — MCP
servers, agents with skills, the tokenomics measurement, and Google UCP — are **not built here**.
This page records where they attach, so that when they are built nothing in the legacy system has to
be rewritten to accommodate them.

Each item is a seam that already exists in the code because it was needed for phase 1 anyway. The
tool names in §2 are the one exception: they are a suggestion, not a decision. What phase 1 is still
missing for phase 2 is in [`phase2-gaps.md`](phase2-gaps.md); read it before starting phase 2.

---

## 0. The design this page is measured against

`INITIAL_DESIGN.md` records only the bottom of the user's phase-2 architecture sketch: the shared
subsystem-client layer and the three subsystems under it. The rest of the design, given by the user
on 2026-09-30 and not otherwise in the repository, is:

* **Two entry points, not two agents.** The company's own chat interface and a UCP endpoint are two
  integration channels onto the same back end. UCP is not "run" here; an external agent (e.g. Gemini)
  calls it and this system serves it.
* **One agent core, two personas.** Customer and ops use the same runtime; only the system prompt and
  the tool allowlist differ. The framework is open (e.g. Claude Agent SDK).
* **One MCP server per subsystem**, all three built on the shared subsystem-client layer.
* **The UCP adapter is not an MCP client.** It is a backend-to-backend call, so it uses the shared
  subsystem-client layer directly rather than going over MCP transport. (Making it an MCP client was
  considered and rejected for simplicity.)
* **The failure branch is triggered by a diagnostic tool**, because there is no push notification,
  only polling. It lists activations and invoices waiting for confirmation longer than a threshold,
  lives on MCP#2 and MCP#3 (activation and billing), and only the ops persona can reach it.
  *(Placement superseded by DL-026: the diagnostic and remediation tools live on a fourth, ops-only
  server. The rest of the bullet, polling only and ops persona only, stands.)*
* **About 6+1 tools**: one per service plus the diagnostic tool. Final granularity (one tool per
  service, or browse → select → confirm steps) is deliberately left to the tokenomics phase.

Where the rest of this page agrees with those decisions, it was written without seeing them.

---

## 1. `subsystem-clients` is the layer MCP servers depend on

The library holds three things:

* a **canonical model** — `Money`, `DataVolume`, `CanonicalPlan`, `CanonicalSubscription`,
  `CanonicalInvoice`, … — which belongs to none of the three subsystems;
* **`SemanticMappers`**, the single place every legacy dialect is translated to and from it;
* one client per protocol: `CatalogJdbcClient` (direct SQL, read-only), `CatalogSubscriberRestClient`
  (the catalog's REST writes: `createSubscriber`, `terminateSubscription`), `ActivationRestClient`,
  `BillingSoapClient`, `BatchFileClient`. Activation's `CatalogRestClient` is its own (DL-009) and is not
  this one; catalog reads through this layer are direct SQL only.

An MCP server for this landscape should depend on `subsystem-clients` and expose its canonical model
as tool schemas. It should *not* talk to the three services directly, and it should not re-derive the
translations — that is the mistake `activation-service` deliberately makes (see `decision-log.md`
DL-009) and the one phase 2 exists to fix.

`ops-console` is the worked example: it is a consumer of exactly this layer, doing exactly the kind of
cross-subsystem work an agent will do.

**The layer is not complete for phase 2.** It was built for the ops console, so it covers what ops
needs and not what a customer does: `ActivationRestClient` has no method for `POST /orders` (services
2 and 4), and nothing wraps the catalog's `POST /api/v1/subscribers`, which a new buyer needs before
activation will accept an order. Both have to be added before the customer persona or the UCP adapter
can be built on it ([`phase2-gaps.md`](phase2-gaps.md) 1.1, 1.2).

## 2. Six services, and one candidate tool mapping

`INITIAL_DESIGN.md` plans "kb. 6+1 tool" at one tool per service, and the design leaves the final
granularity to the tokenomics phase. The table below is **one candidate** for that decision, not the
decision. Settling it is the first task of phase 2 ([`phase2-gaps.md`](phase2-gaps.md) 2.2). The
services were built so that a one-to-one mapping is mechanical:

| Service | Raw interface | Suggested MCP tool | Permission |
| --- | --- | --- | --- |
| 1. Browse plans | catalog REST `GET /api/v1/plans` | `catalog.browse_plans` | customer |
| 2. Start subscription | activation REST `POST /orders` | `subscription.start` | customer |
| 3. Poll order status | activation REST `GET /orders?orderNo=…` | `subscription.order_status` | customer |
| 4. Plan change / add-on | same endpoint, `changeType` variant | `subscription.change` | customer |
| 5. Invoice query + pay | billing SOAP `getInvoices`, `payInvoice` (chat/agent) or `startPayment` (browser hand-off) | `billing.list_invoices`, `billing.pay_invoice` / `billing.start_payment` | customer |
| 6. Detect & resolve | ops console `/ops/v1/**` | `ops.diagnose`, `ops.remediate` | **ops only** |

Service 6 splitting into a diagnose tool and a remediate tool is the one place a one-to-one mapping
probably should not hold: an agent that can see a problem is much less dangerous than one that can act
on it, and the two deserve separate permissions.

Two things this candidate does not settle:

* **The count is larger than it looks.** It is eight tools, not 6+1, and `ops.remediate` hides four
  distinct actions — force-provision, cancel, reconcile with `RESEND`, reconcile with `RE_DRIVE_ACK` —
  so ten or eleven in practice.
* **The `ops.*` namespace is a fourth MCP server — decided (DL-026).** The design has one server per
  subsystem and puts the diagnostic tool on MCP#2 and MCP#3. The cross-subsystem join already exists
  as one library call (`LandscapeDiagnostics`), and phase 2 builds an ops-only MCP#4 over it, carrying
  both detection and remediation; MCP#1–#3 carry customer tools only. The tool names above stay
  candidates, and so does the count, which is [`phase2-gaps.md`](phase2-gaps.md) 2.2.

## 3. The ops/customer permission boundary already exists

There is no authentication in this system (`decision-log.md` DL-012) but the *boundary* is modelled,
by path and by operation:

* customer-facing: `/api/v1/**`, `/activation/v1/orders/**`, billing's `getInvoices` /
  `startPayment`;
* ops-only: `/activation/v1/ops/**`, the whole of `ops-console`, and billing's
  `exportPaymentBatch` / `listUnconfirmedBatches` / `getPaymentBatch` / `reconcileBatch`;
* fault injection, which is neither: `/sim/clearing-house/config`,
  `/activation/v1/ops/provisioning-platform`.

`INITIAL_DESIGN.md` describes the ops persona as using the same chat infrastructure with wider
permissions. That maps onto this split without moving anything — the ops agent gets the ops-only
tools, the customer agent does not.

## 4. `startPayment` is a candidate UCP checkout hand-off

Phase 3 puts Google UCP on top of subscription purchase as a checkout-shaped transaction. The design
names two places it could hook in: service 2 (starting the subscription) and subsystem 3 (billing).
`startPayment` returns what a payment step needs:

```
paymentRef     the Stripe PaymentIntent id
clientSecret   what a client-side confirmation flow needs
amountMinor    integer minor units, the representation payment rails use
currency
paymentStatus
```

And the three-legged invoice lifecycle (`OPEN` → `SETTLEMENT_PENDING` → `PAID`, `decision-log.md`
DL-013) gives a UCP adapter honest states to report: authorised-but-not-settled is a real condition
here, not a simplification.

Three things a UCP adapter will have to decide, which phase 1 leaves open:

* **Which price to quote.** The catalog quotes gross, billing invoices net, and for two of nine plans
  the round trip disagrees by a fillér (`semantic-mismatches.md`). A checkout protocol has to state
  one number. Nobody in this system owns that decision.
* **When the customer pays.** The invoice is issued by the process's last service task, *after*
  provisioning completes, so there is no invoice number to pass to `startPayment` until minutes after
  the order was placed. This is postpaid, activate-then-bill, and it means `startPayment` cannot be
  called at checkout time. **Decided (DL-025):** the UCP checkout is held in `complete_in_progress`
  until the order is provisioned and the invoice is charged, and no pay-at-checkout path is added
  ([`phase2-gaps.md`](phase2-gaps.md) 1.4).
* **Who closes the payment loop — answered (gap 1.3).** The system does, for a channel with no
  browser: billing's `payInvoice` confirms the PaymentIntent, makes the `SETTLEMENT_PENDING` transition
  the webhook makes, and cuts the settlement batch; the invoice reaches `PAID` when the clearing house
  answers, so the caller polls. `startPayment` and its `clientSecret` remain the browser hand-off for a
  UCP-style checkout, and `/webhook/stripe` still works (DL-024).

## 5. The tokenomics experiment has a measurable subject

The comparison `PROJECT_DESC_HUN.md` describes — raw heterogeneous interfaces with schemas in the
prompt, versus translation through an MCP layer — needs the translation work to be something you can
point at. It is:

* `ActivationSemantics` — every catalog↔activation rule, 14 tests
* `VatCalculator` — gross↔net and VAT, 5 tests, including the round-trip error table
* `PaymentService.toMinorUnits`, `BatchFileFormat.toMinor`/`toMajor` — the remaining money hops
* `SemanticMappers` in `subsystem-clients` — the canonical equivalents

The full inventory of what a prompt-only approach would have to infer per call is
[`semantic-mismatches.md`](semantic-mismatches.md). Measurable candidates:

1. **Tokens.** Schemas-in-prompt has to carry the catalog's DDL, activation's DTOs and billing's XSD
   on every call. An MCP layer carries one canonical schema.
2. **Latency.** Multi-turn inference of the same rules versus one deterministic call.
3. **Correctness — the interesting one.** Does the model get 1024 MB per GB right? Does it treat `-1`
   as unmetered rather than negative? Does it strip the `+`? Does it know `PA` is not live? Each is a
   silent wrong answer, not an error, and each is already pinned by a test that says what the right
   answer is.

Suggestion: run the same three transactions (browse → order → poll; add-on purchase; invoice → pay)
both ways, and score correctness against the existing test expectations rather than inventing new
ones.

## 6. The failure branches are the agent's reason to exist

`INITIAL_DESIGN.md` says the failure branch is "ahol az ops-ágens ténylegesen bizonyítja a
létjogosultságát". Both branches are built so that no single call can *resolve* them, which is what
makes them agent-shaped rather than script-shaped:

* **Stuck activation** needs activation's process state (REST) joined to the catalog's stale rows
  (direct JDBC) across an identifier-space mismatch — and then a judgement call between injecting the
  missing callback and cancelling the order, which have different consequences (one is reversible,
  one is not).
* **Unconfirmed batch** needs billing's batch table (SOAP) joined to the outbox files, and then a
  judgement call between `RESEND` (the clearing house may be back) and `RE_DRIVE_ACK` (the money
  demonstrably moved, the confirmation is never coming).

Both judgements depend on context no single query returns. That is the argument for an agent.

It holds for remediation, not for detection. The joins above are already done in one library call,
`LandscapeDiagnostics`, and the ops console serves each as one endpoint. **Decided (DL-026):** the ops
MCP server wraps that call, so the agent's contribution is the judgement step. The tokenomics
comparison does not lose anything by it, because the diagnosis is not one of the measured transactions
(§5). The design's alternative — per-subsystem diagnostic tools on MCP#2 and MCP#3, with the agent
doing the join — would also have missed the catalog half of branch A, which only the catalog can see.
If the diagnosis is ever added to the measurement, DL-026 should be reopened
([`phase2-gaps.md`](phase2-gaps.md) 2.1).

## 7. What phase 2 must not "fix"

Three things look like defects and are the subject matter. Changing them would quietly remove what is
being measured:

1. **The VAT round-trip error** (DL-014). Pinned by `VatCalculatorTest` and documented. Two of nine
   plans bill a fillér away from their quoted price.
2. **The duplicated translation** between `activation-service` and `subsystem-clients` (DL-009). This
   is the "before" picture.
3. **The identifier-space mismatch** (`"00000042"` / `42` / `"BA-00042"`). An MCP layer should hide
   it, not eliminate it.

`CLAUDE.md` repeats this list, because it is the easiest thing for a future session to tidy away by
accident.
