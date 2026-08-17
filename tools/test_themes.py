"""The baked theme tokens must keep matching the palette maths.

``conformance/themes.json`` is generated from :mod:`palette` and :mod:`theme`,
and the app reads it only at build time — by the time a phone runs the slate,
the palettes are constants in ``Themes.kt``. That makes the generated file the
one place where a mistake is invisible: nothing at runtime re-checks it, so a
palette that quietly stopped clearing the gates would ship looking fine.

These tests close that gap. They re-derive the payload and compare, and they
assert the two properties the generated ranges have to hold, so a change that
was not deliberate shows up here rather than on someone's screen.

When one fails, read the diff before regenerating. Regenerating is right when
the palette changed on purpose; it is wrong as a way to make a red test green.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import export_theme_tokens as tokens

ROOT = Path(__file__).resolve().parent.parent
CONFORMANCE = ROOT / "conformance"

REGENERATE = (
    "\n\nIf this change was deliberate, regenerate with:\n"
    "    uv run python tools/export_theme_tokens.py\n"
    "    uv run python tools/generate_kotlin_themes.py"
)


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def test_theme_tokens_match_implementation() -> None:
    """Palettes and the derived control ranges."""
    expected = load(CONFORMANCE / "themes.json")
    actual = tokens.build_payload()
    assert actual == expected, f"themes.json is stale{REGENERATE}"


def test_scrim_floor_is_below_the_shipped_value() -> None:
    """Every theme must be able to reach its own nominal scrim opacity.

    A floor above the value the theme actually ships would mean the slate is
    already illegible before the user touches the slider, which would make the
    clamp a statement that the default is wrong.
    """
    themes = load(CONFORMANCE / "themes.json")["themes"]
    for name, data in themes.items():
        alpha = data["scrim_alpha"]
        assert alpha["min_for_text"] is not None, f"{name} has no computed floor"
        assert alpha["min_for_text"] <= alpha["nominal"], (
            f"{name} ships scrim {alpha['nominal']} but needs "
            f"{alpha['min_for_text']} for 4.5:1 text contrast"
        )


def test_slider_hues_are_a_subset_of_passing_hues() -> None:
    """The control may only offer hues that actually clear the palette gates."""
    payload = load(CONFORMANCE / "themes.json")
    for mode, entry in payload["slider_hues"].items():
        legal = set(payload["legal_hues"][mode])
        offered = set(entry["hues"])
        assert offered <= legal, (
            f"{mode} slider offers hues that fail validation: {sorted(offered - legal)}"
        )
