# Semantic mismatches

This is the core document of the whole project. The `PROJECT_DESC_HUN.md` "Saját pontosítások"
section is explicit that heterogeneity — and specifically *semantic* heterogeneity, not just
protocol variety — is the critical legacy property being modelled. This page is the inventory,
and every row in it is backed by code and by a test.

Protocol differences are listed separately in [`architecture.md`](architecture.md); on their own
they would not be enough.

## The master table

One customer, one product, one price, one date, one phone number — written down differently by
each of the three subsystems.

| Concept | Subsystem 1 — catalog | Subsystem 2 — activation | Subsystem 3 — billing |
| --- | --- | --- | --- |
| **Customer identity** | `cust_no CHAR(8)`, zero-padded string `"00000042"` | `customerRef INTEGER`, `42` | `customer_ref INTEGER` `42` **and** `ba_no VARCHAR(12)` `"BA-00042"` |
| **Product identity** | `plan_code` `"MOB-VOICE-0010"` (upper, hyphens) | `offer_id` `"mob.voice.0010"` (lower, dots) | not modelled — invoices carry an amount, not a product |
| **Subscription identity** | `sub_id` `"SUB-2026-000001"` | `subscription_ref`, opaque external text | `subscription_ref`, opaque external text |
| **Order / document identity** | — | `order_no` `"ORD/2026/0000001"` | `invoice_no` `"2026/INV/000001"`, `batch_id` `"BATCH-20260929-001"` |
| **Data allowance** | `data_allowance_mb INTEGER`, **megabytes**, `-1` = unmetered | `data_allowance_gb NUMERIC(10,3)`, **gigabytes**, `NULL` = unmetered | not modelled |
| **Money** | `monthly_fee_minor BIGINT`, **HUF fillér**, **gross** | `monthly_fee_huf NUMERIC(12,2)`, **HUF major units**, **gross** | `net_amount`/`vat_amount`/`gross_amount NUMERIC(12,2)`, **net-led**; Stripe then wants **integer minor units**; the batch file wants an **implied-two-decimals integer** |
| **Dates** | `DATE` columns | `CHAR(8)` strings, `"20260929"` | `xs:string` on the wire, `"2026-09-29"`; `DATE` columns underneath |
| **Timestamps** | ISO-8601 in JSON | ISO-8601 in JSON | `xs:string` ISO-8601 in SOAP |
| **Phone number** | `msisdn` `"36301234567"`, **no** leading `+` | `msisdn` `"+36301234567"`, **with** the `+` | not modelled |
| **Boolean** | `active_flag CHAR(1)` `'Y'`/`'N'` | `BOOLEAN` | `BOOLEAN` |
| **Service kind** | `service_kind CHAR(1)` `'I'`/`'M'` | inferred from the offer id prefix | not modelled |
| **Lifecycle status** | `status_code CHAR(2)`: `NW PA AC SU TE` | spelled out: `RECEIVED VALIDATED AWAITING_PROVISIONING STUCK PROVISIONED FAILED CANCELLED` | spelled out, different words: `OPEN SETTLEMENT_PENDING PAID CANCELLED` |
| **Error reporting** | JSON body + HTTP status | JSON body + HTTP status | SOAP fault with a coded `faultstring` |

## Why each row is a real problem, not decoration

### Customer identity: three names for one person
Nothing in any database ties `"00000042"` to `42` to `"BA-00042"`. The correspondence is
`Integer.parseInt` in one direction and `"%08d"` in the other, and it lives in
`ActivationSemantics.toCatalogCustNo` / `fromCatalogCustNo` — in code, in this document, and
nowhere else. Billing's `billing_account` table is where `42` becomes `"BA-00042"`, and it is a
lookup, not a formatting rule: `BA-00101` happens to belong to `customer_ref = 101` only because
the seed data says so.

Consequences that actually happen:
* `GetInvoices` with `billingAccountNo = "00000042"` returns a `NOT_FOUND` SOAP fault, not an
  empty list. Pinned by `BillingSoapApiIT.theCatalogsPaddedCustomerNumberIsNotAcceptedHere`.
* A `customerRef` above 99 999 999 cannot be expressed in the catalog at all, so
  `toCatalogCustNo` refuses it rather than truncating.

### Product identity: mechanical, and therefore easy to get subtly wrong
`"MOB-VOICE-0010"` ↔ `"mob.voice.0010"` is uppercase/lowercase plus `-`/`.`. The rule round-trips
for all nine seeded plans (`ActivationSemanticsTest.theProductNamingRoundTripsBothWaysForEverySeededPlan`),
which is exactly what makes it dangerous: it looks inferable, so nobody writes it down, and then a
plan code containing a dot arrives.

