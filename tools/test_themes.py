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


def test_every_style_offers_a_dark_and_a_light_palette() -> None:
    """Switching style must not silently drop the dark/light choice.

    ``SlateStyle.adopt`` keeps the incoming palette's mode when it can, because
    that choice is a statement about the footage the slate will land on and the
    new style knows nothing about it. A style with no light palette would make
    that promise unkeepable — it would hand a light-footage user a dark slate
    without saying so — so the promise is checked where the palettes are made.
    """
    payload = load(CONFORMANCE / "themes.json")
    themes = payload["themes"]
    for style, names in payload["style_themes"].items():
        modes = {themes[name]["mode"] for name in names}
        assert modes == {"dark", "light"}, (
            f"the {style} style offers only {sorted(modes)} palettes; "
            "every style needs at least one of each"
        )


def test_style_palettes_are_not_shared() -> None:
    """A palette belongs to the style whose marks it was measured against.

    Two styles pointing at one list would mean a palette certified for a picture
    nobody is drawing — the exact substitution ``renderOverlay`` refuses at
    runtime, arriving instead through the generator.
    """
    payload = load(CONFORMANCE / "themes.json")
    seen: dict[str, str] = {}
    for style, names in payload["style_themes"].items():
        for name in names:
            assert name not in seen, (
                f"palette {name!r} is offered by both {seen[name]} and {style}"
            )
            seen[name] = style


def test_every_palette_clears_its_own_profile() -> None:
    """No palette ships with a failing verdict, whichever gates apply to it.

    The profiles loosen what is measured — a monochrome palette is not asked to
    separate marks by hue, because it separates them by dash and stroke width
    instead — but none of them loosens *this*. A recorded failure is still a
    failure.
    """
    themes = load(CONFORMANCE / "themes.json")["themes"]
    for name, data in themes.items():
        assert data["validation"]["ok"], (
            f"{name} ships a failing verdict under the {data['profile']} gates"
        )


def test_container_pairs_clear_the_text_bar() -> None:
    """A chip's label must be legible on the chip, not merely on the card.

    A style that fills a shape and prints inside it has changed what its ink is
    read against, and the palette's ordinary ink is no answer: in a dark scheme
    it is pale, and so is a filled container. The pairs are stated together for
    that reason, and this is the check that they were stated correctly.

    Only palettes that declare containers are examined. The rest are given
    defined fallbacks so no style has to test for their absence, and holding a
    fallback to a bar it was never designed for would fail the wrong thing.
    """
    from palette import contrast
    from theme import STYLE_THEMES

    for style, family in STYLE_THEMES.items():
        for candidate in family:
            if candidate.container_primary is None:
                continue
            for role in ("primary", "neutral", "accent", "hazard"):
                background = getattr(candidate, f"container_{role}")
                foreground = getattr(candidate, f"on_container_{role}")
                assert background is not None and foreground is not None, (
                    f"{candidate.name} declares some containers but not {role}"
                )
                ratio = contrast(foreground, background)
                assert ratio >= 4.5, (
                    f"{style}/{candidate.name}: {role} chip text measures "
                    f"{ratio:.2f}:1 on its own fill"
                )


def test_gas_accent_stands_off_the_panel() -> None:
    """The one mark that carries a label must be visible against its label.

    The gas switch is drawn as a tab filled with the palette's panel colour and
    outlined in the accent, so the accent is read against the panel rather than
    against the other marks. No separation gate can see that: they compare marks
    with each other, and the first wrapped palette put a pink accent on pink
    water while clearing every one of them, because none of the marks it was
    measured against was the thing behind it.
    """
    from theme import STYLE_THEMES, accent_over_panel

    for style, family in STYLE_THEMES.items():
        for candidate in family:
            ratio = accent_over_panel(candidate)
            assert ratio >= 3.0, (
                f"{style}/{candidate.name}: the gas accent measures "
                f"{ratio:.2f}:1 against its own panel"
            )
