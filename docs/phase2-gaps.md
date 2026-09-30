# Phase-2 gaps

A review of the phase-1 implementation against the user's high-level design for the later phases
(agent core with two personas, one MCP server per subsystem, a UCP adapter that calls the shared
client layer directly, a polling-based diagnostic tool for the ops persona). Reviewed 2026-09-30.

This page records only what does **not** line up. What does — the canonical layer, the path-based
customer/ops boundary, polling-only failure detection, both failure branches — is described in
[`phase2-seams.md`](phase2-seams.md).

Findings are in three groups: gaps that block phase 2, decisions the phase-1 docs took ahead of the
design, and places where the docs disagree with the code.

---

## 1. Blocks phase 2

### 1.1 The shared layer cannot place an order

`ActivationRestClient` in `subsystem-clients` can find, list, force-provision and cancel orders, but
has no method for `POST /activation/v1/orders`
([`ActivationRestClient.java`](../subsystem-clients/src/main/java/hu/mclsaat/legacy/clients/protocol/ActivationRestClient.java)).

Coverage of the six services through `subsystem-clients`:

| # | Service | Covered |
| - | --- | --- |
| 1 | Browse plans | yes (`CatalogJdbcClient`, direct SQL only — see 3.1) |
| 2 | Start subscription | **no** |
| 3 | Poll order status | yes |
| 4 | Plan change / add-on | **no** |
| 5 | Invoice query + start payment | yes |
| 6 | Detect and resolve | yes |

Services 2 and 4 are the core MCP tools of the customer persona and the core call of the UCP adapter.

### 1.2 A new customer cannot be onboarded

`validateOrder` rejects an unknown `customerRef`
([`ValidateOrderDelegate.java`](../activation-service/src/main/java/hu/mclsaat/legacy/activation/process/ValidateOrderDelegate.java)),
so a subscriber must already exist in the catalog. The catalog has `POST /api/v1/subscribers`, but
nothing in `subsystem-clients` wraps it. A buyer arriving over UCP is new by definition.

Billing is not the problem: it opens a billing account lazily on the first invoice
(`InvoiceService.openAccount`). Only the catalog side is missing.

### 1.3 The payment loop is closed by the demo script, not by the system

[`scripts/demo.sh`](../scripts/demo.sh) confirms the PaymentIntent at the Stripe stand-in itself and
then posts the `payment_intent.succeeded` webhook to billing itself. Nothing in `stripe-sim` emits
webhooks, and `stripe/stripe-mock` does not either. Settlement batch export is off by default
(DL-018) and is triggered through the ops console.

Consequence: an agent-driven purchase, from either channel, stalls at `OPEN` — or at
`SETTLEMENT_PENDING` if someone posts the webhook — and never reaches `PAID` without an ops action.

Additionally, `startPayment` returns a `clientSecret`, which assumes a browser running Stripe.js to
confirm the payment. The chat channel has no browser.

### 1.4 Invoice timing versus checkout (a decision, not a bug)

`issueInvoice` runs **after** provisioning completes, so no invoice number exists to pay until
activation has finished — minutes after the order is placed. This is postpaid, activate-then-bill,
which is realistic for a telco.

[`phase2-seams.md`](phase2-seams.md) §4 calls `startPayment` "the UCP checkout hand-off" and raises
only whether a purchase is one transaction or two. The design itself names two hook points:
service 2 ("ide köt be az UCP checkout") and subsystem 3 ("itt köt be az UCP checkout").

Open decision: keep activate-then-bill and model the UCP checkout around a pending order, or add a
pay-at-checkout path. Verify against UCP's checkout-completion and payment model before choosing;
this review did not check the spec.

---

## 2. Decisions the phase-1 docs took ahead of the design

### 2.1 Where the diagnostic tool lives

The design places the diagnostic tool on MCP#2 and MCP#3 — the per-subsystem servers for activation
and billing — reachable only by the ops persona.

The implementation does the cross-subsystem join inside one library class,
[`LandscapeDiagnostics`](../subsystem-clients/src/main/java/hu/mclsaat/legacy/clients/LandscapeDiagnostics.java)
(catalog JDBC + activation REST for branch A; billing SOAP + outbox files for branch B), and
`phase2-seams.md` proposes `ops.diagnose` / `ops.remediate` tools — effectively a fourth, ops-only
server.

Why it matters:

* **The "agent-shaped" argument.** If one tool call performs the join, the agent contributes only the
  judgement step (force-provision vs. cancel, `RESEND` vs. `RE_DRIVE_ACK`). `phase2-seams.md` §6
  argues the failure branches are agent-shaped because no single call resolves them; that holds for
  remediation but not for detection.
* **The tokenomics comparison.** Per-subsystem diagnosis that the agent combines gives the
  measurement more to measure than a pre-joined report does.

Open decision: per-subsystem diagnostic tools (as designed), or a cross-subsystem ops server over
`LandscapeDiagnostics` (as the docs suggest).

### 2.2 Tool count and granularity

The design says "kb. 6+1 tool", one tool per service, and explicitly defers the final granularity to
the tokenomics phase. `phase2-seams.md` §2 proposes eight tools, and its `ops.remediate` covers three
to four distinct actions (force-provision, cancel, reconcile with `RESEND`, reconcile with
`RE_DRIVE_ACK`) — so ten or eleven in practice.

The seams doc should be read as one candidate, not as the decision.

---

## 3. Doc drift

### 3.1 "Four clients", five listed, one of them absent

[`architecture.md`](architecture.md) and [`phase2-seams.md`](phase2-seams.md) §1 describe
`subsystem-clients` as holding four protocol clients and then list five, including a
`CatalogRestClient`. No such class exists in `subsystem-clients`; the only `CatalogRestClient` is
activation's own, deliberately unshared one (DL-009).

Through the shared layer, browsing plans is therefore direct SQL only. That is consistent with
subsystem 1's "direct DB access" role in the design, but the docs are wrong about it.

### 3.2 The agent-architecture half of the design never reached the repository

The repository's `INITIAL_DESIGN.md` contains only the bottom layer of the architecture sketch
(subsystem-client layer and the three subsystems) and one of the key decisions (6+1 tools). Not in
the repository:

* the agent core with two personas (customer / ops) differing only in system prompt and tool
  allowlist;
* the UCP adapter as a separate entry point that is **not** an MCP client and calls the shared layer
  directly;
* the placement of the diagnostic tool on MCP#2/#3, ops persona only;
* "two entry points, not two agents".

Where phase 1 agrees with these, it agrees by coincidence rather than because it was built against
them. Phase-2 work should take the full design from the user, not from `INITIAL_DESIGN.md`.
`INITIAL_DESIGN.md` is read-only (see `CLAUDE.md` §2) and is not to be edited to close this gap.
