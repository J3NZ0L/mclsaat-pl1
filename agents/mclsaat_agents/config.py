"""Where the legacy landscape lives, read from the environment (agents/.env, see .env.example).

The defaults match both `docker compose up` and `scripts/run-local.sh`, which publish the same
localhost ports.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

AGENTS_DIR = Path(__file__).resolve().parent.parent
REPO_ROOT = AGENTS_DIR.parent

load_dotenv(AGENTS_DIR / ".env")

BILLING_NS = "http://mclsaat.hu/legacy/billing/v1"
SOAP_ENV_NS = "http://schemas.xmlsoap.org/soap/envelope/"


@dataclass(frozen=True)
class Settings:
    model: str
    # Services the generic HTTP tool can address by name. Billing is not one: its only REST
    # endpoints are the Stripe webhook and the clearing-house fault switch, neither for an agent.
    base_urls: dict[str, str]
    billing_url: str  # harness only (fault injection), never handed to a tool
    billing_soap_url: str
    catalog_db_dsn: str
    batch_exchange_dir: Path
    runs_dir: Path
    http_timeout_s: float
    # Raw responses can be large (a full plan list, a SOAP invoice list). Truncate rather than blow
    # the context window, and say so in the result so the model knows it saw a part.
    max_result_chars: int
    recursion_limit: int


def load_settings() -> Settings:
    env = os.environ.get
    return Settings(
        model=env("AGENT_MODEL", "anthropic:claude-sonnet-5-5"),
        base_urls={
            "catalog": env("CATALOG_URL", "http://localhost:8081"),
            "activation": env("ACTIVATION_URL", "http://localhost:8082"),
            "ops_console": env("OPS_URL", "http://localhost:8080"),
        },
        billing_url=env("BILLING_URL", "http://localhost:8083"),
        billing_soap_url=env("BILLING_SOAP_URL", "http://localhost:8083/ws"),
        catalog_db_dsn=env(
            "CATALOG_DB_DSN", "postgresql://ops_reader:ops_reader@localhost:5432/catalogdb"
        ),
        batch_exchange_dir=Path(
            env("BATCH_EXCHANGE_DIR", str(REPO_ROOT / ".local-run" / "batch-exchange"))
        ),
        runs_dir=Path(env("AGENT_RUNS_DIR", str(AGENTS_DIR / "runs"))),
        http_timeout_s=float(env("AGENT_HTTP_TIMEOUT_S", "30")),
        max_result_chars=int(env("AGENT_MAX_RESULT_CHARS", "12000")),
        recursion_limit=int(env("AGENT_RECURSION_LIMIT", "40")),
    )
