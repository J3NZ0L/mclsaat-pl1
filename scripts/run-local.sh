#!/usr/bin/env bash
#
# Starts the whole landscape without Docker.
#
#   scripts/create-local-databases.sh     # one-off
#   scripts/run-local.sh
#   scripts/demo.sh
#   scripts/stop-local.sh
#
# Five JVMs in the background: stripe-sim (standing in for stripe-mock), the three subsystems and the
# ops console. Logs and PIDs land in .local-run/; the batch-exchange directories too.
#
# Needs Java 21, Maven and a PostgreSQL server with the databases from create-local-databases.sh.
# Everything is configured through the same environment variables docker compose uses, so anything you
# can change there you can change here.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$PWD"
RUN_DIR="$ROOT/.local-run"
LOG_DIR="$RUN_DIR/logs"
PID_DIR="$RUN_DIR/pids"
BATCH_DIR="$RUN_DIR/batch-exchange"
VERSION="1.0.0-SNAPSHOT"

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"

SKIP_BUILD="${SKIP_BUILD:-false}"

mkdir -p "$LOG_DIR" "$PID_DIR" "$BATCH_DIR/outbox" "$BATCH_DIR/inbox" "$BATCH_DIR/archive"

# ---------------------------------------------------------------------------- build

if [[ "$SKIP_BUILD" != "true" ]]; then
  echo "==> Building (tests are skipped here; run 'mvn verify' for those)"
  mvn -B -q -DskipTests package
else
  echo "==> SKIP_BUILD=true, using the jars already in target/"
fi

for module in stripe-sim catalog-service billing-service activation-service ops-console; do
  jar="$ROOT/$module/target/$module-$VERSION-app.jar"
  if [[ ! -f "$jar" ]]; then
    echo "error: $jar is missing. Run without SKIP_BUILD, or 'mvn -DskipTests package'." >&2
    exit 1
  fi
done

# ----------------------------------------------------------------------- utilities

start_service() {
  local name="$1"; shift
  local jar="$ROOT/$name/target/$name-$VERSION-app.jar"

  if [[ -f "$PID_DIR/$name.pid" ]] && kill -0 "$(cat "$PID_DIR/$name.pid")" 2>/dev/null; then
    echo "    $name is already running (pid $(cat "$PID_DIR/$name.pid"))"
    return
  fi

  # `env -` is not used: the services legitimately inherit PATH and JAVA_HOME. Everything they
  # actually read is passed explicitly by the callers below.
  nohup java -XX:MaxRAMPercentage=25 -jar "$jar" > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$PID_DIR/$name.pid"
  echo "    $name started (pid $!), logging to .local-run/logs/$name.log"
}

wait_for_health() {
  local name="$1" port="$2" attempts="${3:-90}"
  local i
  for ((i = 1; i <= attempts; i++)); do
    if curl -fsS --noproxy '*' "http://localhost:$port/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
      echo "    $name is UP on $port"
      return 0
    fi
    # Fail fast if the process died rather than waiting out the whole timeout.
    if [[ -f "$PID_DIR/$name.pid" ]] && ! kill -0 "$(cat "$PID_DIR/$name.pid")" 2>/dev/null; then
      echo "error: $name exited during start-up. Last lines of its log:" >&2
      tail -25 "$LOG_DIR/$name.log" >&2
      exit 1
    fi
    sleep 1
  done
  echo "error: $name did not become healthy on port $port within ${attempts}s. Last lines:" >&2
  tail -25 "$LOG_DIR/$name.log" >&2
  exit 1
}

# ------------------------------------------------------------------------- start-up

echo "==> Starting the Stripe stand-in"
# stripe-sim rather than the stripe-mock container: same port, same Stripe API shape, and it remembers
# the intents it creates so a confirm actually changes their status.
STRIPE_SIM_PORT=12111 start_service stripe-sim
wait_for_health stripe-sim 12111 60

