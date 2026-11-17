"""One agent core, two personas: a system prompt plus a tool allowlist (phase2-seams.md §0).

There is no authentication anywhere (DL-012), so with generic tools the persona boundary has to be
enforced inside the tools (DL-028). This module is that boundary, written as data.

Rule of thumb for every pattern below: it enforces *who may call what*, never *what an argument
should look like*. `GET /api/v1/subscribers/43` (wrong, the catalog wants "00000043") must reach the
catalog and come back 404, because the raw arm is measuring exactly that kind of mistake. A tight
regex would turn it into "not allowed" and hide it.

Sources: docs/api-reference.md and phase2-seams.md §3. Harness-only controls (DL-026 Q8) are in no
persona: /sim/clearing-house/config, /activation/v1/ops/provisioning-platform, the ops console's
fault-injection and export-batch, SOAP exportPaymentBatch and createInvoice, /webhook/stripe and
the Stripe stand-in.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

PROMPTS_DIR = Path(__file__).parent / "prompts"


@dataclass(frozen=True)
class HttpRule:
    service: str
    method: str
    path: str  # a regex that must match the whole path

    def matches(self, service: str, method: str, path: str) -> bool:
        return (
            self.service == service
            and self.method == method.upper()
            and re.fullmatch(self.path, path) is not None
        )


@dataclass(frozen=True)
class Persona:
    name: str
    prompt_file: str
    http_rules: tuple[HttpRule, ...]
    soap_operations: frozenset[str]
    sql: bool
    files: bool

    def system_prompt(self) -> str:
        return (PROMPTS_DIR / self.prompt_file).read_text(encoding="utf-8")

    def allows_http(self, service: str, method: str, path: str) -> bool:
        # Structural tricks that could make an allowed-looking path reach somewhere else. The query
        # string travels separately (order numbers contain slashes, so they are always query
        # parameters - DL-019), so a path never legitimately needs any of these.
        if not path.startswith("/") or any(bad in path for bad in ("..", "//", "%", "?", "#", "\\")):
            return False
        return any(rule.matches(service, method, path) for rule in self.http_rules)

    def allows_soap(self, operation: str) -> bool:
        return operation in self.soap_operations


_ANY_SEGMENT = r"[^/]+"

CUSTOMER_HTTP = (
    # Service 1: browse plans (and one plan).
    HttpRule("catalog", "GET", r"/api/v1/plans(/[^/]+)?"),
    # Onboarding and "what do I have" (gap 1.2).
    HttpRule("catalog", "POST", r"/api/v1/subscribers"),
    HttpRule("catalog", "GET", rf"/api/v1/subscribers/{_ANY_SEGMENT}(/subscriptions)?"),
    # One subscription, but not the ops-only stale-pending listing that shares the prefix.
    HttpRule("catalog", "GET", r"/api/v1/subscriptions/(?!stale-pending$)[^/]+"),
    # Services 2, 3 and 4: place an order (NEW_SUBSCRIPTION / PLAN_CHANGE / ADDON) and poll it.
    HttpRule("activation", "POST", r"/activation/v1/orders"),
    HttpRule("activation", "GET", r"/activation/v1/orders"),
)

OPS_ONLY_HTTP = (
    # Failure branch A: activation's half, and its two remedies.
    HttpRule("activation", "GET", r"/activation/v1/ops/stuck-orders"),
    HttpRule("activation", "POST", r"/activation/v1/ops/orders/(force-provision|cancel)"),
    # Failure branch A: the catalog's half, and the orphan remedy (DL-026 criterion 5).
    HttpRule("catalog", "GET", r"/api/v1/subscriptions/stale-pending"),
    HttpRule("catalog", "POST", rf"/api/v1/subscriptions/{_ANY_SEGMENT}/terminate"),
    # The ops console: a pre-joined, canonical layer over subsystem-clients. Whether the RAW arm may
    # use it is an open decision (see TODO.md, "Decide"). Delete these two rules if the answer is no.
    HttpRule("ops_console", "GET", r"/ops/v1/diagnostics/(stuck-activations|unconfirmed-batches|overview)"),
    HttpRule(
        "ops_console", "POST",
        r"/ops/v1/remediation/(activation/(force-provision|cancel)|billing/reconcile)",
    ),
)

CUSTOMER_SOAP = frozenset({"getInvoices", "payInvoice", "startPayment"})
OPS_ONLY_SOAP = frozenset({"listUnconfirmedBatches", "getPaymentBatch", "reconcileBatch"})

CUSTOMER = Persona(
    name="customer",
    prompt_file="customer.md",
    http_rules=CUSTOMER_HTTP,
    soap_operations=CUSTOMER_SOAP,
    sql=False,
    files=False,
)

# Ops uses the same chat infrastructure with wider permissions (INITIAL_DESIGN.md), so it gets
# everything the customer has, plus the ops-only operations, the catalog database and the files.
OPS = Persona(
    name="ops",
    prompt_file="ops.md",
    http_rules=CUSTOMER_HTTP + OPS_ONLY_HTTP,
    soap_operations=CUSTOMER_SOAP | OPS_ONLY_SOAP,
    sql=True,
    files=True,
)

PERSONAS: dict[str, Persona] = {p.name: p for p in (CUSTOMER, OPS)}