Activation refuses a catalog-style code outright rather than guessing:
`POST /activation/v1/orders` with `offerId = "MOB-VOICE-0010"` is a 400.

### Data allowance: 1024, not 1000
The catalog counts megabytes as an integer; activation counts gigabytes to three decimals. The
factor is 1024.

```
catalog 10240 MB  ->  activation 10.000 GB
catalog  5120 MB  ->  activation  5.000 GB
catalog  3072 MB  ->  activation  3.000 GB
catalog  1000 MB  ->  activation  0.977 GB   <-- an agent assuming 1000 would be wrong here
activation 1.000 GB -> catalog 1024 MB       <-- ...and short by 24 MB here
```

And the sentinel: the catalog writes `-1` for an unmetered plan, activation writes nothing at all.
Converting `-1` arithmetically would sell someone a negative allowance; converting `NULL` to `0`
would sell them nothing. `ActivationSemantics.toGigabytes` / `toMegabytes` handle the sentinel
explicitly and `ActivationSemanticsTest` pins both directions.

The catalog also computes an *effective* allowance: the base plan plus every **active** add-on,
with an unmetered base staying unmetered no matter what is bolted on. A pending add-on contributes
nothing. That rule lives in `SubscriptionRegistry.recomputeEffectiveAllowance` and is invisible
from the other two subsystems.

### Money: the same figure in four representations, and it does not survive the trip

```
catalog            monthly_fee_minor BIGINT        599000      gross, HUF fillér
  |  /100
activation         monthly_fee_huf NUMERIC(12,2)   5990.00     gross, HUF major units
  |  /1.27, HALF_UP        <-- billing invoices on NET, the catalog quotes GROSS
billing            net_amount  NUMERIC(12,2)       4716.54
                   vat_amount  NUMERIC(12,2)       1273.47     = round(4716.54 * 0.27)
                   gross_amount                    5990.01     <-- one fillér more than quoted
  |  *100
Stripe             amount (integer minor units)    599001
batch file         DTL amount, implied 2 decimals  000000000599001
```

This is not a bug to be fixed. It is the measurable cost of a gross-priced catalog meeting a
net-invoicing billing system, and it is pinned across the whole seeded catalog by
`VatCalculatorTest.theRoundTripErrorAcrossTheWholeSeededCatalogIsExactlyThis`:

| Plan | Catalog gross | Net | VAT | Gross again | Delta |
| --- | --- | --- | --- | --- | --- |
| `ADDON-DATA-0005` | 1 490.00 | 1 173.23 | 316.77 | 1 490.00 | 0.00 |
| `ADDON-DATA-0010` | 2 490.00 | 1 960.63 | 529.37 | 2 490.00 | 0.00 |
| `ADDON-ROAM-EU01` | 3 990.00 | 3 141.73 | 848.27 | 3 990.00 | 0.00 |
| `INET-ADSL-0030` | 6 990.00 | 5 503.94 | 1 486.06 | 6 990.00 | 0.00 |
| `INET-FIB-0500` | 12 990.00 | 10 228.35 | 2 761.65 | 12 990.00 | 0.00 |
| **`INET-FIB-1000`** | 17 990.00 | 14 165.35 | 3 824.64 | **17 989.99** | **−0.01** |
| `MOB-VOICE-0002` | 3 490.00 | 2 748.03 | 741.97 | 3 490.00 | 0.00 |
| **`MOB-VOICE-0010`** | 5 990.00 | 4 716.54 | 1 273.47 | **5 990.01** | **+0.01** |
| `MOB-VOICE-0050` | 9 990.00 | 7 866.14 | 2 123.86 | 9 990.00 | 0.00 |

Two of nine plans bill the customer a different amount from the one the catalog quoted them. Whose
problem is that — the catalog's, activation's, or billing's? Nobody in this system owns it, which
is the most realistic thing about it. Phase 2 has to decide.

Note also that `vat_rate` is frozen **onto each invoice** (`NUMERIC(5,4)`), because a historical
invoice must keep the rate that applied when it was issued. So the rate is not one global constant
either.

### Dates: three formats, one of which is not a date type at all
Activation stores `requested_start_date` as `CHAR(8)`. Billing's SOAP contract types every date as
`xs:string` and documents `yyyy-MM-dd` in a comment. The catalog uses real `DATE` columns.

