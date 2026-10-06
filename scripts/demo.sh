#!/usr/bin/env bash
#
# Drives the whole system end to end: the happy path, then both deliberate failure branches.
#
#   scripts/demo.sh           everything
#   scripts/demo.sh happy     browse -> subscribe -> add-on -> invoice -> pay -> settle
#   scripts/demo.sh stuck     failure branch A: stuck activation, detected and repaired
#   scripts/demo.sh batch     failure branch B: unconfirmed settlement, detected and repaired
#
# Works against either start-up path - docker compose or scripts/run-local.sh - because it only talks
# to the HTTP ports. Override any of them with CATALOG_URL, ACTIVATION_URL, BILLING_URL, OPS_URL.
#
# Deliberately verbose. It prints raw JSON, raw SOAP envelopes and raw fixed-width batch records,
# because what those look like *is* the subject matter.

set -uo pipefail

CATALOG_URL="${CATALOG_URL:-http://localhost:8081}"
ACTIVATION_URL="${ACTIVATION_URL:-http://localhost:8082}"
BILLING_URL="${BILLING_URL:-http://localhost:8083}"
OPS_URL="${OPS_URL:-http://localhost:8080}"

# A demo customer with an existing billing account in all three subsystems.
CUSTOMER_REF="${CUSTOMER_REF:-43}"
NS="http://mclsaat.hu/legacy/billing/v1"

# --noproxy: these are all localhost, and an HTTPS_PROXY in the environment must not swallow them.
CURL=(curl -sS --noproxy '*' --max-time 30)

# --------------------------------------------------------------------------- output

