"""Pick the right parser for a file.

Detection is content-first. Extensions are only used to decide what to *try*
first, because they are unreliable in practice: both Subsurface and UDDF exports
routinely arrive as plain ``.xml``, and dive logs get renamed on the way out of
a computer's software.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from diveslate.core.models import DiveLog
from diveslate.parsers.base import ParseError
from diveslate.registry import iter_parsers

__all__ = ["parse_file", "parse_text", "sniff"]

_SNIFF_BYTES = 4096


def sniff(text: str, *, hint: str | None = None) -> Any:
    """Return the parser class that claims ``text``.

    ``hint`` is an optional filename or extension used only to order candidates.
    """
    parsers = iter_parsers()
    if not parsers:
        raise LookupError(
            "no dive log parsers are registered; is diveslate installed correctly?"
        )

    suffix = Path(hint).suffix.lower() if hint else ""
    if suffix:
        parsers.sort(key=lambda p: suffix not in getattr(p, "extensions", ()))

    head = text[:_SNIFF_BYTES]
    for parser in parsers:
        try:
            if parser.sniff(head):
                return parser
        except Exception:  # noqa: BLE001,S112 - a bad sniff must not mask good parsers
            continue

    raise ParseError(
        "unrecognised dive log format; expected Subsurface XML (<divelog>) "
        "or UDDF (<uddf>)"
    )


def parse_text(
    text: str, *, hint: str | None = None, source: str | None = None
) -> DiveLog:
    """Detect the format of ``text`` and parse it."""
    # Parsers arrive from entry points, so they are untyped to mypy; the Parser
    # protocol pins the shape and this states the return type it guarantees.
    log: DiveLog = sniff(text, hint=hint).parse(text, source=source)
    return log


def parse_file(path: str | Path) -> DiveLog:
    """Detect the format of the file at ``path`` and parse it."""
    path = Path(path)
    text = path.read_text(encoding="utf-8")
    return parse_text(text, hint=path.name, source=str(path))
