"""The scenario harness: everything around an agent run that the agent must not do itself.

phase2-seams.md §8 point 4: each run needs a known starting state, and the failure setups
(silencing the provisioning platform or the clearing house) are demo controls, not agent tools
(DL-026 Q8). So they live here, outside the agent, and talk to the system directly.

A scenario is: setup (returns a context dict) -> scripted first message (formatted with that
context) -> agent run -> check (judges the outcome against the *system's* state, not only the
answer's wording) -> teardown (always runs).

The three `measured=True` scenarios are the tokenomics transactions of phase2-seams.md §5; the ops
scenarios demonstrate the agent's value but are outside the measurement (DL-026 Q1).

Runs change the system: invoice_pay uses up OPEN invoices, and an ops run may terminate the seeded
SUB-2026-000009 that DL-007 needs findable from a cold start. Runs are only comparable from the
same starting state, so reset the landscape between measured series (TODO.md §7).

Teardown is called even when setup fails half-way, with whatever context exists (possibly {}), so
it must not assume setup finished.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

import httpx

from .config import Settings

# The demo customer: has a billing account in all three subsystems (scripts/demo.sh).
DEMO_CUSTOMER_REF = 43


def _client(settings: Settings) -> httpx.Client:
    return httpx.Client(trust_env=False, timeout=settings.http_timeout_s)


# --- fault injection: harness-only controls ------------------------------------------------------

def set_provisioning_callbacks(settings: Settings, enabled: bool) -> None:
    """The simulated provisioning platform's global off switch (failure branch A)."""
    with _client(settings) as http:
        http.post(f"{settings.base_urls['activation']}/activation/v1/ops/provisioning-platform",
                  json={"callbacksEnabled": enabled}).raise_for_status()


def set_clearing_house_ack(settings: Settings, enabled: bool) -> None:
    """The clearing house's acknowledgement switch (failure branch B)."""
    with _client(settings) as http:
        http.post(f"{settings.billing_url}/sim/clearing-house/config",
                  json={"ackEnabled": enabled}).raise_for_status()


# --- the scenario type ---------------------------------------------------------------------------

def _no_setup(settings: Settings) -> dict:
    return {}


def _no_teardown(settings: Settings, ctx: dict) -> None:
    return None


def _not_written_yet(settings: Settings, ctx: dict, answer: str) -> bool | None:
    raise NotImplementedError


@dataclass(frozen=True)
class Scenario:
    name: str
    persona: str
    message: str  # str.format()-ed with the dict setup() returns
    measured: bool
    setup: Callable[[Settings], dict] = _no_setup
    check: Callable[[Settings, dict, str], bool | None] = _not_written_yet
    teardown: Callable[[Settings, dict], None] = _no_teardown


# --- setups and checks ---------------------------------------------------------------------------
# TODO(you): write these one scenario at a time, in the order of SCENARIOS below. Each check should
# query the legacy system (with _client, directly - not through the agent's tools) and compare with
# the expectations the Java tests and demo.sh already pin; do not invent new expectations
# (phase2-seams.md §5). Return True/False. run.py records `None` while a check raises
# NotImplementedError, so you can run scenarios before their checks exist.

def setup_addon(settings: Settings) -> dict:
    # TODO(you): find an active mobile base subscription of the demo customer and return
    # {"sub_id": "SUB-2026-..."}: GET {catalog}/api/v1/subscribers/00000043/subscriptions,
    # pick status_code "AC" on a MOB-* base plan. Run browse_order_poll first if there is none.
    raise NotImplementedError("TODO(you): scenarios.py setup_addon")


def setup_invoice_pay(settings: Settings) -> dict:
    # TODO(you): find an OPEN invoice of the demo customer over SOAP (getInvoicesRequest with
    # customerRef 43 and status OPEN - the same call demo.sh makes) and return
    # {"sub_id": <its subscriptionRef>, "invoice_no": ..., "gross": ...}. The check then asserts
    # that invoice moved to SETTLEMENT_PENDING or PAID, and that the answer quotes the *billing*
    # gross (5990.01, not 5990.00, for MOB-VOICE-0010 - DL-014/DL-025 Q6).
    raise NotImplementedError("TODO(you): scenarios.py setup_invoice_pay")