if [[ -t 1 ]]; then
  B=$'\033[1m'; DIM=$'\033[2m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; CYAN=$'\033[36m'; R=$'\033[0m'
else
  B=''; DIM=''; GREEN=''; YELLOW=''; RED=''; CYAN=''; R=''
fi

STEP=0
section() { printf '\n%s%s══ %s %s%s\n' "$B" "$CYAN" "$1" "══════════════════════════════════════════" "$R"; }
step()    { STEP=$((STEP + 1)); printf '\n%s[%02d] %s%s\n' "$B" "$STEP" "$1" "$R"; }
note()    { printf '     %s%s%s\n' "$DIM" "$1" "$R"; }
ok()      { printf '     %s✓ %s%s\n' "$GREEN" "$1" "$R"; }
warn()    { printf '     %s! %s%s\n' "$YELLOW" "$1" "$R"; }
fail()    { printf '     %s✗ %s%s\n' "$RED" "$1" "$R"; FAILURES=$((FAILURES + 1)); }
raw()     { sed 's/^/     | /'; }
FAILURES=0

# --------------------------------------------------------------------------- helpers

jq_get() {  # jq_get '<json>' "['a']['b']"
  python3 -c "
import sys, json
try:
    d = json.loads(sys.argv[1])
    print(eval('d' + sys.argv[2]))
except Exception as exc:
    print('', end='')
    sys.exit(1)
" "$1" "$2" 2>/dev/null
}

pretty_json() { python3 -m json.tool 2>/dev/null || cat; }

soap() {  # soap '<body xml>'
  "${CURL[@]}" -X POST "$BILLING_URL/ws" -H 'Content-Type: text/xml; charset=utf-8' \
    --data-binary "<?xml version=\"1.0\"?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>$1</soap:Body></soap:Envelope>"
}

pretty_xml() { python3 -c "import sys,xml.dom.minidom as m; print(m.parseString(sys.stdin.read()).toprettyxml(indent='  '))" 2>/dev/null || cat; }

soap_field() {  # soap_field '<response xml>' fieldName
  printf '%s' "$1" | grep -oE "<ns2:$2>[^<]*" | head -1 | sed "s|<ns2:$2>||"
}

require_up() {
  local name="$1" url="$2"
  if "${CURL[@]}" "$url/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
    ok "$name is up"
  else
    fail "$name is not reachable at $url"
    echo
    echo "Start the system first:  docker compose up --build   or   scripts/run-local.sh" >&2
    exit 1
  fi
}

# Polls an activation order until it reaches one of the given statuses.
poll_order() {  # poll_order <orderNo> <timeoutSeconds> <status>...
  local order_no="$1" timeout="$2"; shift 2
  local wanted=("$@") i response status activity
  for ((i = 1; i <= timeout; i++)); do
    response="$("${CURL[@]}" -G --data-urlencode "orderNo=$order_no" "$ACTIVATION_URL/activation/v1/orders")"
    status="$(jq_get "$response" "['order']['status']")"
    activity="$(jq_get "$response" "['currentActivity']")"
    printf '     %st+%-3ss %-24s %s%s\n' "$DIM" "$i" "$status" "${activity:-—}" "$R"
    for want in "${wanted[@]}"; do
      if [[ "$status" == "$want" ]]; then
        LAST_ORDER_JSON="$response"
        return 0
      fi
    done
    sleep 1
  done
  LAST_ORDER_JSON="$response"
  return 1
}

start_order() {  # start_order '<json body>'
  "${CURL[@]}" -X POST "$ACTIVATION_URL/activation/v1/orders" \
    -H 'Content-Type: application/json' --data-binary "$1"
}

# =============================================================================== A

demo_happy() {
  section "HAPPY PATH"

  step "Service 1 — browse the mobile tariffs (subsystem 1, REST/JSON)"
  note "Watch the units: monthlyFeeMinor is HUF fillér, dataAllowanceMb is megabytes, activeFlag is Y/N."
  "${CURL[@]}" "$CATALOG_URL/api/v1/plans?serviceKind=M&planKind=BASE" | pretty_json | raw

  step "The withdrawn plan is hidden unless you ask for it"
  local visible withdrawn
  visible="$("${CURL[@]}" "$CATALOG_URL/api/v1/plans" | grep -c 'planCode')"
  withdrawn="$("${CURL[@]}" "$CATALOG_URL/api/v1/plans?includeWithdrawn=true" | grep -c 'planCode')"
  note "$visible sellable plans, $withdrawn including withdrawn ones (MOB-VOICE-0002 has active_flag = 'N')."

  step "Service 2 — start a mobile subscription (subsystem 2, REST + embedded Flowable)"
  note "Note the dialect change: customerRef is an integer, the product is mob.voice.0050 rather than"
  note "MOB-VOICE-0050, the date is yyyyMMdd, and the phone number carries a leading plus."
  local created order_no
  created="$(start_order "{
    \"changeType\": \"NEW_SUBSCRIPTION\",
    \"customerRef\": $CUSTOMER_REF,
    \"offerId\": \"mob.voice.0050\",
    \"msisdn\": \"+36209876543\",
    \"requestedStartDate\": \"$(date +%Y%m%d)\"
  }")"
  printf '%s' "$created" | pretty_json | raw
  order_no="$(jq_get "$created" "['orderNo']")"
  if [[ -z "$order_no" ]]; then fail "the order was not accepted"; return 1; fi
  ok "order $order_no accepted — status RECEIVED, and no fee resolved yet"
  note "202 Accepted is the best it can do: validateOrder is flowable:async, so nothing has run."

  step "Service 3 — poll it (this is why polling is a service, not a convenience)"
  if poll_order "$order_no" 60 PROVISIONED FAILED CANCELLED; then
    ok "order reached $(jq_get "$LAST_ORDER_JSON" "['order']['status']")"
  else
    fail "the order did not finish in 60s"; return 1
  fi
  printf '%s' "$LAST_ORDER_JSON" | pretty_json | raw

  local sub_id invoice_no
  sub_id="$(jq_get "$LAST_ORDER_JSON" "['order']['subscriptionRef']")"
  invoice_no="$(jq_get "$LAST_ORDER_JSON" "['order']['invoiceRef']")"
  DEMO_SUBSCRIPTION="$sub_id"

  step "The catalog's view of the same subscription (subsystem 1)"
  note "Every field has been translated on the way across:"
  note "  customerRef $CUSTOMER_REF -> cust_no 000000$CUSTOMER_REF      mob.voice.0050 -> MOB-VOICE-0050"
  note "  +36209876543 -> 36209876543        50.000 GB -> 51200 MB        PROVISIONED -> AC"
  "${CURL[@]}" "$CATALOG_URL/api/v1/subscriptions/$sub_id" | pretty_json | raw

  step "Service 4 — buy a +5 GB data pack (the ADDON variant of the SAME BPMN process)"
  local addon_created addon_order
  addon_created="$(start_order "{
    \"changeType\": \"ADDON\",
    \"customerRef\": $CUSTOMER_REF,
    \"offerId\": \"addon.data.0005\",
    \"targetSubscriptionRef\": \"$sub_id\",
    \"requestedStartDate\": \"$(date +%Y%m%d)\"
  }")"
  addon_order="$(jq_get "$addon_created" "['orderNo']")"
  note "order $addon_order — same process definition, different branch of the changeType gateway"
  if poll_order "$addon_order" 60 PROVISIONED FAILED; then
    ok "add-on order reached $(jq_get "$LAST_ORDER_JSON" "['order']['status']")"
  else
    fail "the add-on order did not finish"; return 1
  fi

  step "The base subscription's effective allowance has grown"
  local allowance
  allowance="$(jq_get "$("${CURL[@]}" "$CATALOG_URL/api/v1/subscriptions/$sub_id")" "['dataAllowanceMb']")"
  if [[ "$allowance" == "56320" ]]; then
    ok "51200 MB + 5120 MB = $allowance MB — the catalog recomputed it when the add-on went active"
  else
    warn "effective allowance is $allowance MB (expected 56320)"
  fi

  step "Service 5 — query the invoices over SOAP (subsystem 3)"
  note "Raw SOAP. Amounts are NUMERIC(12,2) major units, dates are yyyy-MM-dd, and errors would be"
  note "faults rather than HTTP statuses."
  local invoices
  invoices="$(soap "<getInvoicesRequest xmlns=\"$NS\"><customerRef>$CUSTOMER_REF</customerRef><status>OPEN</status></getInvoicesRequest>")"
  printf '%s' "$invoices" | pretty_xml | raw

  step "The VAT round-trip artefact, in the data"
  note "The catalog quotes gross; billing invoices net and adds VAT back. For MOB-VOICE-0010 that"
  note "does not come back to the quoted price — 5990.00 gross becomes 4716.54 + 1273.47 = 5990.01."
  local artefact
  artefact="$(soap "<getInvoicesRequest xmlns=\"$NS\"><customerRef>42</customerRef><subscriptionRef>SUB-2026-000002</subscriptionRef></getInvoicesRequest>")"
  printf '%s' "$artefact" | grep -oE '<ns2:(invoiceNo|netAmount|vatAmount|grossAmount)>[^<]*' \
    | sed 's|<ns2:||; s|>| = |' | raw
  note "Nobody in this system owns that fillér. See docs/semantic-mismatches.md."

  step "Service 5 — pay $invoice_no with no browser (SOAP payInvoice)"
  note "The chat/agent channel has no Stripe.js, so billing closes the payment leg itself: it confirms"
  note "the PaymentIntent at Stripe, moves the invoice to SETTLEMENT_PENDING and cuts the settlement"
  note "batch. This script calls nothing else - no Stripe confirm, no webhook POST, no ops export."
  local payment payment_ref pay_status batch_id
  payment="$(soap "<payInvoiceRequest xmlns=\"$NS\"><invoiceNo>$invoice_no</invoiceNo></payInvoiceRequest>")"
  printf '%s' "$payment" | pretty_xml | raw
  payment_ref="$(soap_field "$payment" paymentRef)"
  pay_status="$(soap_field "$payment" invoiceStatus)"
  batch_id="$(soap_field "$payment" batchId)"
  if [[ -z "$payment_ref" ]]; then fail "payInvoice returned no PaymentIntent: $payment"; return 1; fi
  ok "Stripe PaymentIntent $payment_ref confirmed by billing itself"
  note "Third money representation: the invoice holds the gross amount, Stripe was told it in minor units."
  note "SETTLEMENT_PENDING, not PAID. Stripe has the money; this business does not consider it"
  note "posted until the clearing house acknowledges the settlement batch."
  if [[ "$pay_status" != "SETTLEMENT_PENDING" && "$pay_status" != "PAID" ]]; then
    fail "the invoice is $pay_status after payInvoice, expected SETTLEMENT_PENDING"; return 1
  fi
  if [[ -z "$batch_id" ]]; then fail "payInvoice cut no settlement batch"; return 1; fi
  ok "invoice is $pay_status and rides settlement batch $batch_id"
  note "The fixed-width settlement file itself is shown in failure branch B, where it gets stuck."

  step "The clearing house answers, and a background poller applies it"
  local i status
  for ((i = 1; i <= 30; i++)); do
    status="$(soap_field "$(soap "<getPaymentBatchRequest xmlns=\"$NS\"><batchId>$batch_id</batchId></getPaymentBatchRequest>")" status)"
    printf '     %st+%-3ss batch %s%s\n' "$DIM" "$i" "$status" "$R"
    [[ "$status" == "ACKED" ]] && break
    sleep 1
  done
  if [[ "$status" == "ACKED" ]]; then
    ok "batch $batch_id acknowledged"
  else
    warn "batch $batch_id is still $status"
  fi

  step "And the invoice is finally PAID"
  local final
  final="$(soap "<getInvoicesRequest xmlns=\"$NS\"><customerRef>$CUSTOMER_REF</customerRef><subscriptionRef>$sub_id</subscriptionRef></getInvoicesRequest>")"
  printf '%s' "$final" | grep -oE '<ns2:(invoiceNo|status|grossAmount|batchId)>[^<]*' \
    | sed 's|<ns2:||; s|>| = |' | raw
  if printf '%s' "$final" | grep -q '<ns2:status>PAID<'; then
    ok "OPEN -> SETTLEMENT_PENDING -> PAID, across SOAP, Stripe REST and a batch file"
  else
    warn "the invoice has not reached PAID"
  fi
}

