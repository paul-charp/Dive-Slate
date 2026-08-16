"""Entry-point discovery for parsers and themes.

Mirrors the plugin approach Dive-Plan uses, with one deliberate difference:
entry points are resolved **lazily and individually**. A third-party parser that
fails to import costs you that parser and nothing else — the built-in formats
keep working. Dive-Plan can afford eager loading because a missing deco model is
a hard error; here a broken plugin should never stop you rendering a PNG.
"""

from __future__ import annotations

from functools import cache
from importlib.metadata import EntryPoint, entry_points
from typing import Any

__all__ = [
    "PARSER_GROUP",
    "THEME_GROUP",
    "available_parsers",
    "available_themes",
    "load_parser",
    "load_theme",
]

PARSER_GROUP = "diveslate.parsers"
THEME_GROUP = "diveslate.themes"


@cache
def _entries(group: str) -> dict[str, EntryPoint]:
    return {ep.name: ep for ep in entry_points(group=group)}


def available_parsers() -> tuple[str, ...]:
    """Names of every registered parser, whether or not it imports cleanly."""
    return tuple(sorted(_entries(PARSER_GROUP)))


def available_themes() -> tuple[str, ...]:
    return tuple(sorted(_entries(THEME_GROUP)))


def _load(group: str, name: str, what: str) -> Any:
    entry = _entries(group).get(name)
    if entry is None:
        known = ", ".join(sorted(_entries(group))) or "none"
        raise LookupError(f"unknown {what} {name!r}; available: {known}")
    return entry.load()


def load_parser(name: str) -> Any:
    """Load one parser by entry-point name."""
    return _load(PARSER_GROUP, name, "parser")


def load_theme(name: str) -> Any:
    """Load one theme by entry-point name."""
    return _load(THEME_GROUP, name, "theme")


def iter_parsers() -> list[Any]:
    """Every parser that imports successfully, broken plugins skipped."""
    loaded: list[Any] = []
    for name in available_parsers():
        try:
            loaded.append(load_parser(name))
        except Exception:  # noqa: BLE001,S112 - a bad plugin must not break detection
            continue
    return loaded
