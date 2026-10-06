# API reference

Four kinds of interface, one per subsystem plus the ops console. Nothing here is authenticated —
this is a research PoC with no real users, and `decision-log.md` DL-012 records that choice.

Default ports: catalog **8081**, activation **8082**, billing **8083**, ops console **8080**,
Stripe stand-in **12111**.

---

## Subsystem 1 — catalog and subscription registry (REST/JSON, port 8081)

Field names and values are the database's own. `monthlyFeeMinor` is HUF fillér, `dataAllowanceMb` is
megabytes with `-1` meaning unmetered, `activeFlag` is `"Y"`/`"N"`, `statusCode` is a two-character
code, `custNo` is a zero-padded eight-character string, and `msisdn` has **no** leading `+`.

Errors: `{"code": "...", "message": "..."}` with `NOT_FOUND` → 404, `BAD_REQUEST` /
`VALIDATION_FAILED` → 400, `ILLEGAL_TRANSITION` → 409.

### Catalogue

#### `GET /api/v1/plans`
Service 1. Query parameters, all optional: `serviceKind` (`I` or `M`), `planKind` (`BASE` or
`ADDON`), `addonCategory` (`DATA_PACK` or `ROAMING`), `includeWithdrawn` (default `false`).

```
GET /api/v1/plans?serviceKind=M&planKind=BASE
[
  {
    "planCode": "MOB-VOICE-0010", "serviceKind": "M", "displayName": "Mobil Alap 10GB",
    "planKind": "BASE", "addonCategory": null,
    "monthlyFeeMinor": 599000, "currency": "HUF",
    "dataAllowanceMb": 10240, "speedKbps": null, "activeFlag": "Y"
  }
]
```

#### `GET /api/v1/plans/{planCode}`
One plan, including withdrawn ones. 404 if unknown.

