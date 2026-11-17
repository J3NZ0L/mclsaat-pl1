"""The agent loop as an explicit LangGraph graph: one core, both personas (DL-029).

It mirrors the LangGraph quickstart's Graph API version step for step, so the reading maps onto
the code: State (quickstart step 2), llm_call (step 3), tool_node (step 4), should_continue
(step 5), build and compile (step 6).

        START -> llm_call --(last AI message asks for tools?)--> tool_node -> llm_call -> ...
                          |
                          +--(no)--> END

Deliberately not `create_agent` / `ToolNode` / `tools_condition`: they hide the loop you are here
to learn, and the per-call token counting the tokenomics measurement needs.
"""
from __future__ import annotations

import operator
from typing import Literal

from langchain_core.language_models import BaseChatModel
from langchain_core.runnables import Runnable
from langchain_core.tools import BaseTool
from langchain.messages import AIMessage, AnyMessage, HumanMessage, SystemMessage, ToolMessage
from langgraph.graph import END, START, StateGraph
from typing_extensions import Annotated, TypedDict


class AgentState(TypedDict):
    # operator.add makes LangGraph *append* the messages a node returns instead of replacing the
    # list. The other fields have no reducer, so a node's return value overwrites them.
    messages: Annotated[list[AnyMessage], operator.add]
    llm_calls: int
    tool_calls: int
    input_tokens: int
    output_tokens: int
    total_tokens: int


def initial_state(user_message: str) -> AgentState:
    return AgentState(
        messages=[HumanMessage(content=user_message)],
        llm_calls=0, tool_calls=0, input_tokens=0, output_tokens=0, total_tokens=0,
    )


def build_graph(
    model_with_tools: Runnable | BaseChatModel,
    tools: list[BaseTool],
    system_prompt: str,
):
    """Compile the agent graph. `model_with_tools` is `init_chat_model(...).bind_tools(tools)`."""
    tools_by_name = {t.name: t for t in tools}

    def llm_call(state: AgentState) -> dict:
        # ----------------------------------------------------------------------------------------
        # TODO(you): quickstart step 3, "Define model node", plus token accounting.
        #   1. response = model_with_tools.invoke([SystemMessage(content=system_prompt)] + state["messages"])
        #      The system prompt is prepended on every call and never stored in the state, so the
        #      transcript stays clean. (It still costs input tokens every call - that is real cost.)
        #   2. usage = response.usage_metadata or {}   # dict: input_tokens, output_tokens, total_tokens
        #      Providers that report no usage give None - default to zeros, do not crash.
        #   3. return {
        #          "messages": [response],                      # appended, thanks to operator.add
        #          "llm_calls": state.get("llm_calls", 0) + 1,
        #          "input_tokens": state.get("input_tokens", 0) + usage.get("input_tokens", 0),
        #          ... same for output_tokens and total_tokens ...
        #      }
        # ----------------------------------------------------------------------------------------
        raise NotImplementedError("TODO(you): graph.py llm_call")

    def tool_node(state: AgentState) -> dict:
        # ----------------------------------------------------------------------------------------
        # TODO(you): quickstart step 4, "Define tool node".
        #   last = state["messages"][-1]          # an AIMessage whose .tool_calls is a list of
        #                                         # {"name": ..., "args": {...}, "id": ...}
        #   For each call:
        #       tool = tools_by_name.get(call["name"])
        #       result = tool.invoke(call["args"]) if tool else f"ERROR: no tool named {call['name']}"
        #       results.append(ToolMessage(content=str(result), tool_call_id=call["id"]))
        #   Every tool_call MUST get exactly one ToolMessage with its id, or the next model call
        #   is rejected by the provider.
        #   Our tools return "ERROR: ..." instead of raising, but a TODO you have not written yet
        #   raises NotImplementedError: wrap tool.invoke in try/except Exception and turn it into
        #   an "ERROR: ..." ToolMessage too, so one broken tool does not kill the whole run.
        #   return {"messages": results, "tool_calls": state.get("tool_calls", 0) + len(results)}
        # ----------------------------------------------------------------------------------------
        raise NotImplementedError("TODO(you): graph.py tool_node")

    def should_continue(state: AgentState) -> Literal["tool_node", END]:
        # ----------------------------------------------------------------------------------------
        # TODO(you): quickstart step 5, "Define end logic".
        #   If the last message is an AIMessage with non-empty .tool_calls -> return "tool_node",
        #   otherwise -> return END.
        #   (A hard stop on runaway loops is already there: run.py passes recursion_limit.)
        # ----------------------------------------------------------------------------------------
        raise NotImplementedError("TODO(you): graph.py should_continue")

    # Quickstart step 6, "Build and compile the agent". Done for you; read it once the nodes work.
    builder = StateGraph(AgentState)
    builder.add_node("llm_call", llm_call)
    builder.add_node("tool_node", tool_node)
    builder.add_edge(START, "llm_call")
    builder.add_conditional_edges("llm_call", should_continue, ["tool_node", END])
    builder.add_edge("tool_node", "llm_call")
    return builder.compile()


def final_answer(state: AgentState) -> str:
    """The text of the last AI message: what the user would read."""
    for message in reversed(state["messages"]):
        if isinstance(message, AIMessage):
            return message.text if isinstance(message.text, str) else str(message.content)
    return ""