# =============================================================================== B

demo_stuck() {
  section "FAILURE BRANCH A — stuck activation"
  note "The network platform never calls back. The boundary timer fires, the order goes STUCK, and"
  note "the catalog is left holding a subscription that never went live. The catalog has no word for"
  note "'stuck'; activation cannot see the catalog's row. Neither can diagnose it alone."

  step "Place an order the provisioning platform will ignore, with a 5-second SLA"
  local created order_no
  created="$(start_order "{
    \"changeType\": \"NEW_SUBSCRIPTION\",
    \"customerRef\": 101,
    \"offerId\": \"mob.voice.0010\",
    \"msisdn\": \"+36707654321\",
    \"requestedStartDate\": \"$(date +%Y%m%d)\",
    \"simulateStuck\": true,
    \"provisioningTimeout\": \"PT5S\"
  }")"
  order_no="$(jq_get "$created" "['orderNo']")"
  if [[ -z "$order_no" ]]; then fail "the order was not accepted"; return 1; fi
  ok "order $order_no accepted"

  step "Wait for the boundary timer"
  if poll_order "$order_no" 45 STUCK PROVISIONED FAILED; then
    if [[ "$(jq_get "$LAST_ORDER_JSON" "['order']['status']")" == "STUCK" ]]; then
      ok "order $order_no is STUCK"
    else
      warn "order reached $(jq_get "$LAST_ORDER_JSON" "['order']['status']") instead of STUCK"
    fi
  else
    fail "the order never went STUCK"; return 1
  fi
  note "reason: $(jq_get "$LAST_ORDER_JSON" "['order']['failureReason']")"

  step "The process is STILL WAITING — this is the whole point of the non-interrupting timer"
  local waiting
  waiting="$(jq_get "$LAST_ORDER_JSON" "['waitingForCallback']")"
  if [[ "$waiting" == "True" ]]; then
    ok "waitingForCallback = true, so the missing message can still be injected"
  else
    warn "waitingForCallback = $waiting; the order would only be cancellable"
  fi

  step "The catalog's half of the problem"
  local stuck_sub
  stuck_sub="$(jq_get "$LAST_ORDER_JSON" "['order']['subscriptionRef']")"
  "${CURL[@]}" "$CATALOG_URL/api/v1/subscriptions/$stuck_sub" | pretty_json | raw
  note "status_code 'PA' — indistinguishable from a subscription that is about to succeed."

  step "Service 6 — the ops console finds it, by joining REST to direct JDBC"
  "${CURL[@]}" "$OPS_URL/ops/v1/diagnostics/stuck-activations?olderThanMinutes=0" | python3 -c "
import sys, json
for finding in json.load(sys.stdin):
    print('--- %s | %s | repairable=%s' % (finding['orderNo'], finding['category'],
                                           finding['repairableByCallback']))
    print('    diagnosis: %s' % finding['diagnosis'])
    print('    remedy   : %s' % finding['suggestedRemedy'])
    activation, catalog = finding['activation'], finding['catalog']
    print('    activation (REST): %s' % (
        'none - no order refers to this row' if not activation
        else '%s, subscription %s, waiting=%s' % (activation['status'], activation['subscriptionRef'],
                                                  activation['waitingForCallback'])))
    print('    catalog (JDBC)   : %s' % (
        'none - nothing was reserved' if not catalog
        else '%s, code %s, cust_no %s, %s' % (catalog['subscriptionId'], catalog['catalogStatusCode'],
                                              catalog['custNo'], catalog['dataAllowance'])))
" | raw
  note "The seeded SUB-2026-000009 shows up too, as ORPHANED_PENDING_SUBSCRIPTION: a stale catalog row"
  note "with no order behind it, which force-provision cannot repair. Different problem, different remedy."

  step "Ops injects the callback the platform never sent"
  "${CURL[@]}" -X POST "$OPS_URL/ops/v1/remediation/activation/force-provision" \
    -H 'Content-Type: application/json' \
    --data-binary "{\"orderNo\":\"$order_no\",\"simIccid\":\"89360100123456789\"}" | pretty_json | raw

  step "And the order finishes down the NORMAL path"
  if poll_order "$order_no" 45 PROVISIONED FAILED; then
    ok "order $order_no is now $(jq_get "$LAST_ORDER_JSON" "['order']['status']")"
  else
    fail "the repaired order did not complete"; return 1
  fi
  note "SIM $(jq_get "$LAST_ORDER_JSON" "['order']['simIccid']") — the one ops supplied by hand"
  note "invoice $(jq_get "$LAST_ORDER_JSON" "['order']['invoiceRef']") — issued as if nothing had happened"

  step "The catalog is consistent again"
  "${CURL[@]}" "$CATALOG_URL/api/v1/subscriptions/$stuck_sub" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('subId=%s statusCode=%s simIccid=%s activatedOn=%s' % (d['subId'],d['statusCode'],d['simIccid'],d['activatedOn']))" | raw

  step "It no longer shows up as stuck"
  local still
  still="$("${CURL[@]}" "$OPS_URL/ops/v1/diagnostics/stuck-activations?olderThanMinutes=0" | grep -c "$order_no")"
  if [[ "$still" == "0" ]]; then
    ok "the diagnosis is clear for $order_no"
  else
    warn "$order_no is still being reported"
  fi
}

