# Phase-2 seams

Phase 1 (this repository) is the legacy system. Phases 2 and 3 of `PROJECT_DESC_HUN.md` — MCP
servers, agents with skills, the tokenomics measurement, and Google UCP — are **not built here**.
This page records where they attach, so that when they are built nothing in the legacy system has to
be rewritten to accommodate them.

Nothing on this page is speculative API design. Each item is a seam that already exists in the code
because it was needed for phase 1 anyway.

---

## 1. `subsystem-clients` is the layer MCP servers depend on

The library holds three things:

* a **canonical model** — `Money`, `DataVolume`, `CanonicalPlan`, `CanonicalSubscription`,
  `CanonicalInvoice`, … — which belongs to none of the three subsystems;
* **`SemanticMappers`**, the single place every legacy dialect is translated to and from it;
* one client per protocol: `CatalogJdbcClient` (direct SQL), `CatalogRestClient`,
  `ActivationRestClient`, `BillingSoapClient`, `BatchFileClient`.

An MCP server for this landscape should depend on `subsystem-clients` and expose its canonical model
as tool schemas. It should *not* talk to the three services directly, and it should not re-derive the
translations — that is the mistake `activation-service` deliberately makes (see `decision-log.md`
DL-009) and the one phase 2 exists to fix.

`ops-console` is the worked example: it is a consumer of exactly this layer, doing exactly the kind of
cross-subsystem work an agent will do.

## 2. Six services, six tools, one to one

`INITIAL_DESIGN.md` plans "kb. 6+1 tool" at one tool per service. The services were built so that
mapping is mechanical:

| Service | Today | Suggested MCP tool | Permission |
| --- | --- | --- | --- |
| 1. Browse plans | catalog REST `GET /api/v1/plans` | `catalog.browse_plans` | customer |
| 2. Start subscription | activation REST `POST /orders` | `subscription.start` | customer |
| 3. Poll order status | activation REST `GET /orders?orderNo=…` | `subscription.order_status` | customer |
| 4. Plan change / add-on | same endpoint, `changeType` variant | `subscription.change` | customer |
| 5. Invoice query + pay | billing SOAP `getInvoices`, `startPayment` | `billing.list_invoices`, `billing.start_payment` | customer |
| 6. Detect & resolve | ops console `/ops/v1/**` | `ops.diagnose`, `ops.remediate` | **ops only** |

Service 6 splitting into a diagnose tool and a remediate tool is the one place a one-to-one mapping
probably should not hold: an agent that can see a problem is much less dangerous than one that can act
on it, and the two deserve separate permissions.

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

## 4. `startPayment` is the UCP checkout hand-off

Phase 3 puts Google UCP on top of subscription purchase as a checkout-shaped transaction.
`startPayment` already returns what a checkout needs:

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

Two things a UCP adapter will have to decide, which phase 1 deliberately leaves open:

* **Which price to quote.** The catalog quotes gross, billing invoices net, and for two of nine plans
  the round trip disagrees by a fillér (`semantic-mismatches.md`). A checkout protocol has to state
  one number. Nobody in this system owns that decision.
* **Whether a subscription purchase is one transaction or two.** Activation returns `202 Accepted`
  and the order completes minutes later. A checkout that must answer synchronously has to either wait
  on the polling tool or model the order as pending.

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
létjogosultságát". Both branches are built so that a *single* tool call cannot resolve them, which is
what makes them agent-shaped rather than script-shaped:

* **Stuck activation** needs activation's process state (REST) joined to the catalog's stale rows
  (direct JDBC) across an identifier-space mismatch — and then a judgement call between injecting the
  missing callback and cancelling the order, which have different consequences (one is reversible,
  one is not).
* **Unconfirmed batch** needs billing's batch table (SOAP) joined to the outbox files, and then a
  judgement call between `RESEND` (the clearing house may be back) and `RE_DRIVE_ACK` (the money
  demonstrably moved, the confirmation is never coming).

Both judgements depend on context no single query returns. That is the argument for an agent.

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