### Subscribers

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/api/v1/subscribers/{custNo}` | `custNo` is eight digits, e.g. `00000042` |
| `POST` | `/api/v1/subscribers` | `{fullName, email, msisdn?}` → 201, allocates the next `custNo` |
| `GET` | `/api/v1/subscribers/{custNo}/subscriptions` | 404 if the customer is unknown |

### Subscriptions

The write endpoints are what the activation process drives. They are not meant for customers, but
nothing enforces that beyond convention.

| Method | Path | Body | Effect |
| --- | --- | --- | --- |
| `GET` | `/api/v1/subscriptions/{subId}` | — | one subscription |
| `POST` | `/api/v1/subscriptions` | `{custNo, planCode, msisdn?}` | 201, creates it in `PA` |
| `POST` | `/api/v1/subscriptions/{subId}/addons` | `{addonPlanCode}` | 201, child row in `PA`; parent must be `AC` |
| `POST` | `/api/v1/subscriptions/{subId}/activate` | `{simIccid?, activatedOn?}` | `PA` → `AC`. **Idempotent** |
| `POST` | `/api/v1/subscriptions/{subId}/change-plan` | `{newPlanCode}` | swaps the base plan, recomputes the allowance |
| `POST` | `/api/v1/subscriptions/{subId}/terminate` | — | → `TE`. Idempotent |
| `GET` | `/api/v1/subscriptions/stale-pending` | `?olderThanMinutes=5` | rows stuck in `PA`; the catalog half of failure branch A |

Rules the endpoints enforce: a mobile plan needs an `msisdn`; an add-on cannot be a base
subscription and cannot hang off another add-on; a withdrawn plan cannot be sold; a subscription
cannot move between service kinds; an add-on only grows its parent's effective allowance once the
add-on itself is active; an unmetered base plan stays unmetered.

### Direct JDBC

The ops console reads these tables with plain SQL rather than through the API above — one of the
four interface styles. Schema `catalog`, tables `plan`, `subscriber`, `subscription`. See
`catalog-service/src/main/resources/db/migration/V1__catalog_core.sql`; the column comments there
are the contract.

---

## Subsystem 2 — activation process (REST/JSON, port 8082)

Field names and values are activation's own: `customerRef` is a plain integer, `offerId` is dotted
lowercase (`mob.voice.0010`), `requestedStartDate` is `"yyyyMMdd"`, `msisdn` carries a leading `+`,
`dataAllowanceGb` is a decimal, `monthlyFeeHuf` is HUF major units.

Errors: same JSON shape as the catalog, plus `DOWNSTREAM_FAILURE` → 502 when a neighbouring
subsystem refuses or cannot be reached.

**Order numbers contain slashes** (`ORD/2026/0000001`), which is why none of them appears in a path.
Tomcat rejects an encoded `%2F` by default, and configuring it to decode splits the order number into
three path segments that no longer match the mapping. So the order number travels as a query parameter
on reads and in the body on writes. See `docs/decision-log.md` DL-019.

### `POST /activation/v1/orders`
Services 2 and 4 — one endpoint, three variants of the same BPMN process.

```json
{
  "changeType": "NEW_SUBSCRIPTION",
  "customerRef": 42,
  "offerId": "mob.voice.0010",
  "msisdn": "+36301234567",
  "requestedStartDate": "20260929",
  "targetSubscriptionRef": null,
  "simulateStuck": false,
  "provisioningTimeout": "PT45S"
}
```

| Field | Notes |
| --- | --- |
| `changeType` | `NEW_SUBSCRIPTION`, `PLAN_CHANGE` or `ADDON` |
| `offerId` | dotted lowercase. A catalog-style `MOB-VOICE-0010` is **rejected**, not translated |
| `msisdn` | E.164; a missing `+` is added, anything else non-numeric is a 400 |
| `requestedStartDate` | `yyyyMMdd`; defaults to today. Billing's `2026-09-29` is a 400 |
| `targetSubscriptionRef` | required for `PLAN_CHANGE` and `ADDON`; the catalog's `SUB-yyyy-nnnnnn` |
| `simulateStuck` | ask the provisioning platform to ignore this order — triggers failure branch A |
| `provisioningTimeout` | ISO-8601 duration overriding the boundary timer, e.g. `PT10S` |

Returns **202 Accepted** with the order in `RECEIVED`. It cannot do better: `validateOrder` is
`flowable:async`, so nothing has run yet and `monthlyFeeHuf` is still null.

### `GET /activation/v1/orders?orderNo=ORD/2026/0000001`
Service 3. The reason polling is a service and not a convenience.

```json
{
  "order": { "orderNo": "ORD/2026/0000001", "status": "AWAITING_PROVISIONING",
             "subscriptionRef": "SUB-2026-001000", "monthlyFeeHuf": 5990.00,
             "dataAllowanceGb": 10.000, "simIccid": null, "invoiceRef": null, "...": "..." },
  "processRunning": true,
  "processFinished": false,
  "waitingForCallback": true,
  "currentActivity": "provisioningCallback",
  "terminal": false
}
```

`waitingForCallback` is the useful one: it says whether a live `provisioningCompleted` message
subscription exists, which is what decides whether waiting longer — or injecting the callback by
hand — can still help.

Order statuses: `RECEIVED` → `VALIDATED` → `AWAITING_PROVISIONING` → `PROVISIONED`, with `STUCK`
reachable from `AWAITING_PROVISIONING` (and recoverable back to `PROVISIONED`), plus terminal
`FAILED` and `CANCELLED`.

### `GET /activation/v1/orders?customerRef=42`
Every order for one customer, newest first. The two forms are the same endpoint, selected by which
parameter you pass.

### `POST /activation/v1/callbacks/provisioning`
What the network platform calls. This is the asynchronous seam of the whole system: the HTTP request
that started the order is long gone, and this correlates a message into a process instance that has
been parked ever since.

```json
{ "orderNo": "ORD/2026/0000001", "outcome": "COMPLETED", "simIccid": "89360100123456789" }
```

409 if the order is not waiting for a callback (already finished, cancelled, or never got that far).

### Ops-only: `/activation/v1/ops/**`

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/stuck-orders?olderThanMinutes=1` | orders waiting or reported `STUCK`; each carries `repairableByCallback` |
| `POST` | `/orders/force-provision` | `{orderNo, simIccid?}` — injects the callback the platform never sent. The *same* correlation the platform performs |
| `POST` | `/orders/cancel` | `{orderNo, reason?}` — gives up. **Not reversible**: the process instance and its message subscription are deleted |
| `GET`/`POST` | `/provisioning-platform` | `{callbacksEnabled: bool}` — the global off switch for the simulated platform |

---

## Subsystem 3 — billing and payment (SOAP, batch files, Stripe; port 8083)

### SOAP

* Endpoint `POST http://localhost:8083/ws`, `Content-Type: text/xml`
* WSDL `GET http://localhost:8083/ws/billing.wsdl`
* Namespace `http://mclsaat.hu/legacy/billing/v1`
* Contract `billing-service/src/main/resources/xsd/billing-v1.xsd`

Money is `xs:decimal` in HUF major units. Dates are `xs:string` in `yyyy-MM-dd` — activation's
`yyyyMMdd` is a fault, not a tolerated variant. Errors are SOAP faults whose `faultstring` is one of
`NOT_FOUND`, `BAD_REQUEST`, `ILLEGAL_STATE`, `DOWNSTREAM_FAILURE`.

| Operation | Purpose |
| --- | --- |
| `getInvoices` | Service 5a. Exactly one of `billingAccountNo` or `customerRef`; optional `status`, `subscriptionRef` |
| `createInvoice` | Called by activation. Takes a **net** amount and adds VAT. Idempotent on `requestRef` |
| `startPayment` | Service 5b. Creates a Stripe PaymentIntent, returns its `clientSecret` |
| `payInvoice` | Service 5c. Pays with **no browser**: billing confirms at Stripe, moves the invoice to `SETTLEMENT_PENDING` and cuts the settlement batch. For chat/agent channels |
| `exportPaymentBatch` | Ops. Cuts a settlement batch now instead of waiting for the nightly run |
| `listUnconfirmedBatches` | Ops. Batches sent and never acknowledged — failure branch B |
| `getPaymentBatch` | Ops. One batch plus its invoices |
| `reconcileBatch` | Ops. `RESEND` or `RE_DRIVE_ACK` |

Example:

```xml
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>
  <getInvoicesRequest xmlns="http://mclsaat.hu/legacy/billing/v1">
    <customerRef>42</customerRef>
    <status>OPEN</status>
  </getInvoicesRequest>
</soap:Body></soap:Envelope>
```

```xml
<ns2:invoice>
  <ns2:invoiceNo>2026/INV/000003</ns2:invoiceNo>
  <ns2:billingAccountNo>BA-00042</ns2:billingAccountNo>
  <ns2:customerRef>42</ns2:customerRef>
  <ns2:subscriptionRef>SUB-2026-000002</ns2:subscriptionRef>
  <ns2:periodStart>2026-09-01</ns2:periodStart>
  <ns2:netAmount>4716.54</ns2:netAmount>
  <ns2:vatRate>0.2700</ns2:vatRate>
  <ns2:vatAmount>1273.47</ns2:vatAmount>
  <ns2:grossAmount>5990.01</ns2:grossAmount>
  <ns2:status>OPEN</ns2:status>
</ns2:invoice>
```

That `5990.01` against a catalog price of `5990.00` is not a typo. See
[`semantic-mismatches.md`](semantic-mismatches.md).

`createInvoice` takes `accountName` and `billingEmail` for the case where the `customerRef` has no
billing account yet — billing cannot look a name up in the catalog, because the two share no
database and no identifier space. Without them an unknown `customerRef` is a `BAD_REQUEST` fault.

`startPayment` returns `amountMinor` as an integer count of minor units, because that is what Stripe
wants — the third representation the same figure passes through. The invoice stays `OPEN`: Stripe has
not taken anything yet.

`payInvoice` takes just `invoiceNo` and is the payment path for channels with no browser; `startPayment`
stays as the browser hand-off, where the caller gets the `clientSecret` and Stripe.js does the confirming
(DL-024). Both can be used on one invoice: an intent already created by `startPayment` is reused. The
response carries `invoiceNo`, `paymentRef`, `invoiceStatus`, `changed`, an optional `batchId` and a
`message`. The steps run in this order, and the call returns after the second one's batch is *sent*, not
after it is acknowledged:

| Step | Invoice status | Visible in the response |
| --- | --- | --- |
| Stripe confirms the PaymentIntent (server-side, test card) | `OPEN` | — |
| Billing records it, the same transition as the webhook | `SETTLEMENT_PENDING` | `changed = true` |
| Billing cuts a settlement batch and writes the file | `SETTLEMENT_PENDING` | `batchId` |
| Clearing house acknowledges, the poller applies it (seconds later) | `PAID` | poll `getInvoices` |

Idempotent: an invoice that is already `SETTLEMENT_PENDING` or `PAID` is reported as it is with
`changed = false`, and one stuck in `SETTLEMENT_PENDING` with no batch gets its batch now. A `CANCELLED`
invoice is `ILLEGAL_STATE`; so is a payment Stripe does not complete. If the export itself fails the
Stripe payment is *not* rolled back — the invoice waits in `SETTLEMENT_PENDING` and `message` says so.
With the clearing house silenced (failure branch B) the call still returns `SETTLEMENT_PENDING` plus a
`batchId`, and the batch stays `SENT`. Note that `exportBatch` sweeps every unbatched
`SETTLEMENT_PENDING` invoice, so one call can settle other customers' payments too.

Invoice statuses: `OPEN` → `SETTLEMENT_PENDING` → `PAID`, plus `CANCELLED`.
Batch statuses: `OPEN` → `SENT` → `ACKED`, plus `FAILED`.

### Stripe webhook (REST/JSON)

`POST /webhook/stripe` — the one REST endpoint in an otherwise SOAP subsystem, because that is what
Stripe speaks.

```json
{ "id": "evt_1", "type": "payment_intent.succeeded",
  "data": { "object": { "id": "pi_...", "metadata": { "invoice_no": "2026/INV/000003" } } } }
```

Moves the invoice `OPEN` → `SETTLEMENT_PENDING`, **not** to `PAID`: the money is at Stripe but this
business does not consider it posted until the clearing house acknowledges the settlement batch.
Idempotent, because Stripe retries. Other event types are acknowledged and ignored. Resolves the
invoice from `metadata.invoice_no` or, failing that, from the PaymentIntent id.

Signature verification is deliberately absent — there is no real webhook signing secret, and
pretending to verify one would be theatre. With a real key this is where
`Webhook.constructEvent(...)` goes.

### Batch file exchange

Directories (configurable, shared volume under compose):
`batch-exchange/outbox`, `batch-exchange/inbox`, `batch-exchange/archive`.

Outbound `PMT-<batchId>.txt`, US-ASCII, one record per line, **no delimiters** — column offsets only.
Money is an integer with an implied two decimals.

```
      off len field                          off len field
HDR     0   3 "HDR"                    DTL     0   3 "DTL"
        3  20 batchId  (left, space)            3  20 invoiceNo (left, space)
       23   8 created yyyyMMdd                 23  12 billingAccountNo (left, space)
       31   6 created HHmmss                   35  15 amountMinor (right, zero)
       37   6 itemCount   (right, zero)        50  32 paymentRef (left, space)
       43  15 totalMinor  (right, zero)        82   3 currency
       58   3 currency                       = 85 bytes
      = 61 bytes
TRL     0   3 "TRL"
        3   6 itemCount  (right, zero)
        9  15 totalMinor (right, zero)
      = 24 bytes
```

```
HDRBATCH-20260929-001  20260929174413000001000000001299000HUF
DTL2026/INV/000002     BA-00042    000000001299000pi_sim_00000000001              HUF
TRL000001000000001299000
```

Inbound `ACK-<batchId>.txt`:

```
      off len field                          off len field
ACK     0   3 "ACK"                    RES     0   3 "RES"
        3  20 batchId                          3  20 invoiceNo
       23   8 ACCEPTED|REJECTED                23   8 ACCEPTED|REJECTED
       31   8 acked yyyyMMdd                   31   4 reasonCode
       39   6 acked HHmmss                   = 35 bytes
       45   6 acceptedCount
       51   6 rejectedCount
      = 57 bytes
```

```
ACKBATCH-20260929-001  ACCEPTED20260929174413000001000000
RES2026/INV/000002     ACCEPTED0000
```

The trailer is checked on read: a count or total that disagrees with the details is a `BAD_REQUEST`,
and a value that does not fit its column is refused rather than truncated. A background poller sweeps
the inbox every `billing.batch.poll-interval-ms` (default 2000), applies each acknowledgement and
moves the file to `archive/`; a file it cannot parse goes to `archive/rejected/` so one bad file does
not wedge the sweep.

### Clearing-house simulator control

`GET`/`POST /sim/clearing-house/config` with `{"ackEnabled": false}`. This is the fault injection
point for failure branch B. Under `/sim/` rather than in the business API because it is not part of
the business.

---

## Ops console (REST/JSON, port 8080)

Internal only. Every endpoint reaches across subsystem boundaries, and each one uses a different
protocol to do it — which is the point of the module existing.

| Method | Path | Talks to |
| --- | --- | --- |
| `GET` | `/ops/v1/diagnostics/stuck-activations` | activation over **REST** + catalog over **direct JDBC** |
| `GET` | `/ops/v1/diagnostics/unconfirmed-batches` | billing over **SOAP** + the outbox **files** |
| `GET` | `/ops/v1/diagnostics/overview` | all three |
| `POST` | `/ops/v1/remediation/activation/force-provision` | activation over REST — `{orderNo, simIccid?}` |
| `POST` | `/ops/v1/remediation/activation/cancel` | activation over REST — `{orderNo, reason?}`. **Not reversible** |
| `POST` | `/ops/v1/remediation/billing/reconcile` | billing over SOAP — `{batchId, mode?}`, mode defaults to `RE_DRIVE_ACK` |
| `POST` | `/ops/v1/remediation/billing/export-batch` | billing over SOAP — cuts a settlement batch now |
| `POST` | `/ops/v1/remediation/fault-injection/provisioning-callbacks` | activation over REST — `{callbacksEnabled}`, the global off switch |

`stuck-activations` is the interesting one: neither source alone is enough. Activation knows its
process instance is parked; the catalog knows it has a subscription that never went live. The ops
console joins them by hand, across an identifier-space mismatch, because nothing else in the system
can.

Each finding carries both halves side by side, plus a `diagnosis` in prose and a `suggestedRemedy`,
and a `category` that decides which remedy applies:

| Category | What it means | Remedy |
| --- | --- | --- |
| `STUCK_PROCESS` | a live process instance is parked and the catalog row is pending | `force-provision`, if `repairableByCallback` |
| `ORPHANED_STUCK_ORDER` | the order was reported stuck but its process instance is gone | `cancel` only |
| `ORPHANED_PENDING_SUBSCRIPTION` | a stale catalog row with no activation order behind it (the seeded `SUB-2026-000009`) | terminate in the catalog and re-order |

`unconfirmed-batches` likewise returns the settlement file's records verbatim in
`settlementFileLines`, and `settlementFilePresent` is what decides whether a remedy exists at all: you
cannot rebuild an acknowledgement from a file that was never written, and the console says so rather
than guessing. Attempting it returns **409** `BILLING_ILLEGAL_STATE`.

Errors: one shape — `{"code", "message"}` — regardless of which of the three unrelated downstream
error models produced it. `BILLING_NOT_FOUND` → 404, `BILLING_ILLEGAL_STATE` → 409,
`ACTIVATION_FAILURE` → 502 or 503, `CATALOG_JDBC_FAILURE` → 503.

---

## Stripe stand-in (REST/JSON, port 12111)

Compose and the local path both run the `stripe-sim` module. The official `stripe/stripe-mock` image is
an opt-in compose profile (`official-stripe-mock`): it can create PaymentIntents but cannot complete one,
so `payInvoice` cannot close against it (DL-024). Same port, same SDK either way. The official image is
pinned to `v0.206.0`, there and in the billing payment tests, because it tracks the live Stripe OpenAPI
spec and `:latest` has already changed the API under an unchanged checkout (DL-027).

`stripe-sim` implements `POST /v1/payment_intents`, `GET /v1/payment_intents/{id}` and
`POST /v1/payment_intents/{id}/confirm`, in Stripe's response shape. Unlike `stripe-mock` it
remembers what it created, so a retrieve after a confirm actually reports `succeeded`.

The API key must be alphanumeric after the `sk_test_` prefix — `stripe-mock` validates the shape and
rejects, for example, `sk_test_mclsaat_local`. To point at real Stripe, set `STRIPE_API_BASE` to
`https://api.stripe.com` and `STRIPE_API_KEY` to an `sk_test_...` key. Nothing else changes.
