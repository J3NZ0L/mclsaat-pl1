"""Generic file tools: read-only access to billing's settlement-file exchange (ops persona only).

Rooted at Settings.batch_exchange_dir (outbox/, inbox/, archive/). The ops console mounts the same
directory read-only; this is the agent's equivalent.
"""
from __future__ import annotations

from pathlib import Path

from langchain_core.tools import BaseTool
from langchain.tools import tool

from ._text import truncate
from ..config import Settings


def resolve_under_root(root: Path, relative: str) -> Path | None:
    """`root / relative`, or None if that escapes the root (`..`, an absolute path, a symlink out)."""
    root = root.resolve()
    candidate = (root / relative).resolve()
    if candidate == root or root in candidate.parents:
        return candidate
    return None


def make_file_tools(settings: Settings) -> list[BaseTool]:
    root = settings.batch_exchange_dir

    @tool
    def list_files(directory: str = "") -> str:
        """List the files in a directory of the settlement-file exchange (outbox, inbox, archive).

        Args:
            directory: path relative to the exchange root, e.g. "outbox". Empty for the root.
        """
        target = resolve_under_root(root, directory)
        if target is None:
            return "ERROR: path is outside the settlement-file exchange."

        # ------------------------------------------------------------------------------------
        # TODO(you): if target is not a directory -> "ERROR: ...". Otherwise return one line per
        # entry: name, size in bytes, modification time (ISO-8601), sorted by name. Directories
        # marked with a trailing "/". Never raise (catch OSError -> "ERROR: ...").
        # ------------------------------------------------------------------------------------
        raise NotImplementedError("TODO(you): tools/file_tool.py list_files")

    @tool
    def read_file(path: str) -> str:
        """Read one text file from the settlement-file exchange and return its exact contents.

        Args:
            path: file path relative to the exchange root, e.g. "outbox/PMT-BATCH-20260929-001.txt".
        """
        target = resolve_under_root(root, path)
        if target is None:
            return "ERROR: path is outside the settlement-file exchange."

        # ------------------------------------------------------------------------------------
        # TODO(you): if target is not a file -> "ERROR: no such file". Otherwise return
        # target.read_text(encoding="utf-8") through truncate(). Return it verbatim: the
        # fixed-width records (implied two decimals, padded fields) are the model's problem to
        # read, see docs/api-reference.md "Batch file exchange". Catch OSError and
        # UnicodeDecodeError -> "ERROR: ...".
        # ------------------------------------------------------------------------------------
        raise NotImplementedError("TODO(you): tools/file_tool.py read_file")

    return [list_files, read_file]
