"""Shared result formatting for the tools."""
from __future__ import annotations


def truncate(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    return f"{text[:limit]}\n...[truncated: {len(text) - limit} more characters not shown]"