# =============================================================================== C

demo_batch() {
  section "FAILURE BRANCH B — unconfirmed settlement batch"
  note "Stripe takes the customer's money, the settlement file goes out, and the clearing house never"
  note "answers. The invoice sits in SETTLEMENT_PENDING - a state neither of the other two subsystems"
  note "can express."

  step "Silence the clearing house (the fault injection point)"
  "${CURL[@]}" -X POST "$BILLING_URL/sim/clearing-house/config" -H 'Content-Type: application/json' \
    --data-binary '{"ackEnabled":false}' | pretty_json | raw

  step "Find an OPEN invoice and pay it"
  local open_invoices invoice_no
  open_invoices="$(soap "<getInvoicesRequest xmlns=\"$NS\"><customerRef>42</customerRef><status>OPEN</status></getInvoicesRequest>")"
  invoice_no="$(soap_field "$open_invoices" invoiceNo)"
  if [[ -z "$invoice_no" ]]; then
    warn "customer 42 has no OPEN invoice left; run 'scripts/demo.sh happy' first"
    return 1
  fi
  note "paying $invoice_no"
  local payment batch_id pay_status
  payment="$(soap "<payInvoiceRequest xmlns=\"$NS\"><invoiceNo>$invoice_no</invoiceNo></payInvoiceRequest>")"
  printf '%s' "$payment" | pretty_xml | raw
  batch_id="$(soap_field "$payment" batchId)"
  pay_status="$(soap_field "$payment" invoiceStatus)"
  if [[ "$pay_status" == "SETTLEMENT_PENDING" ]]; then
    ok "payInvoice returned with the invoice SETTLEMENT_PENDING; with the clearing house silent it cannot move on"
  else
    fail "expected SETTLEMENT_PENDING from payInvoice, got '$pay_status'"
  fi

  step "Billing paid it and cut the settlement batch itself — it will never be acknowledged"
  if [[ -z "$batch_id" ]]; then
    warn "payInvoice cut no batch: $(soap_field "$payment" message)"
    return 1
  fi
  ok "batch $batch_id sent"
  note "$(soap_field "$payment" message)"

  step "Nothing came back"
  local batch_status
  batch_status="$(soap_field "$(soap "<getPaymentBatchRequest xmlns=\"$NS\"><batchId>$batch_id</batchId></getPaymentBatchRequest>")" status)"
  if [[ "$batch_status" == "SENT" ]]; then
    ok "batch $batch_id is still SENT — the money left and nothing confirmed it"
  else
    warn "batch $batch_id is $batch_status"
  fi

  step "Service 6 — the ops console finds it, by joining SOAP to the files on disk"
  "${CURL[@]}" "$OPS_URL/ops/v1/diagnostics/unconfirmed-batches?olderThanMinutes=0" | python3 -c "
