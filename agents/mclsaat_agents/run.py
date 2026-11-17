"""Run one agent conversation and record its tokenomics.

    python -m mclsaat_agents.run --list
    python -m mclsaat_agents.run --scenario browse_order_poll
    python -m mclsaat_agents.run --persona customer --message "Which mobile plans do you have?"

No chat UI (that is Week 9): a scenario is a scripted first message.
"""
from __future__ import annotations

import argparse
import sys
import time

from langchain.chat_models import init_chat_model

from .config import load_settings
from .graph import build_graph, final_answer, initial_state
from .metrics import record_from_state, save_run
from .personas import PERSONAS
from .scenarios import SCENARIOS, Scenario
from .tools import tools_for


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true", help="list scenarios and exit")
    parser.add_argument("--scenario", choices=sorted(SCENARIOS))
    parser.add_argument("--persona", choices=sorted(PERSONAS), help="ad-hoc run, with --message")
    parser.add_argument("--message", help="ad-hoc first message")
    args = parser.parse_args(argv)

    if args.list:
        for s in SCENARIOS.values():
            print(f"{s.name:24} persona={s.persona:9} measured={s.measured}")
        return 0
    if args.scenario:
        scenario = SCENARIOS[args.scenario]
    elif args.persona and args.message:
        scenario = Scenario(name="adhoc", persona=args.persona, message=args.message, measured=False)
    else:
        parser.error("give --scenario, or --persona with --message")

    settings = load_settings()
    persona = PERSONAS[scenario.persona]
    tools = tools_for(persona, settings)
    model = init_chat_model(settings.model, temperature=0)
    graph = build_graph(model.bind_tools(tools), tools, persona.system_prompt())

    ctx: dict = {}
    try:
        # Inside the try: a setup that fails half-way (clearing house already silenced, payment not
        # made) must still be undone by teardown, or every later run - and demo.sh - inherits it.
        ctx = scenario.setup(settings)
        state = initial_state(scenario.message.format(**ctx))
        outcome = "completed"
        started = time.perf_counter()
        try:
            # stream(), not invoke(): each chunk is the full state after one step, so when a run dies
            # (a looping agent hitting recursion_limit, a provider error) we still hold the tokens it
            # spent. With invoke() a failed run would be recorded as free, which flatters exactly the
            # arm most likely to loop.
            for state in graph.stream(state, config={"recursion_limit": settings.recursion_limit},
                                      stream_mode="values"):
                pass
        except Exception as e:  # recorded, not swallowed: the record says the run failed and why
            outcome = f"error: {type(e).__name__}: {e}"
        latency_s = time.perf_counter() - started
    finally:
        scenario.teardown(settings, ctx)

    for message in state["messages"]:
        message.pretty_print()

    check_passed: bool | None = None
    if outcome == "completed":
        try:
            check_passed = scenario.check(settings, ctx, final_answer(state))
        except NotImplementedError:
            check_passed = None

    record = record_from_state(state, persona=persona.name, scenario=scenario.name,
                               model=settings.model, latency_s=latency_s,
                               outcome=outcome, check_passed=check_passed)
    transcript = save_run(record, state["messages"], settings.runs_dir)
    print(f"\n{record}\ntranscript: {transcript}")
    return 0 if outcome == "completed" and check_passed is not False else 1


if __name__ == "__main__":
    sys.exit(main())
