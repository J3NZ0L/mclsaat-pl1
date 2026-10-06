# Run guide

Two ways to start the system. Both give you the same five processes and the same seed data.

* **Docker** — one command, nothing to install but Docker.
* **Local** — no Docker at all, one script, for when you want a debugger attached.

Then a scripted demo that drives the happy path end to end and triggers *and resolves* both
deliberate failure branches.

---

## 1. Docker (the one-command path)

```bash
docker compose up --build
```

That builds the five service images and starts, in order:

| Container | Port | What it is |
| --- | --- | --- |
| `postgres` | 5432 | one server, three databases: `catalogdb`, `flowabledb`, `billingdb` |
| `stripe-sim` | 12111 | the stateful Stripe stand-in (`stripe/stripe-mock` is an opt-in profile, DL-024) |
| `catalog-service` | 8081 | subsystem 1 |
| `billing-service` | 8083 | subsystem 3 |
| `activation-service` | 8082 | subsystem 2, Flowable embedded |
| `ops-console` | 8080 | internal ops + the demo's driver |

Compose waits on health checks, so when `docker compose up` settles everything is actually ready.
Flyway runs the migrations and loads the seed data on first start.

Check it:

```bash
curl -s localhost:8081/actuator/health
curl -s localhost:8082/actuator/health
curl -s localhost:8083/actuator/health
curl -s localhost:8080/actuator/health
curl -s "localhost:8081/api/v1/plans?serviceKind=M"
curl -s localhost:8083/ws/billing.wsdl | head -20
```

The clearing-house file exchange lives on a named volume shared with nothing else. To watch it:

```bash
docker compose exec billing-service ls -l /var/lib/legacy/batch-exchange/outbox
docker compose exec billing-service cat /var/lib/legacy/batch-exchange/outbox/PMT-BATCH-*.txt
docker compose exec billing-service cat /var/lib/legacy/batch-exchange/archive/ACK-BATCH-*.txt
```

Those directories are created **in the image**, owned by the runtime user, because Docker seeds an empty
named volume from the image including ownership. If you ever see `exportPaymentBatch` return 502
"could not write settlement file", the volume predates that fix — `docker compose down -v` and build
again.

Stop and wipe:

```bash
docker compose down          # keeps the data
docker compose down -v       # drops the databases and the batch volume too
```

### Behind a TLS-terminating proxy

If outbound HTTPS on this machine goes through a proxy that terminates TLS — a corporate MITM
appliance, or a cloud dev container — the JVM inside the build container will refuse to talk to Maven
Central:

```
PKIX path building failed: unable to find valid certification path to requested target
```

Drop the proxy's CA certificate into `docker/ca/` and build again. The `Dockerfile` imports whatever it
finds there into the JDK truststore before Maven runs, and the directory is empty (and the step a
no-op) on an ordinary machine.

```bash
cp /root/.ccr/ca-bundle.crt docker/ca/     # in a Claude Code cloud container
docker compose up --build
```

See [`docker/ca/README.md`](../docker/ca/README.md). Certificate files are gitignored.

Two related details, both already handled in `docker-compose.yml`: the builds use `network: host`
(the proxy is bound to `127.0.0.1`, which a bridge-network build container cannot reach), and the
runtime images install nothing at all — `apt-get` over such a proxy fails with "the repository is not
signed", so the health checks talk HTTP through bash's `/dev/tcp` instead of using curl.

### If the Docker daemon is not running

In a fresh cloud container it often is not:

```bash
nohup dockerd > /tmp/dockerd.log 2>&1 &
sleep 8 && docker info
```

It has also been seen to die during a heavy build; restart it the same way and build again.

### If a build fails on `load metadata` with HTTP 429

Docker Hub rate-limiting. Pull the base images on their own, with a retry, then build:

```bash
for i in 1 2 3; do docker pull maven:3.9-eclipse-temurin-21 && break; sleep 15; done
docker pull eclipse-temurin:21-jre-jammy
```