echo "==> Starting subsystem 1: catalog-service"
CATALOG_PORT=8081 \
CATALOG_DB_URL="jdbc:postgresql://$PGHOST:$PGPORT/catalogdb" \
CATALOG_DB_USER="${CATALOG_DB_USER:-catalog_app}" \
CATALOG_DB_PASSWORD="${CATALOG_DB_PASSWORD:-catalog_app}" \
  start_service catalog-service

echo "==> Starting subsystem 3: billing-service"
BILLING_PORT=8083 \
BILLING_DB_URL="jdbc:postgresql://$PGHOST:$PGPORT/billingdb" \
BILLING_DB_USER="${BILLING_DB_USER:-billing_app}" \
BILLING_DB_PASSWORD="${BILLING_DB_PASSWORD:-billing_app}" \
STRIPE_API_KEY="${STRIPE_API_KEY:-sk_test_mclsaat123}" \
STRIPE_API_BASE="${STRIPE_API_BASE:-http://localhost:12111}" \
BATCH_OUTBOX_DIR="$BATCH_DIR/outbox" \
BATCH_INBOX_DIR="$BATCH_DIR/inbox" \
BATCH_ARCHIVE_DIR="$BATCH_DIR/archive" \
BATCH_POLL_INTERVAL_MS="${BATCH_POLL_INTERVAL_MS:-2000}" \
BATCH_UNCONFIRMED_AFTER_MINUTES="${BATCH_UNCONFIRMED_AFTER_MINUTES:-2}" \
  start_service billing-service

wait_for_health catalog-service 8081
wait_for_health billing-service 8083

# Activation goes last of the three: it calls the other two while a process runs.
echo "==> Starting subsystem 2: activation-service (Flowable embedded)"
ACTIVATION_PORT=8082 \
ACTIVATION_DB_URL="jdbc:postgresql://$PGHOST:$PGPORT/flowabledb" \
ACTIVATION_DB_USER="${ACTIVATION_DB_USER:-activation_app}" \
ACTIVATION_DB_PASSWORD="${ACTIVATION_DB_PASSWORD:-activation_app}" \
CATALOG_BASE_URL="http://localhost:8081" \
BILLING_SOAP_ENDPOINT="http://localhost:8083/ws" \
PROVISIONING_TIMEOUT="${PROVISIONING_TIMEOUT:-PT20S}" \
PROVISIONING_CALLBACK_DELAY="${PROVISIONING_CALLBACK_DELAY:-PT2S}" \
PROVISIONING_CALLBACK_BASE_URL="http://localhost:8082" \
  start_service activation-service
wait_for_health activation-service 8082

echo "==> Starting the ops console"
OPS_PORT=8080 \
CATALOG_DB_URL="jdbc:postgresql://$PGHOST:$PGPORT/catalogdb" \
CATALOG_DB_USER="${OPS_DB_USER:-ops_reader}" \
CATALOG_DB_PASSWORD="${OPS_DB_PASSWORD:-ops_reader}" \
ACTIVATION_BASE_URL="http://localhost:8082" \
BILLING_SOAP_ENDPOINT="http://localhost:8083/ws" \
BATCH_OUTBOX_DIR="$BATCH_DIR/outbox" \
  start_service ops-console
wait_for_health ops-console 8080

cat <<'BANNER'

Everything is up.

  catalog-service      http://localhost:8081   REST + a directly readable schema
  activation-service   http://localhost:8082   REST + message correlation, Flowable embedded
  billing-service      http://localhost:8083   SOAP at /ws, WSDL at /ws/billing.wsdl
  ops-console          http://localhost:8080   /ops/v1/diagnostics, /ops/v1/remediation
  stripe-sim           http://localhost:12111  the Stripe stand-in

  logs                 .local-run/logs/
  batch exchange       .local-run/batch-exchange/{outbox,inbox,archive}

Next:
  scripts/demo.sh      drives the happy path and both failure branches
  scripts/stop-local.sh
BANNER
