"""Generic SOAP tool: billing's SOAP 1.1 endpoint. The model writes the request XML itself."""
from __future__ import annotations

import xml.etree.ElementTree as ET

import httpx
from langchain_core.tools import BaseTool
from langchain.tools import tool

from ._text import truncate
from ..config import BILLING_NS, SOAP_ENV_NS, Settings
from ..personas import Persona


def soap_operation(body_xml: str) -> str | None:
    """The operation a request element names (`<getInvoicesRequest>` -> "getInvoices"), or None.

    The persona guard needs the operation name, and the only honest place to read it is the XML
    the model actually wrote. Exactly one well-formed element is accepted.
    """
    if "<!DOCTYPE" in body_xml.upper():
        return None
    try:
        root = ET.fromstring(body_xml.strip())
    except ET.ParseError:
        return None
    local_name = root.tag.rsplit("}", 1)[-1]
    if not local_name.endswith("Request"):
        return None
    return local_name[: -len("Request")]


def make_soap_tool(persona: Persona, settings: Settings) -> BaseTool:

    @tool
    def soap_call(body_xml: str) -> str:
        """Call the billing SOAP service and return the raw SOAP response XML.

        The SOAP envelope is added for you; send only the request element.

        Args:
            body_xml: exactly one request element in the namespace
                http://mclsaat.hu/legacy/billing/v1, e.g.
                <someOperationRequest xmlns="http://mclsaat.hu/legacy/billing/v1">...</someOperationRequest>
        """
        operation = soap_operation(body_xml)
        if operation is None:
            return "ERROR: body_xml must be exactly one well-formed <...Request> element."
        if not persona.allows_soap(operation):
            return f"ERROR: the SOAP operation {operation} is not available to you."

        # ------------------------------------------------------------------------------------
        # TODO(you): wrap and send.
        #   1. envelope = (f'<soap:Envelope xmlns:soap="{SOAP_ENV_NS}"><soap:Body>'
        #                  f'{body_xml}</soap:Body></soap:Envelope>')
        #   2. httpx.Client(trust_env=False, timeout=settings.http_timeout_s).post(
        #          settings.billing_soap_url, content=envelope.encode("utf-8"),
        #          headers={"Content-Type": "text/xml; charset=utf-8"})
        #      (Same shape as soap() in scripts/demo.sh - compare if a call fails.)
        #   3. Return f"HTTP {status}\n{response.text}" through truncate(). A SOAP fault comes
        #      back as HTTP 500 with a coded <faultstring>: return it, do not raise.
        #   4. Catch httpx.HTTPError -> "ERROR: ...".
        # BILLING_NS is imported for you in case you want to check the namespace before sending;
        # optional - billing will fault on a wrong one anyway, and the model can read that fault.
        # ------------------------------------------------------------------------------------
        raise NotImplementedError("TODO(you): tools/soap_tool.py")

    return soap_call
