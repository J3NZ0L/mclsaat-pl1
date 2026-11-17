"""The agent loop, driven by a scripted fake model: no API key, no network, no legacy system."""
import pytest
from langchain.messages import AIMessage
from langchain.tools import tool
from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel

from mclsaat_agents.config import load_settings
from mclsaat_agents.graph import build_graph, final_answer, initial_state
from mclsaat_agents.personas import CUSTOMER, OPS
from mclsaat_agents.tools import tools_for


@tool
def echo(text: str) -> str:
    """Return the text unchanged."""
    return f"echo: {text}"


def test_the_graph_compiles_for_both_personas():
    settings = load_settings()
    for persona in (CUSTOMER, OPS):
        tools = tools_for(persona, settings)
        graph = build_graph(FakeMessagesListChatModel(responses=[]), tools, persona.system_prompt())
        assert set(graph.get_graph().nodes) >= {"llm_call", "tool_node"}


def test_each_persona_gets_exactly_its_tools():
    settings = load_settings()
    assert [t.name for t in tools_for(CUSTOMER, settings)] == ["http_request", "soap_call"]
    assert [t.name for t in tools_for(OPS, settings)] == [
        "http_request", "soap_call", "sql_query", "list_files", "read_file"]


# TODO(you): remove this skip once graph.py's three TODOs are written. It is the proof your loop
# works before you spend a single real token: model asks for a tool -> tool runs -> model answers.
@pytest.mark.skip(reason="enable after the TODO(you) blocks in graph.py are written")
def test_the_loop_runs_a_tool_and_stops_when_the_model_answers():
    scripted = FakeMessagesListChatModel(responses=[
        AIMessage(content="", tool_calls=[{"name": "echo", "args": {"text": "hi"}, "id": "call-1"}],
                  usage_metadata={"input_tokens": 10, "output_tokens": 5, "total_tokens": 15}),
        AIMessage(content="done",
                  usage_metadata={"input_tokens": 20, "output_tokens": 3, "total_tokens": 23}),
    ])
    graph = build_graph(scripted, [echo], "You are a test.")

    state = graph.invoke(initial_state("say hi"))

    assert final_answer(state) == "done"
    assert state["messages"][2].content == "echo: hi"  # human, ai(tool call), tool, ai
    assert state["llm_calls"] == 2 and state["tool_calls"] == 1
    assert (state["input_tokens"], state["output_tokens"], state["total_tokens"]) == (30, 8, 38)
