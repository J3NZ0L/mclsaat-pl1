"""The persona boundary (DL-028): fast, no network, no model."""
from pathlib import Path

import pytest

from mclsaat_agents.personas import CUSTOMER, OPS
from mclsaat_agents.tools.file_tool import resolve_under_root
from mclsaat_agents.tools.soap_tool import soap_operation
from mclsaat_agents.tools.sql_tool import is_select_only

NS = "http://mclsaat.hu/legacy/billing/v1"


@pytest.mark.parametrize("service,method,path", [
    ("catalog", "GET", "/api/v1/plans"),
    ("catalog", "GET", "/api/v1/plans/MOB-VOICE-0010"),
    ("catalog", "GET", "/api/v1/subscribers/00000043/subscriptions"),
    ("catalog", "GET", "/api/v1/subscriptions/SUB-2026-000002"),
    ("activation", "POST", "/activation/v1/orders"),
    ("activation", "GET", "/activation/v1/orders"),
])
def test_the_customer_can_reach_the_customer_facing_services(service, method, path):
    assert CUSTOMER.allows_http(service, method, path)
    assert OPS.allows_http(service, method, path)


@pytest.mark.parametrize("service,method,path", [
    ("activation", "GET", "/activation/v1/ops/stuck-orders"),
    ("activation", "POST", "/activation/v1/ops/orders/cancel"),
    ("catalog", "GET", "/api/v1/subscriptions/stale-pending"),
    ("catalog", "POST", "/api/v1/subscriptions/SUB-2026-000009/terminate"),
    ("ops_console", "GET", "/ops/v1/diagnostics/overview"),
])
def test_ops_only_paths_are_closed_to_the_customer_and_open_to_ops(service, method, path):
    assert not CUSTOMER.allows_http(service, method, path)
    assert OPS.allows_http(service, method, path)


@pytest.mark.parametrize("service,method,path", [
    ("activation", "POST", "/activation/v1/ops/provisioning-platform"),
    ("activation", "POST", "/activation/v1/callbacks/provisioning"),
    ("ops_console", "POST", "/ops/v1/remediation/billing/export-batch"),
    ("ops_console", "POST", "/ops/v1/remediation/fault-injection/provisioning-callbacks"),
    ("catalog", "POST", "/api/v1/subscriptions/SUB-2026-000002/activate"),
])
def test_harness_controls_and_internal_calls_are_closed_to_both_personas(service, method, path):
    assert not CUSTOMER.allows_http(service, method, path)
    assert not OPS.allows_http(service, method, path)


@pytest.mark.parametrize("path", [
    "/activation/v1/orders/../ops/stuck-orders",
    "/activation/v1/orders?x=/activation/v1/ops/stuck-orders",
    "/activation/v1/%6Fps/stuck-orders",
    "//activation/v1/orders",
])
def test_path_tricks_do_not_slip_past_the_allowlist(path):
    assert not CUSTOMER.allows_http("activation", "GET", path)


def test_the_guard_checks_who_may_call_not_whether_the_argument_is_well_formed():
    # The catalog wants "00000043"; "43" is a semantic mistake the raw arm must be allowed to make.
    assert CUSTOMER.allows_http("catalog", "GET", "/api/v1/subscribers/43")


def test_soap_operations_follow_the_persona():
    pay = f'<payInvoiceRequest xmlns="{NS}"><invoiceNo>2026/INV/000003</invoiceNo></payInvoiceRequest>'
    reconcile = f'<reconcileBatchRequest xmlns="{NS}"><batchId>B</batchId></reconcileBatchRequest>'
    export = f'<exportPaymentBatchRequest xmlns="{NS}"/>'
    assert soap_operation(pay) == "payInvoice" and CUSTOMER.allows_soap("payInvoice")
    assert soap_operation(reconcile) == "reconcileBatch"
    assert not CUSTOMER.allows_soap("reconcileBatch") and OPS.allows_soap("reconcileBatch")
    assert soap_operation(export) == "exportPaymentBatch"
    assert not OPS.allows_soap("exportPaymentBatch") and not OPS.allows_soap("createInvoice")


def test_malformed_or_smuggled_soap_bodies_have_no_operation():
    assert soap_operation("<a/><b/>") is None
    assert soap_operation('<!DOCTYPE x [<!ENTITY e "y">]><getInvoicesRequest/>') is None
    assert soap_operation(f'<invoice xmlns="{NS}"/>') is None


def test_only_a_single_select_is_allowed():
    assert is_select_only("SELECT cust_no FROM catalog.subscriber WHERE cust_no = '00000043';")
    assert is_select_only("with x as (select 1) select * from x")
    assert not is_select_only("SELECT 1; DELETE FROM catalog.subscriber")
    assert not is_select_only("UPDATE catalog.subscription SET status_code = 'AC'")
    assert not is_select_only("SELECT * FROM catalog.subscription FOR UPDATE")


def test_file_access_stays_under_the_exchange_root(tmp_path: Path):
    assert resolve_under_root(tmp_path, "outbox/PMT-BATCH-1.txt") == (tmp_path / "outbox/PMT-BATCH-1.txt").resolve()
    assert resolve_under_root(tmp_path, "../secrets") is None
    assert resolve_under_root(tmp_path, "/etc/passwd") is None


def test_only_ops_gets_the_database_and_the_files():
    assert not CUSTOMER.sql and not CUSTOMER.files
    assert OPS.sql and OPS.files