---

## 2. Local, without Docker

Needs Java 21, Maven 3.9+, and a PostgreSQL 16 server you can create databases on. (In a Claude Code
cloud container PostgreSQL is installed but not started: `pg_ctlcluster 16 main start`.)

```bash
# one-off: create the three databases, their roles and the ops console's read-only grant
scripts/create-local-databases.sh          # reads PGHOST/PGPORT/PGUSER, defaults to localhost:5432
# ...or, where only the postgres unix socket accepts a superuser:
#   su postgres -c "PGHOST=/var/run/postgresql scripts/create-local-databases.sh"

# build and run everything, including stripe-sim
scripts/run-local.sh
```

`scripts/run-local.sh` builds with `mvn -DskipTests package`, then starts `stripe-sim`,
`catalog-service`, `billing-service`, `activation-service` and `ops-console` as background JVMs,
waits for each health check, and prints where the logs are. `scripts/stop-local.sh` stops them.

`stripe-sim` listens on **12111**, the same port `stripe-mock` uses. It remembers the PaymentIntents it
created, so a confirm actually makes them `succeeded` — which `payInvoice` needs, and which the stateless
official mock cannot do (DL-024). Docker compose runs `stripe-sim` too; the official image is the opt-in
compose profile `official-stripe-mock`, pinned to `v0.206.0` (DL-027).

Logs go to `.local-run/logs/<service>.log`. The batch exchange goes to `.local-run/batch-exchange/`.

---

## 3. The demo

```bash
scripts/demo.sh
```

Works against either path — it only talks to the HTTP ports. It is deliberately verbose: it prints
the raw JSON, the raw SOAP envelopes and the raw fixed-width batch records, because the point of the
system is what those look like.

What it does, in order:

1. **Browse the catalogue** (service 1, catalog REST) — and show the same rows read directly over
   JDBC, so the two access paths sit side by side.
2. **Start a mobile subscription** (service 2, activation REST) — note the `202 Accepted` with the
   order in `RECEIVED` and no fee resolved yet.
3. **Poll it** (service 3) until `PROVISIONED`, showing it pass through `AWAITING_PROVISIONING` while
   the simulated network platform takes its time.
4. **Show the catalog side**: the subscription is now `AC` with the ICCID the platform allocated.
5. **Buy a data pack** (service 4, the `ADDON` variant of the same process) and show the base
   subscription's effective allowance grow from 10240 MB to 15360 MB.
6. **Query the invoices over SOAP** (service 5) — raw XML, including the `5990.01` gross against a
   catalog price of `5990.00`.
7. **Pay the invoice with one SOAP call**, `payInvoice` — no browser, no Stripe confirm and no webhook
   from the script. Billing confirms at Stripe, moves the invoice to `SETTLEMENT_PENDING` (not
   `PAID`) and cuts the settlement batch itself.
8. **Wait for the poller** to apply the clearing house's acknowledgement: batch `ACKED`, invoice `PAID`.
   No ops action is involved. The fixed-width file is printed in failure branch B.
9. **Failure branch A** — start an order with `simulateStuck: true` and a short timer, wait for it to
   go `STUCK`, show the catalog left holding a `PA` subscription, detect it through the ops console
   (which has to join activation over REST with the catalog over JDBC), then resolve it with
   `force-provision` and watch the order finish normally.
10. **Failure branch B** — silence the clearing house, pay another invoice with `payInvoice` (billing cuts
    the batch itself), show the batch stranded in `SENT` and the invoice in `SETTLEMENT_PENDING`, detect it through the ops console (SOAP plus the outbox files), then
    `reconcileBatch` with `RE_DRIVE_ACK` and watch the invoice reach `PAID`.

Every step is a check, and the script exits non-zero if any of them fails — so it doubles as an
end-to-end test of the running system. It passes 21 checks against either start-up path.

Run one part only:

```bash
scripts/demo.sh happy        # steps 1-8
scripts/demo.sh stuck        # step 9
scripts/demo.sh batch        # step 10
```

Point it somewhere else with `CATALOG_URL`, `ACTIVATION_URL`, `BILLING_URL`, `OPS_URL`, `STRIPE_URL`.

---

## 4. Tests

```bash
mvn verify
```

**Use `verify`, not `test`.** `mvn test` runs only the fast unit tests — the mappers, the VAT
arithmetic, the fixed-width record offsets. Everything that matters about this system (real
PostgreSQL, real SOAP over a real port, a real Flowable job executor, real files) is in `*IT` classes
that Failsafe runs during `verify`.

Integration tests need a working Docker daemon for Testcontainers. If they all fail with
"Could not find a valid Docker environment", the daemon is down; if they fail with
`client version 1.32 is too old`, override the pinned Engine API version:

```bash
mvn verify -Ddocker.api.version=1.41
```

Run one module or one test:

```bash
mvn -pl billing-service verify
mvn -pl activation-service verify -Dit.test=SubscriptionActivationProcessIT
mvn -pl catalog-service test
```

---

## 5. Configuration

Everything is an environment variable with a working default. The defaults assume the local path;
`docker-compose.yml` overrides the hosts.

### catalog-service

| Variable | Default | Notes |
| --- | --- | --- |
| `CATALOG_PORT` | `8081` | |
| `CATALOG_DB_URL` | `jdbc:postgresql://localhost:5432/catalogdb` | |
| `CATALOG_DB_USER` / `CATALOG_DB_PASSWORD` | `catalog_app` | |

### activation-service

| Variable | Default | Notes |
| --- | --- | --- |
| `ACTIVATION_PORT` | `8082` | |
| `ACTIVATION_DB_URL` | `jdbc:postgresql://localhost:5432/flowabledb` | Flowable's tables live here too |
| `CATALOG_BASE_URL` | `http://localhost:8081` | |
| `BILLING_SOAP_ENDPOINT` | `http://localhost:8083/ws` | |
| `PROVISIONING_TIMEOUT` | `PT45S` | the boundary timer. A production SLA would be hours |
| `PROVISIONING_CALLBACK_DELAY` | `PT2S` | how long the simulated platform takes to answer |
| `PROVISIONING_CALLBACK_BASE_URL` | `http://localhost:8082` | the platform calls back over real HTTP, so it needs this service's own URL |

### billing-service

| Variable | Default | Notes |
| --- | --- | --- |
| `BILLING_PORT` | `8083` | |
| `BILLING_DB_URL` | `jdbc:postgresql://localhost:5432/billingdb` | |
| `STRIPE_API_KEY` | `sk_test_mclsaat123` | must be alphanumeric after `sk_test_` — `stripe-mock` validates the shape |
| `STRIPE_API_BASE` | `http://localhost:12111` (`http://stripe-sim:12111` under compose) | `stripe-sim`, `stripe-mock` (create-only), or `https://api.stripe.com` |
| `BATCH_OUTBOX_DIR` / `BATCH_INBOX_DIR` / `BATCH_ARCHIVE_DIR` | `batch-exchange/{outbox,inbox,archive}` | |
| `BATCH_POLL_INTERVAL_MS` | `2000` | how often the inbox is swept |
| `BATCH_AUTO_EXPORT` | `false` | the nightly-style export; off so the demo decides when a batch is cut |
| `BATCH_UNCONFIRMED_AFTER_MINUTES` | `2` | when a sent-but-unacknowledged batch is reported to ops |

### ops-console

| Variable | Default | Notes |
| --- | --- | --- |
| `OPS_PORT` | `8080` | |
| `CATALOG_DB_URL` / `CATALOG_DB_USER` / `CATALOG_DB_PASSWORD` | as above | ops reads the catalog **directly over JDBC** |
| `ACTIVATION_BASE_URL` | `http://localhost:8082` | |
| `BILLING_SOAP_ENDPOINT` | `http://localhost:8083/ws` | |
| `BATCH_OUTBOX_DIR` | `batch-exchange/outbox` | ops reads the settlement files |

