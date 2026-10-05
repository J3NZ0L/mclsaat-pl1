# mclsaat-pl1 — a legacy ISP/telco landscape, on purpose

The **phase-1 base** for the modernization experiment in [`PROJECT_DESC_HUN.md`](PROJECT_DESC_HUN.md):
a running enterprise landscape for an ISP's B2C subscription business, built to be as awkwardly
heterogeneous as a real one. Phases 2 and 3 — MCP servers, agents, Google UCP, tokenomics — are
**not** in this repository; the seams they will attach to are.

```bash
docker compose up --build      # the whole thing
scripts/demo.sh                # happy path + both failure branches, end to end
```

Not a mock system. The databases, migrations, SOAP stack, WSDL, fixed-width file formats, process
engine, job executor, timers and inter-service HTTP calls are all real. Two things are simulated,
because the alternative teaches nothing: **Stripe** (the official `stripe-mock`, spoken to through the
real Stripe SDK) and the **external network provisioning platform** (which still calls back over real
HTTP, from another thread).

## Three subsystems that agree on nothing

| | Subsystem 1 — catalog | Subsystem 2 — activation | Subsystem 3 — billing |
| --- | --- | --- | --- |
| **Port** | 8081 | 8082 | 8083 |
| **API** | REST/JSON + a directly readable schema | REST/JSON + message correlation | SOAP/WSDL + fixed-width batch files + Stripe REST |
| **Interaction** | synchronous | **asynchronous** — Flowable 7 embedded as a library | synchronous + an asynchronous file exchange |
| **Customer** | `"00000042"` `CHAR(8)` | `42` `INTEGER` | `42` **and** `"BA-00042"` |
| **Product** | `MOB-VOICE-0010` | `mob.voice.0010` | not modelled |
| **Data** | `10240` **megabytes**, `-1` = unmetered | `10.000` **gigabytes**, `null` = unmetered | not modelled |
| **Money** | `599000` **fillér**, gross | `5990.00` **HUF**, gross | `4716.54` net + `1273.47` VAT |
| **Dates** | `DATE` | `"20260929"` | `"2026-09-29"` |
| **Phone** | `36301234567` | `+36301234567` | not modelled |
| **Status** | `PA`, `AC` | `AWAITING_PROVISIONING`, `STUCK` | `SETTLEMENT_PENDING`, `PAID` |
| **Errors** | JSON + HTTP status | JSON + HTTP status | SOAP faults |

Four kinds of API — REST, SOAP, batch file, **direct JDBC into another subsystem's schema** — and the
data models differ *semantically*, not just by protocol. That is the requirement the brief cares about,
and the full inventory is in [`docs/semantic-mismatches.md`](docs/semantic-mismatches.md).

One consequence, to show the flavour: the catalog quotes `MOB-VOICE-0010` at 5990.00 Ft gross, billing
invoices on net and adds 27% VAT back, and the customer is billed **5990.01**. Two of the nine seeded
plans do that. Nobody in the system owns the discrepancy, and a test pins it so nobody accidentally
"fixes" it.

## Six services

1. **Browse plans and tariffs** — catalog REST
2. **Start a subscription** — activation REST *(the happy path)*
3. **Poll the order status** — activation REST *(needed, because #2 returns `202` and nothing else)*
4. **Plan change / add-on** — the same BPMN process, a different `changeType`
5. **Query invoices, start a payment** — billing SOAP → Stripe
6. **Detect and resolve the failure branches** — the ops console, internal only

## Two deliberate failure branches

Both are the same thing underneath: **a state that exists in one subsystem and cannot be expressed in
its neighbour.** Neither can be diagnosed from inside a single system.

**A. Stuck activation.** The provisioning platform never calls back. A **non-interrupting** boundary
timer reports the order `STUCK` *and leaves the wait in place*, so the catalog is left holding a
subscription in `PA` — indistinguishable from one about to succeed. Ops finds it by joining activation
over REST to the catalog over direct JDBC, then injects the missing correlated message and the order
finishes down the normal path.

**B. Unconfirmed settlement batch.** Stripe has taken the money, the fixed-width settlement file went
out, and the clearing house never answered. The invoice sits in `SETTLEMENT_PENDING`. Ops finds it by
joining billing over SOAP to the files on disk — and whether the file still exists is what decides
whether rebuilding the acknowledgement is honest or a guess.

## Getting started

```bash
# Docker: one command
docker compose up --build

# or without Docker
scripts/create-local-databases.sh    # one-off
scripts/run-local.sh

# then
scripts/demo.sh                      # everything
scripts/demo.sh happy|stuck|batch    # one part

# tests — note: verify, not test
mvn verify
```

`mvn test` runs only the fast unit tests. Everything that matters (real PostgreSQL, real SOAP over a
real port, a live Flowable job executor, real files) is in `*IT` classes that run during `verify`.

Behind a TLS-terminating proxy, `cp /root/.ccr/ca-bundle.crt docker/ca/` before building — see
[`docker/ca/README.md`](docker/ca/README.md).

## Layout

```
catalog-service/      subsystem 1 — REST + a legacy-shaped schema others read directly
activation-service/   subsystem 2 — Flowable 7 embedded, async, message-correlated
billing-service/      subsystem 3 — SOAP, batch files, Stripe, the clearing-house simulator
subsystem-clients/    one canonical vocabulary + the mappers + one client per protocol
ops-console/          the internal view across all three; the first consumer of the above
stripe-sim/           a Stripe-shaped simulator for the non-Docker path
scripts/              run-local, stop-local, create-local-databases, demo
docs/                 architecture, semantic mismatches, API reference, run guide, decisions, seams
```

`subsystem-clients` is the layer phase 2's MCP servers are meant to sit on;
[`docs/phase2-seams.md`](docs/phase2-seams.md) says where everything attaches — and, more usefully,
which three things a later phase must **not** tidy away, because they are the subject matter rather
than bugs.

## Documentation

| | |
| --- | --- |
| [`docs/architecture.md`](docs/architecture.md) | subsystems, protocols, the BPMN process, mocked vs. real, scope |
| [`docs/semantic-mismatches.md`](docs/semantic-mismatches.md) | **the centrepiece** — every data-model disagreement, with the test that pins it |
| [`docs/api-reference.md`](docs/api-reference.md) | every endpoint, SOAP operation and file format |
| [`docs/run-guide.md`](docs/run-guide.md) | both start-up paths, every environment variable, seed data, troubleshooting |
| [`docs/decision-log.md`](docs/decision-log.md) | what was decided and why, including the deliberate ugliness |
| [`docs/phase2-seams.md`](docs/phase2-seams.md) | where MCP, the agents and UCP attach |
| [`docs/phase2-gaps.md`](docs/phase2-gaps.md) | what phase 1 is still missing for phase 2, and open decisions |
| [`CLAUDE.md`](CLAUDE.md) | for future Claude Code sessions, incl. the do-not-modify list |
| [`docs/superpowers/`](docs/superpowers/) | the build plan and its live status |

`PROJECT_DESC_HUN.md`, `INITIAL_DESIGN.md`, `TODOS.md` and `PROGRESS_DIARY.md` are the user's own
source documents and are treated as read-only.
