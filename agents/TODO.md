# Building the raw-arm agents: to-do

Scope: ROADMAP Week 8, "Build the agents". **Raw generic tools** (DL-028) on **LangGraph** (DL-029),
**one agent core, two personas** (customer, ops). MCP is Week 9 and is not here.

Every place you still have to write code is marked **`TODO(you)`**. To list them:

```bash
grep -rn "TODO(you)" agents/mclsaat_agents agents/tests
```

| File | What it is | State |
| --- | --- | --- |
| `mclsaat_agents/config.py` | ports, SOAP URL, DB login, paths, all from `agents/.env` | done |
| `mclsaat_agents/personas.py` | the customer/ops boundary as an allowlist table, plus prompt loading | done, tested |
| `mclsaat_agents/tools/*.py` | four dumb protocol pipes: HTTP, SOAP, SQL, files | guards done, **I/O is yours** |
| `mclsaat_agents/graph.py` | the LangGraph loop: `llm_call` → `tool_node` → … → END | wiring done, **nodes are yours** |
| `mclsaat_agents/prompts/*.md` | the two system prompts | role and rules done, **system knowledge is yours** |
| `mclsaat_agents/metrics.py` | tokens, latency, turns, cost → `agents/runs/runs.jsonl` | done, **price table is yours** |
| `mclsaat_agents/scenarios.py` | setup, fault injection, scripted first message, check | switches done, **setups/checks are yours** |
| `mclsaat_agents/run.py` | the CLI that ties it together | done |
| `tests/` | the persona boundary, graph compile, loop test (skipped) | done |

---

## 0. Setup prerequisites

Already done on branch `feat/agents-scaffold` (branched from `chore/update-roadmap`, where DL-028 and
DL-029 live):

- [x] `uv venv --python 3.12 agents/.venv`. The system Python is 3.14. The pins were resolved and
      tested on 3.12, so 3.12 is the safe choice.
- [x] `VIRTUAL_ENV=agents/.venv uv pip install -r agents/requirements.txt`. This installs
      langgraph 1.2.22, langchain 1.5.0, langchain-anthropic 1.7.10, httpx, psycopg 3, python-dotenv
      and pytest.
- [x] `.gitignore` covers `.venv/`, `__pycache__/`, `agents/.env` and `agents/runs/`.
- [x] `cd agents && .venv/bin/pytest -q` gives **28 passed, 1 skipped**, with no API key and no
      running system.

Still yours:

- [ ] `source agents/.venv/bin/activate`. Run every command below from `agents/`.
- [ ] `cp .env.example .env`, then set `ANTHROPIC_API_KEY`. To use another provider, change
      `AGENT_MODEL` (`"<provider>:<model>"`) and add its `langchain-<provider>` package to
      `requirements.txt`.
- [ ] Start the legacy system with **`scripts/run-local.sh`** (recommended for now) or
      `docker compose up --build`. Then run `scripts/demo.sh` once to get a known state.
      - The file tools read `BATCH_EXCHANGE_DIR`. Under run-local.sh that is
        `.local-run/batch-exchange` (the default). Under compose it is a named volume the host
        cannot see. Running the agent under compose later needs an agent container that mounts
        `batch-exchange:ro`, as ops-console does. That gets a DL entry when you do it.
- [ ] Smoke-test what the tools will hit:
      `curl --noproxy '*' localhost:8081/api/v1/plans` and
      `psql postgresql://ops_reader:ops_reader@localhost:5432/catalogdb -c 'select count(*) from catalog.plan'`.

## 1. Read (about 1 hour, two documents only)

