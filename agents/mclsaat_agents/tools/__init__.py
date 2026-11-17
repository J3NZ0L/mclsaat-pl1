"""The raw arm's tools: four generic protocol pipes (DL-028).

Every tool here is deliberately dumb. It moves bytes across one protocol and returns what came back,
as text. It never translates an identifier, a unit, a date, a status or a price: the model doing
that from the prompt *is* what the raw arm measures. If you catch yourself adding "helpful"
normalisation to a tool, that belongs in the MCP arm (Week 9), not here.

Two conventions all four share:
* A tool never raises. Errors go back to the model as a string starting with "ERROR:", so it can
  read them and try again. (An exception would crash the graph instead.)
* Results longer than Settings.max_result_chars are truncated, with a marker saying so.
"""
from __future__ import annotations

from langchain_core.tools import BaseTool

from ..config import Settings
from ..personas import Persona
from .file_tool import make_file_tools
from .http_tool import make_http_tool
from .soap_tool import make_soap_tool
from .sql_tool import make_sql_tool


def tools_for(persona: Persona, settings: Settings) -> list[BaseTool]:
    tools: list[BaseTool] = [make_http_tool(persona, settings), make_soap_tool(persona, settings)]
    if persona.sql:
        tools.append(make_sql_tool(settings))
    if persona.files:
        tools.extend(make_file_tools(settings))
    return tools