import sys, json
for finding in json.load(sys.stdin):
    batch = finding['batch']
    print('--- %s | %s | stranded %s | file present: %s' % (
        batch['batchId'], batch['status'], finding['strandedAmount'], finding['settlementFilePresent']))
    print('    diagnosis: %s' % finding['diagnosis'])
    print('    remedy   : %s' % finding['suggestedRemedy'])
    for invoice in finding['invoices']:
        print('    invoice  : %s %s %s (%s)' % (invoice['invoiceNo'], invoice['status'],
                                                invoice['grossAmount'], invoice['paymentRef']))
    for line in finding['settlementFileLines']:
        print('    file     | %s' % line)
" | raw
  note "The seeded BATCH-20260925-001 is in there too, and is NOT repairable: its settlement file was"
  note "never written, so there is no record of what was sent. The console says so rather than guessing."

  step "Ops rebuilds the acknowledgement from the file that was actually sent"
  "${CURL[@]}" -X POST "$OPS_URL/ops/v1/remediation/billing/reconcile" \
    -H 'Content-Type: application/json' \
    --data-binary "{\"batchId\":\"$batch_id\",\"mode\":\"RE_DRIVE_ACK\"}" | pretty_json | raw

  step "The invoice is PAID"
  local settled
  settled="$(soap "<getPaymentBatchRequest xmlns=\"$NS\"><batchId>$batch_id</batchId></getPaymentBatchRequest>")"
  printf '%s' "$settled" | grep -oE '<ns2:(batchId|status|invoiceNo)>[^<]*' | sed 's|<ns2:||; s|>| = |' | raw
  if printf '%s' "$settled" | grep -q '<ns2:status>PAID<'; then
    ok "the stranded money is posted"
  else
    warn "the invoice has not reached PAID"
  fi

  step "And the unsafe case is refused, for the right reason"
  local refused http_code
  refused="$("${CURL[@]}" -o /tmp/demo-refused.json -w '%{http_code}' \
    -X POST "$OPS_URL/ops/v1/remediation/billing/reconcile" -H 'Content-Type: application/json' \
    --data-binary '{"batchId":"BATCH-20260925-001","mode":"RE_DRIVE_ACK"}')"
  cat /tmp/demo-refused.json 2>/dev/null | pretty_json | raw
  rm -f /tmp/demo-refused.json
  if [[ "$refused" == "409" ]]; then
    ok "HTTP 409: you cannot rebuild an acknowledgement from a file that does not exist"
  else
    warn "expected HTTP 409, got $refused"
  fi

  step "Put the clearing house back"
  "${CURL[@]}" -X POST "$BILLING_URL/sim/clearing-house/config" -H 'Content-Type: application/json' \
    --data-binary '{"ackEnabled":true}' | pretty_json | raw
}

