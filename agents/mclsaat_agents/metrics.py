"""Tokenomics records: one JSON line per run (DL-029 lists the fields).

Written to agents/runs/runs.jsonl, with the full transcript beside it in
agents/runs/<run_id>.json, so the dashboard (ROADMAP Week 8) can show both later.
"""
from __future__ import annotations

import json
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from langchain_core.messages import messages_to_dict

# USD per million tokens: (input, output).
# ------------------------------------------------------------------------------------------------
# TODO(you): fill in from the provider's official pricing page for every model you run, keyed by
# the exact AGENT_MODEL string, e.g. "anthropic:claude-sonnet-5-5": (in_price, out_price).
# Do not guess the numbers. A model missing here gets estimated_cost_usd = null, not 0.
# ------------------------------------------------------------------------------------------------
PRICES_PER_MTOK: dict[str, tuple[float, float]] = {}


def estimate_cost(model: str, input_tokens: int, output_tokens: int) -> float | None:
    price = PRICES_PER_MTOK.get(model)
    if price is None:
        return None
    return round((input_tokens * price[0] + output_tokens * price[1]) / 1_000_000, 6)


@dataclass
class RunRecord:
    persona: str
    scenario: str
    model: str
    latency_s: float
    llm_calls: int
    tool_calls: int
    input_tokens: int
    output_tokens: int
    total_tokens: int
    estimated_cost_usd: float | None
    outcome: str                 # "completed" | "error: <ExceptionType>"
    check_passed: bool | None    # None = no check written yet, or the run did not complete
    arm: str = "raw"             # "raw" now; "mcp" once Week 9 exists (DL-028: a 2-arm comparison)
    run_id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    started_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())


def record_from_state(state: dict, *, persona: str, scenario: str, model: str,
                      latency_s: float, outcome: str, check_passed: bool | None) -> RunRecord:
    input_tokens = state.get("input_tokens", 0)
    output_tokens = state.get("output_tokens", 0)
    return RunRecord(
        persona=persona, scenario=scenario, model=model, latency_s=round(latency_s, 3),
        llm_calls=state.get("llm_calls", 0), tool_calls=state.get("tool_calls", 0),
        input_tokens=input_tokens, output_tokens=output_tokens,
        total_tokens=state.get("total_tokens", 0),
        estimated_cost_usd=estimate_cost(model, input_tokens, output_tokens),
        outcome=outcome, check_passed=check_passed,
    )


def save_run(record: RunRecord, messages: list, runs_dir: Path) -> Path:
    runs_dir.mkdir(parents=True, exist_ok=True)
    with (runs_dir / "runs.jsonl").open("a", encoding="utf-8") as out:
        out.write(json.dumps(asdict(record), ensure_ascii=False) + "\n")
    transcript = runs_dir / f"{record.run_id}.json"
    transcript.write_text(
        json.dumps({"record": asdict(record), "messages": messages_to_dict(messages)},
                   ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return transcript
