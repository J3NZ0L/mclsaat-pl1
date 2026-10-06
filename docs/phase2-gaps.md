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
`phase2-seams.md` marks the section 2 items as open. As of 2026-10-06, items 1.1, 1.2 and 1.3 are
addressed, item 1.4 is decided (DL-025, no code change) and item 2.1 is decided (DL-026, no code
change); item 2.2 (tool count and granularity) is deliberately not decided in phase 1. It is the
**first task of phase 2**, to be settled with the user before any MCP tool surface is defined.

---

## 1. Blocks phase 2

### 1.1 The shared layer cannot place an order — **addressed 2026-10-05**

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
| 5 | Invoice query + pay | yes (`startPayment` and `payInvoice`, see 1.3) |
| 6 | Detect and resolve | yes |

Services 2 and 4 are the core MCP tools of the customer persona and the core call of the UCP adapter.

### 1.2 A new customer cannot be onboarded — **addressed 2026-10-05**

`validateOrder` rejects an unknown `customerRef`
([`ValidateOrderDelegate.java`](../activation-service/src/main/java/hu/mclsaat/legacy/activation/process/ValidateOrderDelegate.java)),
so a subscriber must already exist in the catalog. `subsystem-clients` now wraps
`POST /api/v1/subscribers` in `CatalogSubscriberRestClient`, so a UCP adapter can onboard first and
then place the activation order.

Billing was never the problem: it opens a billing account lazily on the first invoice
(`InvoiceService.openAccount`). The catalog side was the only missing half, and it is now covered.

### 1.3 The payment loop is closed by the demo script, not by the system — **addressed 2026-10-06**

> **Addressed.** Billing now has a browserless `payInvoice` SOAP operation that confirms the
> PaymentIntent at Stripe itself, moves the invoice to `SETTLEMENT_PENDING` through the same
> transition the webhook uses, and cuts the settlement batch. `scripts/demo.sh` no longer confirms
> anything at Stripe or posts a webhook; `BillingSoapClient.payInvoice` exposes it to the shared layer.
> `startPayment` and `/webhook/stripe` are unchanged, and the invoice still reaches `PAID` only when the
> clearing house acknowledges the batch. See DL-024 and [`api-reference.md`](api-reference.md). The
> original finding follows, for the record. Gap 1.4 (activate-then-bill) is untouched.

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
cancel, a final charge decline or expiry. Invoice timing and the BPMN do not change. Also decided there:

* the checkout quotes the **billing-derived** total (5990.01 / 17989.99 for the two DL-014 plans), so
  quote, invoice and charge agree;
* UCP and chat share one lifecycle; only the trigger differs (UCP charges automatically, chat pays
  explicitly), and both end in `payInvoice` (1.3);
* a declined charge cancels the checkout and surfaces to ops, with no automatic compensation.

The full answers, the rejected options (deferred Payment Term, authorize-then-capture) and the
acceptance criteria are in [`decision-log.md`](decision-log.md) DL-025. Still to build from this, all
additive: an optional payment-method element on `payInvoice` so the UCP instrument can reach the charge
(both XSD copies), a distinguishable decline fault in billing, a fault-injectable decline in
`stripe-sim`, and an ops diagnostic rule for provisioned-but-unpaid orders. That rule now has a home:
it becomes a third finding type in `LandscapeDiagnostics` behind the ops server's diagnose tool
(DL-026), and needs a bulk source that neither billing nor activation offers today. It detects a declined
UCP charge, so it is deferred to phase 3 with the UCP adapter.

---

## 2. Decisions the phase-1 docs took ahead of the design

### 2.1 Where the diagnostic tool lives — resolved 2026-10-06 (DL-026)

> **Resolved: a fourth, ops-only MCP server over `LandscapeDiagnostics`, carrying both detection and
> remediation; MCP#1–#3 carry customer tools only.** The diagnosis is not one of the measured tokenomics
> transactions, which removed the reason for leaving the join to the agent. It also turned out that the
> design's placement on MCP#2/#3 cannot see the catalog half of failure branch A (the seeded
> `SUB-2026-000009`), and that the orphan remedy ("terminate in the catalog") has no tool today. The
> customer persona never connects to the ops server; `ops-console` is unchanged. The answers, the
> rejected options, the acceptance criteria and the revisit trigger (reopen it if the diagnosis is ever
> measured) are in [`decision-log.md`](decision-log.md) DL-026. The original finding follows, for the
> record.

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

### 2.2 Tool count and granularity — deferred to the start of phase 2

> **Not a phase-1 blocker, and not to be skipped.** The user's design defers the final granularity to the
> tokenomics phase, and deciding it properly needs research that belongs to phase 2: industry guidance
> and example agent architectures, then a comparison on this system. So it is the **first task of
> phase 2**: before any MCP tool surface is defined (tool names, schemas, which tools a server lists),
> run a research-then-interview exercise with the user and record the answer as the next free DL entry.
> Do not start building tools on a silent default.
>
> *Proposed starting point, not confirmed by the user:* one tool per service for the customer side, as
> the design says; on the ops server, diagnose plus separate remediation tools. The reason for the last
> part is mechanical, not a preference: persona allowlists work per tool name and the MCP
> `destructiveHint` is set per tool, so one `remediate` tool mixing reversible and irreversible actions
> could not be split by permission. Counts under that proposal: customer side 6 (7 with `startPayment`),
> ops side 2 to 6 depending on how remediation is cut, so 8 to 13 for the ops persona, which also
> connects to the customer servers. Whether granularity is itself a variable the tokenomics experiment
> should compare (two cuts behind a switch) is part of that exercise.
>
> Evidence already gathered for it is in [`decision-log.md`](decision-log.md) DL-026, "Research basis".
> It is thin at this size: the published numbers are for libraries of 50 or more tools.

The design says "kb. 6+1 tool", one tool per service, and explicitly defers the final granularity to
the tokenomics phase. `phase2-seams.md` §2 proposes eight tools, and its `ops.remediate` covers three
to four distinct actions (force-provision, cancel, reconcile with `RESEND`, reconcile with
`RE_DRIVE_ACK`) — so ten or eleven in practice.

`phase2-seams.md` §2 now presents its table as one candidate, not the decision.

Interaction with 2.1 (DL-026): the ops tools now sit on one server, MCP#4, so "6+1" is no longer a
count of one tool per service plus a separate diagnostic; whether MCP#4's remediation is one
`remediate` tool or several is still this item's question. Not decided there.

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
them. `INITIAL_DESIGN.md` is read-only (see [`AGENTS.md`](../AGENTS.md) §2) and is not to be edited to close
this gap.

*Corrected:* `phase2-seams.md` §0 now records the full design, and the rest of that page is annotated
where it departs from it.
