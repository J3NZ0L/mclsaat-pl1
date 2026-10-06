# Phase-2 gaps

A review of the phase-1 implementation against the user's high-level design for the later phases
(agent core with two personas, one MCP server per subsystem, a UCP adapter that calls the shared
client layer directly, a polling-based diagnostic tool for the ops persona). Reviewed 2026-09-30.

This page records only what does **not** line up. What does — the canonical layer, the path-based
customer/ops boundary, polling-only failure detection, both failure branches — is described in
[`phase2-seams.md`](phase2-seams.md).

Findings are in three groups: gaps that block phase 2, decisions the phase-1 docs took ahead of the
design, and places where the docs disagreed with the code.

**Status.** Section 3 has been corrected in `architecture.md` and `phase2-seams.md`, and
`phase2-seams.md` now marks the section 2 items and 1.3–1.4 as open. As of 2026-10-05, items 1.1 and
1.2 are addressed in `subsystem-clients`; section 2 decisions and 1.3–1.4 are still open.

---

## 1. Blocks phase 2

### 1.1 The shared layer cannot place an order

`ActivationRestClient` in `subsystem-clients` can now place orders through
`POST /activation/v1/orders` via `startOrder` plus typed helpers for new subscriptions, plan changes
and add-ons
([`ActivationRestClient.java`](../subsystem-clients/src/main/java/hu/mclsaat/legacy/clients/protocol/ActivationRestClient.java)).

Coverage of the six services through `subsystem-clients`:

| # | Service | Covered |
| - | --- | --- |
| 1 | Browse plans | yes (`CatalogJdbcClient`, direct SQL only — see 3.1) |
| 2 | Start subscription | yes |
| 3 | Poll order status | yes |
| 4 | Plan change / add-on | yes |
| 5 | Invoice query + start payment | yes |
| 6 | Detect and resolve | yes |

Services 2 and 4 are the core MCP tools of the customer persona and the core call of the UCP adapter.

### 1.2 A new customer cannot be onboarded

`validateOrder` rejects an unknown `customerRef`
([`ValidateOrderDelegate.java`](../activation-service/src/main/java/hu/mclsaat/legacy/activation/process/ValidateOrderDelegate.java)),
so a subscriber must already exist in the catalog. `subsystem-clients` now wraps
`POST /api/v1/subscribers` in `CatalogSubscriberRestClient`, so a UCP adapter can onboard first and
then place the activation order.

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

### 1.4 Invoice timing versus checkout — resolved 2026-10-06 (DL-025)

`issueInvoice` runs **after** provisioning completes, so no invoice number exists to pay until
activation has finished — minutes after the order is placed. This is postpaid, activate-then-bill,
which is realistic for a telco.

[`phase2-seams.md`](phase2-seams.md) §4 calls `startPayment` "the UCP checkout hand-off" and raises
only whether a purchase is one transaction or two. The design itself names two hook points:
service 2 ("ide köt be az UCP checkout") and subsystem 3 ("itt köt be az UCP checkout").

**Resolved: keep activate-then-bill, and hold the UCP checkout in `complete_in_progress`.** Checked
against UCP release `v2026-08-25`: Complete Checkout requires a payment instrument, the Order has no
payment-pending state, and the checkout has an asynchronous `complete_in_progress` status for exactly
this case. The checkout becomes `completed` once the order is provisioned and the invoice has reached
`SETTLEMENT_PENDING` (the card charge succeeded; `PAID` is not awaited), and `canceled` on an ops
cancel, a final charge decline or expiry. No legacy code changes. Also decided there:

* the checkout quotes the **billing-derived** total (5990.01 / 17989.99 for the two DL-014 plans), so
  quote, invoice and charge agree;
* UCP and chat share one lifecycle; only the trigger differs (UCP charges automatically, chat pays
  explicitly), and both end in `payInvoice` (1.3);
* a declined charge cancels the checkout and surfaces to ops, with no automatic compensation.

The full answers, the rejected options (deferred Payment Term, authorize-then-capture) and the
acceptance criteria are in [`decision-log.md`](decision-log.md) DL-025. Still open from this: an optional
payment-method element on `payInvoice` so the UCP instrument can reach the charge (additive, both XSD
copies), and a fault-injectable decline in `stripe-sim`.

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

`phase2-seams.md` §2 now presents its table as one candidate, not the decision.

---

## 3. Doc drift (corrected 2026-09-30)

### 3.1 "Four clients", five listed, one of them absent

[`architecture.md`](architecture.md) and [`phase2-seams.md`](phase2-seams.md) §1 describe
`subsystem-clients` as holding four protocol clients and then list five, including a
`CatalogRestClient`. No such class exists in `subsystem-clients`; the only `CatalogRestClient` is
activation's own, deliberately unshared one (DL-009).

Through the shared layer, browsing plans is therefore direct SQL only. That is consistent with
subsystem 1's "direct DB access" role in the design, but the docs were wrong about it.

*Corrected:* both documents now list the four clients that exist and say the catalog is reached by
direct SQL only through this layer.

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
them. `INITIAL_DESIGN.md` is read-only (see `CLAUDE.md` §2) and is not to be edited to close this gap.

*Corrected:* `phase2-seams.md` §0 now records the full design, and the rest of that page is annotated
where it departs from it.