def setup_ops_stuck(settings: Settings) -> dict:
    # TODO(you): place an order the platform will ignore, exactly like demo.sh's "stuck" part:
    # POST {activation}/activation/v1/orders with customerRef 101, offerId "mob.voice.0010",
    # simulateStuck true, provisioningTimeout "PT5S" (copy the full body from demo.sh), then wait
    # until GET ...orders?orderNo=... reports STUCK. Return {"order_no": ...}.
    # The check, on system state rather than wording: that order is PROVISIONED (force-provision)
    # or CANCELLED, and the seeded SUB-2026-000009 is no longer PA in the catalog (terminated) or
    # is named in the answer as needing a human. Do not match the label
    # ORPHANED_PENDING_SUBSCRIPTION: only the ops console says it, and the raw agent may not use it.
    raise NotImplementedError("TODO(you): scenarios.py setup_ops_stuck")


def setup_ops_batch(settings: Settings) -> dict:
    # TODO(you): set_clearing_house_ack(settings, False), then pay an OPEN invoice of customer 42
    # over SOAP payInvoice (demo.sh "batch" part) and return {"batch_id": <batchId from the
    # response>}. teardown_ops_batch puts the clearing house back.
    # The check: getPaymentBatch shows the batch acknowledged and its invoices PAID.
    raise NotImplementedError("TODO(you): scenarios.py setup_ops_batch")


def teardown_ops_batch(settings: Settings, ctx: dict) -> None:
    set_clearing_house_ack(settings, True)


SCENARIOS: dict[str, Scenario] = {s.name: s for s in (
    # --- measured: the three transactions of phase2-seams.md §5 ---------------------------------
    # The messages use the *catalog's* dialect on purpose ("00000043", "MOB-VOICE-0050"): the agent
    # has to translate them for activation (43, "mob.voice.0050"). Keep them identical across arms.
    Scenario(
        name="browse_order_poll", persona="customer", measured=True,
        message=("I am customer 00000043. Which mobile voice plans do you offer, with monthly price "
                 "and data allowance? Then subscribe me to MOB-VOICE-0050 and tell me once it is "
                 "active."),
        # TODO(you): a check: activation's newest order for customerRef 43 is PROVISIONED with
        # offerId mob.voice.0050, and the catalog shows the matching subscription as AC.
    ),
    Scenario(
        name="addon_purchase", persona="customer", measured=True, setup=setup_addon,
        message=("I am customer 00000043. Add a 5 GB data pack to my subscription {sub_id}, and "
                 "tell me my new total data allowance in GB."),
        # TODO(you): a check on the allowance in GB, using 1024 MB per GB (semantic-mismatches.md).
    ),
    Scenario(
        name="invoice_pay", persona="customer", measured=True, setup=setup_invoice_pay,
        message=("I am customer 00000043. Do I have any unpaid invoices for subscription {sub_id}? "
                 "If so, pay it and tell me exactly how much was charged."),
    ),
    # --- not measured: the two failure branches (DL-026 Q1) --------------------------------------
    Scenario(
        name="ops_stuck_activation", persona="ops", measured=False, setup=setup_ops_stuck,
        message=("Customers report subscriptions that never become active. Find every stuck or "
                 "orphaned activation, fix what can safely be fixed, and report what you did and "
                 "what still needs a human."),
    ),
    Scenario(
        name="ops_unconfirmed_batch", persona="ops", measured=False, setup=setup_ops_batch,
        teardown=teardown_ops_batch,
        message=("A customer says a card payment from a few minutes ago has not been posted. Find "
                 "unconfirmed settlement batches and resolve them; explain your choice of remedy."),
    ),
)}