### Using a real Stripe test key

```bash
export STRIPE_API_BASE=https://api.stripe.com
export STRIPE_API_KEY=sk_test_your_real_test_key
```

Nothing else changes — the calls already go through the official Stripe Java SDK. The webhook
endpoint does **not** verify signatures (there is no signing secret); with a real key, add
`Webhook.constructEvent(...)` in `StripeWebhookController` before exposing it to anything.

---

## 6. Seed data

Loaded by Flyway on first start. Three subscribers, nine plans, four subscriptions, five invoices,
three billing accounts, one stranded batch.

| Customer | catalog `cust_no` | activation `customerRef` | billing `ba_no` |
| --- | --- | --- | --- |
| Kovács Anna | `00000042` | `42` | `BA-00042` |
| Nagy Béla | `00000043` | `43` | `BA-00043` |
| Szabó Csilla | `00000101` | `101` | `BA-00101` |

Plans: `INET-FIB-0500`, `INET-FIB-1000`, `INET-ADSL-0030`, `MOB-VOICE-0010`, `MOB-VOICE-0050`,
`MOB-VOICE-0002` (withdrawn, `active_flag = 'N'`), `ADDON-DATA-0005`, `ADDON-DATA-0010`,
`ADDON-ROAM-EU01`.

Two rows are seeded **deliberately inconsistent**, so both failure branches are findable from a cold
start without triggering anything:

* `SUB-2026-000009` — sitting in `PA` for four days, because the activation order it belongs to never
  got its provisioning callback. Failure branch A. It has no live process instance, so it is the
  *orphaned* flavour: ops can see it but `force-provision` cannot repair it, only cancellation and a
  fresh order can.
* `BATCH-20260925-001` + invoice `2026/INV/000005` — a batch sent four days ago and never
  acknowledged, with the customer's money already at Stripe. Failure branch B. Its settlement file
  was never written (seeding only touched the database), so `reconcileBatch` reports
  `ILLEGAL_STATE` — which is itself the honest answer: you cannot rebuild an acknowledgement from a
  file that does not exist.

Both seeded cases therefore demonstrate the *limits* of the remediation tools, which is why the demo
also creates fresh, fully repairable instances of each.

---

## 7. Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Integration tests: "Could not find a valid Docker environment" | the Docker daemon is not running — `nohup dockerd > /tmp/dockerd.log 2>&1 &` |
| Integration tests: `client version 1.32 is too old` | docker-java's negotiated Engine API version; `mvn verify -Ddocker.api.version=1.41` |
| `mvn test` passes but nothing seems tested | correct — use `mvn verify` |
| Stripe calls fail with `AuthenticationException` | the key must be alphanumeric after `sk_test_` |
| An order sits in `RECEIVED` for ever | the Flowable async executor is not running; check `flowable.async-executor-activate` |
| An order reaches `AWAITING_PROVISIONING` and stops | the platform's callback could not reach this service — check `PROVISIONING_CALLBACK_BASE_URL` |
| An invoice stays `SETTLEMENT_PENDING` | either the clearing house is silenced (`GET /sim/clearing-house/config`) or no batch has been exported yet |
| `BillingContractCopyTest` fails | billing's XSD changed; the failure message prints the `cp` to run |
| Image build: `PKIX path building failed` | a TLS-terminating proxy; put its CA in `docker/ca/` (see above) |
| Image build: `load metadata ... 429 Too Many Requests` | Docker Hub rate-limiting; pull the base images separately first |
| `exportPaymentBatch` → 502 "could not write settlement file" | the `batch-exchange` volume is root-owned. It is seeded from the image, so recreate it: `docker compose down -v && docker compose up --build` |
| "no space left on device" | delete build output: `mvn clean`, `docker system prune` |