# ============================================================================ main

section "PRE-FLIGHT"
require_up "catalog-service    (subsystem 1, REST + JDBC)" "$CATALOG_URL"
require_up "activation-service (subsystem 2, Flowable)   " "$ACTIVATION_URL"
require_up "billing-service    (subsystem 3, SOAP+batch) " "$BILLING_URL"
require_up "ops-console        (internal ops)            " "$OPS_URL"

step "All four interface styles, as the ops console sees them"
"${CURL[@]}" "$OPS_URL/ops/v1/diagnostics/overview?olderThanMinutes=0" | python3 -c "
import sys, json
d = json.load(sys.stdin)
for name, up in d['subsystemsReachable'].items():
    print('%-30s %s' % (name, 'reachable' if up else 'NOT REACHABLE'))
print()
print('stuck activations      : %s' % d['stuckActivationCount'])
print('unconfirmed settlements: %s (%s stranded)' % (d['unconfirmedSettlementCount'], d['strandedAmount']))
" | raw

case "${1:-all}" in
  all)   demo_happy; demo_stuck; demo_batch ;;
  happy) demo_happy ;;
  stuck) demo_stuck ;;
  batch) demo_batch ;;
  *) echo "usage: $0 [all|happy|stuck|batch]" >&2; exit 2 ;;
esac

section "DONE"
if [[ "$FAILURES" -eq 0 ]]; then
  printf '%s%s✓ everything the demo checked behaved as documented%s\n\n' "$B" "$GREEN" "$R"
  exit 0
else
  printf '%s%s✗ %s check(s) failed — see the ✗ lines above%s\n\n' "$B" "$RED" "$FAILURES" "$R"
  exit 1
fi
