"""Generic HTTP tool: catalog REST, activation REST and (ops only) the ops console."""
from __future__ import annotations

from typing import Any

import httpx
from langchain_core.tools import BaseTool
from langchain.tools import tool

from ._text import truncate
from ..config import Settings
from ..personas import Persona


def make_http_tool(persona: Persona, settings: Settings) -> BaseTool:
    # A factory, because the tool must remember which persona it guards. The docstring below is
    # what the model reads to decide how to call the tool: keep it accurate and short.

    @tool
    def http_request(
        service: str,
        method: str,
        path: str,
        query: dict[str, str] | None = None,
        json_body: dict[str, Any] | None = None,
    ) -> str:
        """Send one HTTP request to a REST service and return the raw status code and response body.

        Args:
            service: which service to call: "catalog", "activation" or "ops_console".
            method: "GET" or "POST".
            path: URL path starting with "/", with no query string, e.g. "/api/v1/plans".
            query: optional query-string parameters, e.g. {"serviceKind": "M"}.
            json_body: optional JSON request body, for POST.
        """
        base_url = settings.base_urls.get(service)
        if base_url is None:
            return f"ERROR: unknown service {service!r}; use one of {sorted(settings.base_urls)}"
        if not persona.allows_http(service, method, path):
            return f"ERROR: {method.upper()} {path} on {service} is not available to you."

        # ------------------------------------------------------------------------------------
        # TODO(you): send the request and return the result as text.
        #   1. httpx.Client(trust_env=False, timeout=settings.http_timeout_s)
        #      trust_env=False matters: an HTTP(S)_PROXY in your environment would otherwise
        #      swallow localhost calls (AGENTS.md §5 - the same reason demo.sh uses --noproxy).
        #   2. client.request(method.upper(), base_url + path, params=query, json=json_body)
        #   3. Return f"HTTP {response.status_code}\n{response.text}", passed through
        #      truncate(..., settings.max_result_chars).
        #      Return the body for 4xx/5xx too: a 400 from activation explaining that
        #      "MOB-VOICE-0010" is not an offerId is exactly the signal the model needs.
        #   4. Catch httpx.HTTPError and return f"ERROR: {type(e).__name__}: {e}" instead of raising.
        # ------------------------------------------------------------------------------------
        raise NotImplementedError("TODO(you): tools/http_tool.py")

    return http_request