- [ ] **Anthropic, [Building effective agents](https://www.anthropic.com/engineering/building-effective-agents).**
      Read *What are agents*, *Building block: the augmented LLM*, *Agents* and *Appendix 2:
      prompt engineering your tools*. This is the concept: an agent is an LLM using tools in a loop,
      based on feedback from the environment. Appendix 2 covers how to write the tool docstrings in
      `tools/*.py` and the prompts.
- [ ] **LangGraph, [Quickstart](https://docs.langchain.com/oss/python/langgraph/quickstart)**, the
      *Graph API* version only. Steps 1–6 map one-to-one onto `graph.py`:
      2 = `AgentState`, 3 = `llm_call`, 4 = `tool_node`, 5 = `should_continue`, 6 = the build at
      the bottom. Skip the block of instructions aimed at coding assistants and the Functional API.
      LangSmith (step 7) is optional.

## 2. Tools (`mclsaat_agents/tools/`)

- [ ] `http_tool.py`: an httpx request with `trust_env=False`. It returns status plus body,
      including on 4xx and 5xx.
- [ ] `soap_tool.py`: wrap the body in the envelope and POST it as `text/xml`. It returns faults as
      text.
- [ ] `sql_tool.py`: a psycopg read-only connection with a statement timeout, capped rows,
      tab-separated text.
- [ ] `file_tool.py`: `list_files` and `read_file`, verbatim.
- [ ] The rule for all four: **no translation inside a tool.** Raw values in, raw values out. The
      model translating is what is being measured.

## 3. The graph (`mclsaat_agents/graph.py`)

- [ ] `llm_call`: invoke with the system prompt and add up `usage_metadata`.
- [ ] `tool_node`: one `ToolMessage` per tool call, and never let an exception escape.
- [ ] `should_continue`: return `"tool_node"` if the last message has `tool_calls`, else `END`.
- [ ] Remove the `@pytest.mark.skip` in `tests/test_graph.py`, then run `pytest -q`. This should give
      29 passed. It proves the loop with a scripted fake model before you spend any tokens.
- [ ] First real call, with no tools needed:
      `python -m mclsaat_agents.run --persona customer --message "Hello, what can you do?"`.

## 4. Prompts (`mclsaat_agents/prompts/`)

- [ ] Choose level A, B or C (explained in the comment in `customer.md`). B is recommended. Record
      the choice (§8).
- [ ] Fill in `customer.md` from `docs/api-reference.md`, `billing-v1.xsd` and
      `semantic-mismatches.md`. Customer operations only.
- [ ] Fill in `ops.md`: the same customer sections, plus the ops paths, ops SOAP operations, the
      catalog tables (Flyway `V*.sql`) and the batch-file layout.

## 5. Metrics (`mclsaat_agents/metrics.py`)

- [ ] Fill `PRICES_PER_MTOK` from the provider's official pricing page, for each `AGENT_MODEL` you
      run.
- [ ] After a run, open `agents/runs/runs.jsonl` and `agents/runs/<run_id>.json` and check that
      tokens, `llm_calls`, `tool_calls` and latency look plausible.

## 6. Scenarios (`mclsaat_agents/scenarios.py`)

- [ ] `browse_order_poll`: write its check (no setup needed). Run it.
- [ ] `addon_purchase`: write `setup_addon` and the check.
- [ ] `invoice_pay`: write `setup_invoice_pay` and the check. The quoted amount must be billing's
      gross.
- [ ] `ops_stuck_activation`: write `setup_ops_stuck` (copy demo.sh "stuck") and the check.
- [ ] `ops_unconfirmed_batch`: write `setup_ops_batch` (copy demo.sh "batch") and the check.
      Teardown is done.
- [ ] Base every check on the expectations that `demo.sh` and the Java tests already pin. Do not
      invent new ones.

## 7. Run and verify

- [ ] **Reset to a known state before each measured series.** Runs change data: `invoice_pay` uses up
      open invoices, and an ops run may terminate the seeded `SUB-2026-000009`.
      - Compose: `docker compose down -v && docker compose up --build` wipes the volume, and Flyway
        reseeds it.
      - Local: `scripts/stop-local.sh --clean`, then `dropdb catalogdb flowabledb billingdb` as the
        postgres superuser, then `scripts/create-local-databases.sh` and `scripts/run-local.sh`.
- [ ] `python -m mclsaat_agents.run --list`, then run each scenario 3 times from a fresh reset.
      Three runs make variance visible, and a single run proves little.
- [ ] Read at least one transcript per scenario end to end. Did it translate `00000043` → `43`,
      `MOB-VOICE-0050` → `mob.voice.0050` and MB ↔ GB (1024) correctly? Note every silent wrong
      answer. These are the correctness results (`phase2-seams.md` §5).
- [ ] Re-run `scripts/demo.sh`. The agents must not have left the system in a state it cannot handle.

## 8. Decide and record (`docs/decision-log.md`, next free DL, probably DL-030)

- [ ] The prompt level (A, B or C) and why.
- [ ] **Can the raw ops agent use the ops console?** It is a pre-joined canonical layer. The two
      rules are at the end of `OPS_ONLY_HTTP` in `personas.py`; delete them if the answer is no.
      Diagnosis is not measured (DL-026 Q1), so either answer is defensible. The answer has to be
      written down.
- [ ] The persona allowlist table in `personas.py` is the persona boundary that DL-028 requires.
      Reference it.
- [ ] Then tick "Build the agents" in `ROADMAP.md` and update `docs/superpowers/STATUS.md`.

**Not here:** MCP servers (Week 9). Settle gap **2.2** (tool granularity) with the user *before*
defining any MCP tool (`AGENTS.md` §8). The generic raw tools here do not trigger that rule
(`phase2-seams.md` §8.6).
