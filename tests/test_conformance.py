"""The conformance fixtures must keep matching the implementation.

``conformance/`` is generated from this codebase, so on its own it is only ever
a snapshot of whatever the code did last time someone ran the exporter. That is
not much use as a specification for a port: the Kotlin implementation will be
held to those files, and if Python drifts away from them without anyone
noticing, the two halves disagree and the fixtures side with the wrong one.

These tests close that gap. They re-derive every fixture and compare, so a
behaviour change that was not deliberate shows up here as a failure rather than
as a port that mysteriously stops matching months later.

When one fails, read the diff before regenerating. Regenerating is right when
the behaviour changed on purpose; it is wrong as a way to make a red test green.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

import export_oracle as oracle
import export_theme_tokens as tokens

CONFORMANCE = ROOT / "conformance"
LOG_FIXTURES = sorted((CONFORMANCE / "logs").glob("*.json"))

REGENERATE = (
    "\n\nIf this change was deliberate, regenerate with:\n"
    "    uv run python tools/export_oracle.py\n"
    "    uv run python tools/export_theme_tokens.py"
)


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def test_fixtures_exist() -> None:
    """A missing corpus would make every other test here vacuously pass."""
    assert LOG_FIXTURES, f"no log fixtures in {CONFORMANCE / 'logs'}{REGENERATE}"


@pytest.mark.parametrize("path", LOG_FIXTURES, ids=lambda p: p.stem)
def test_log_fixture_matches_implementation(path: Path) -> None:
    expected = load(path)
    source = ROOT / "tests" / "data" / expected["source"]
    assert source.exists(), f"fixture references a missing log: {source}"

    actual = oracle.log_json(oracle.detect.parse_file(source), expected["source"])
    assert actual == expected, f"{path.name} no longer matches the parser{REGENERATE}"


def test_specs_fixture_matches_implementation() -> None:
    """Unit grammar, rounding rules, gas naming and gradient-factor recovery."""
    expected = load(CONFORMANCE / "specs.json")
    assert oracle.specs_json() == expected, f"specs.json is stale{REGENERATE}"


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
