"""Generic SQL tool: read-only queries against the catalog's database (ops persona only).

The real guard is the database login: `ops_reader` has SELECT on schema `catalog` and nothing else
(docker/postgres-init.sql), exactly what the ops console uses. The keyword check below is a second
line that fails fast with a readable message instead of a permission error.
"""
from __future__ import annotations

import re

import psycopg
from langchain_core.tools import BaseTool
from langchain.tools import tool

from ._text import truncate
from ..config import Settings

_WRITE_KEYWORDS = re.compile(
    r"\b(insert|update|delete|merge|upsert|drop|alter|create|truncate|grant|revoke|copy|call|do"
    r"|lock|vacuum|set|reset|begin|commit|rollback|prepare|execute|listen|notify)\b",
    re.IGNORECASE,
)


def is_select_only(sql: str) -> bool:
    statement = sql.strip().rstrip(";").strip()
    if not statement or ";" in statement:  # one statement only
        return False
    if not re.match(r"(select|with)\b", statement, re.IGNORECASE):
        return False
    return _WRITE_KEYWORDS.search(statement) is None


def make_sql_tool(settings: Settings) -> BaseTool:

    @tool
    def sql_query(sql: str) -> str:
        """Run one read-only SQL SELECT against the catalog's PostgreSQL database; returns rows as text.

        Args:
            sql: a single SELECT (or WITH ... SELECT) statement. Tables live in schema "catalog".
        """
        if not is_select_only(sql):
            return "ERROR: only a single read-only SELECT statement is allowed."

        # ------------------------------------------------------------------------------------
        # TODO(you): run it read-only and return the rows as text.
        #   1. with psycopg.connect(settings.catalog_db_dsn, connect_timeout=10) as conn:
        #          conn.read_only = True          # belt and braces on top of the read-only login
        #          with conn.cursor() as cur:
        #              cur.execute("SET statement_timeout = '10s'")
        #              cur.execute(sql)
        #              columns = [d.name for d in cur.description]
        #              rows = cur.fetchmany(200)   # cap rows; say so in the output if capped
        #   2. Format as text the model can read: a header line of column names, then one
        #      tab-separated line per row. Keep values exactly as the database returns them
        #      (cust_no "00000042", data_allowance_mb -1, status_code 'PA'): no translation here.
        #   3. Pass the text through truncate(..., settings.max_result_chars).
        #   4. Catch psycopg.Error -> f"ERROR: {e}" (the model can fix a wrong column name).
        # Tip: the schema the model must know is in catalog-service/src/main/resources/db/migration/.
        # ------------------------------------------------------------------------------------
        raise NotImplementedError("TODO(you): tools/sql_tool.py")

    return sql_query