Each boundary crossing therefore reformats, and each side rejects the other's format rather than
accepting it:
* `CreateInvoice` with `periodStart = "20261001"` → `BAD_REQUEST` SOAP fault
  (`BillingSoapApiIT.aMisformattedDateIsAFaultBecauseBillingOnlySpeaksYyyyMmDd`).
* `POST /orders` with `requestedStartDate = "2026-09-29"` → 400
  (`SubscriptionActivationProcessIT.badInputIsRejectedSynchronouslyBeforeAnOrderExists`).

`ActivationSemantics.toBillingDate` goes straight from one string format to the other, via a real
`LocalDate` in the middle so a malformed value fails loudly instead of being reformatted into
nonsense.

### Phone numbers: one character, silently significant
The catalog's `ck_subscriber_msisdn` check constraint is `^[0-9]{8,15}$` — a leading `+` is a
constraint violation, not a normalisation. Activation's is `^\+[0-9]{8,15}$` — the `+` is
mandatory. So the same number cannot be stored in both without conversion, and a caller that
forwards one to the other gets a database error rather than a wrong answer. `toCatalogMsisdn` and
`toActivationMsisdn` are both idempotent, because callers cannot be trusted to know which side of
the boundary they are on.

### Lifecycle status: not the same shape, so the mapping is lossy
`ActivationSemantics.fromCatalogStatusCode` maps `PA → AWAITING_PROVISIONING` and
`AC → PROVISIONED`. But:
* the catalog has **no** state corresponding to activation's `STUCK` — a stuck order's subscription
  just sits in `PA`, indistinguishable from one that is about to succeed. *This is failure branch A.*
* activation has **no** state corresponding to the catalog's `SU` (suspended), so the mapping
  invents `SUSPENDED_IN_CATALOG`, which no activation status constraint accepts.
* billing's vocabulary overlaps neither: `SETTLEMENT_PENDING` describes money that Stripe has taken
  and the clearing house has not confirmed, a condition the other two subsystems cannot express at
  all. *This is failure branch B.*

Both failure branches are, at bottom, states that exist in one subsystem and are unrepresentable in
its neighbour.

### Error reporting: status codes versus faults
The catalog and activation answer with `{"code": "...", "message": "..."}` and an HTTP status.
Billing answers with a SOAP fault whose `faultstring` carries the code (`NOT_FOUND`, `BAD_REQUEST`,
`ILLEGAL_STATE`, `DOWNSTREAM_FAILURE`) and whose detail is, by SOAP convention, elsewhere. A caller
spanning both has two unrelated error models to normalise — and `CatalogClientException` /
`BillingClientException` both have to decide *retryability* from that, because Flowable's async
executor will retry whatever it is told to.

## Where the translation lives today

| Translation | Owner | Tests |
| --- | --- | --- |
| catalog ↔ activation, all of it | `ActivationSemantics` in `activation-service` | `ActivationSemanticsTest` (14 tests) |
| activation → billing dates and amounts | `BillingSoapClient` + `ActivationSemantics` | `SubscriptionActivationProcessIT.theBoundaryTranslationsActuallyHappenOnTheWayOut` |
| gross ↔ net and VAT | `VatCalculator` in `billing-service` | `VatCalculatorTest` (5 tests) |
| billing → Stripe minor units | `PaymentService.toMinorUnits` | `PaymentServiceTest` |
| billing → batch file implied decimals | `BatchFileFormat.toMinor`/`toMajor` | `BatchFileFormatTest` |
| catalog ↔ canonical, for ops | `subsystem-clients` | see that module's tests |

Note the duplication: `activation-service` and `subsystem-clients` translate the catalog's dialect
**separately**, with their own code. That is deliberate and it is the point. Each legacy subsystem
grew its own integration layer with its own idea of the rules; removing the duplication now would
delete the evidence for what phase 2 is supposed to fix. See `docs/decision-log.md` (DL-009).

## What this means for phase 2

The tokenomics experiment described in `PROJECT_DESC_HUN.md` compares two ways of getting an agent
to perform one business transaction:

1. **Raw heterogeneous interfaces.** Every schema above goes in the prompt, and the model has to
   infer, on every call, that GB means 1024 MB, that `-1` means unmetered, that the price is gross,
   that `+` must be stripped, and that `PA` means the subscription is not live yet. Every inference
   is a chance to be wrong in a way nothing validates.
2. **Through an MCP layer.** The rules above are written down once, in code, tested, and the model
   sees one canonical vocabulary.

The measurable quantity is the difference in tokens and latency. The *interesting* quantity is
which of the two gets `MOB-VOICE-0010`'s price right — and neither can, because the system itself
does not agree on it.
