"""Bake the palettes to flat tokens, and the sliders to safe ranges.

``render/palette.py`` is a design-time instrument, not a runtime one. It exists
to prove that a palette clears the gates — OKLab ΔE, Machado CVD simulation,
chroma floor, lightness band, WCAG contrast — and having proved it for the nine
presets, its job is done. So none of that maths needs to be ported: run it once
here and ship the answers.

The same trick applies to the controls. A hue picker backed by ``build_theme``
is a widget that raises on most of its own range, and an opacity slider that
runs to zero is a widget that quietly defeats the contrast the gates enforce.
Both become safe if the legal range is computed here and baked in, so the
control cannot express an invalid state in the first place.

Run from the repo root::

    uv run python tools/export_theme_tokens.py
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from diveslate.render import theme as theme_mod
from diveslate.render.palette import contrast, validate
from diveslate.render.theme import CEILING, THEMES, Theme, build_theme

OUT = ROOT / "conformance" / "themes.json"
SCHEMA = 1

#: WCAG bars. 3:1 is the floor the palette gates hold marks to; 4.5:1 is the
#: normal-text bar, and the slate is mostly text, so that is the one the slider
#: is clamped against.
CONTRAST_MARK = 3.0
CONTRAST_TEXT = 4.5

#: Resolution of the hue sweep, and so of the hue control built from it.
HUE_STEP = 5

_RGBA = re.compile(
    r"^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)$", re.IGNORECASE
)


def parse_colour(value: str) -> tuple[int, int, int, float]:
    """``(r, g, b, alpha)`` from ``#rrggbb`` or ``rgba(r,g,b,a)``."""
    if match := _RGBA.match(value.strip()):
        r, g, b = (int(match[i]) for i in (1, 2, 3))
        return r, g, b, float(match[4]) if match[4] is not None else 1.0
    raw = value.strip().lstrip("#")
    if len(raw) == 3:
        raw = "".join(c * 2 for c in raw)
    if len(raw) != 6:
        raise ValueError(f"cannot parse colour {value!r}")
    return int(raw[0:2], 16), int(raw[2:4], 16), int(raw[4:6], 16), 1.0


def to_hex(r: float, g: float, b: float) -> str:
    return "#{:02x}{:02x}{:02x}".format(
        *(max(0, min(255, round(c))) for c in (r, g, b))
    )


def token(value: str) -> dict[str, Any]:
    """A colour in every form the port might want it.

    ``css`` keeps the original string so an SVG export stays byte-identical to
    the Python renderer's; ``argb`` is what Compose actually wants, so the app
    never has to parse CSS colour syntax at runtime.
    """
    r, g, b, a = parse_colour(value)
    return {
        "css": value,
        "argb": f"#{round(a * 255):02x}{r:02x}{g:02x}{b:02x}",
        "r": r,
        "g": g,
        "b": b,
        "alpha": round(a, 4),
    }


def composite(fg: tuple[int, int, int], alpha: float, bg: tuple[int, int, int]) -> str:
    """``fg`` at ``alpha`` painted over ``bg``, as hex."""
    return to_hex(*(alpha * f + (1.0 - alpha) * b for f, b in zip(fg, bg)))


def worst_backdrop(ink: str) -> str:
    """The background that fights ``ink`` hardest.

    The output is transparent and lands on footage the renderer never sees, so
    the scrim has to hold up against the least helpful frame possible: white
    behind light ink, black behind dark ink.
    """
    return (
        "#ffffff" if contrast(ink, "#000000") > contrast(ink, "#ffffff") else "#000000"
    )


def min_scrim_alpha(scrim: str, ink: str, target: float) -> float | None:
    """Least scrim opacity at which ink still clears ``target`` on any backdrop.

    Below this the panel stops doing its job and the halo is carrying the text
    alone, which CLAUDE.md notes is not enough over video. Returned as the hard
    stop for the opacity slider rather than as advice.
    """
    r, g, b, _ = parse_colour(scrim)
    backdrop = parse_colour(worst_backdrop(ink))[:3]
    for step in range(1001):
        alpha = step / 1000.0
        if contrast(ink, composite((r, g, b), alpha, backdrop)) >= target:
            return round(alpha, 3)
    return None


def mode_of(theme: Theme) -> str:
    return (
        "dark"
        if contrast(theme.ink, "#000000") > contrast(theme.ink, "#ffffff")
        else "light"
    )


def theme_json(theme: Theme) -> dict[str, Any]:
    report = theme_mod.validate_theme(theme)
    scrim_alpha = parse_colour(theme.scrim)[3]

    colour_fields = (
        "ink",
        "ink_secondary",
        "ink_muted",
        "halo",
        "grid",
        "axis",
        "scrim",
        "curve",
        "curve_fill_top",
        "curve_fill_bottom",
        "ceiling",
        "ceiling_fill",
        "accent",
    )

    return {
        "name": theme.name,
        "mode": mode_of(theme),
        "assumed_surface": theme.assumed_surface,
        "tokens": {field: token(getattr(theme, field)) for field in colour_fields},
        "type": {
            "font_family": theme.font_family,
            "font_size": theme.font_size,
            "title_size": theme.title_size,
            "label_size": theme.label_size,
        },
        # The opacity slider's range. Ink is never faded — only this panel is —
        # so legibility degrades gracefully instead of falling off a cliff.
        "scrim_alpha": {
            "nominal": round(scrim_alpha, 4),
            "max": 1.0,
            "min_for_text": min_scrim_alpha(theme.scrim, theme.ink, CONTRAST_TEXT),
            "min_for_marks": min_scrim_alpha(theme.scrim, theme.ink, CONTRAST_MARK),
            "worst_backdrop": worst_backdrop(theme.ink),
        },
        "validation": {
            "ok": report.ok,
            "worst_cvd_delta_e": round(report.worst_cvd, 2),
            "worst_normal_delta_e": round(report.worst_normal, 2),
        },
    }


def sweep_hues(step: int = HUE_STEP) -> dict[str, list[dict[str, Any]]]:
    """Walk the whole hue circle recording which bases survive the gates.

    Swept full-circle rather than across the documented 180–330 band so the
    boundary is measured here rather than taken on trust — and so the slider
    can be built from the surviving hues instead of from a remembered range.
    """
    out: dict[str, list[dict[str, Any]]] = {}
    for mode in ("dark", "light"):
        rows: list[dict[str, Any]] = []
        for hue in range(0, 360, step):
            base = theme_mod._base_for(float(hue), mode)
            candidate = build_theme(f"h{hue}", base, mode=mode, strict=False)
            report = validate(
                [candidate.curve, candidate.ceiling, candidate.accent],
                mode=mode,
                surface=candidate.assumed_surface,
                pairs="all",
            )
            rows.append(
                {
                    "hue": hue,
                    "ok": report.ok,
                    "curve": candidate.curve,
                    "accent": candidate.accent,
                    "worst_cvd_delta_e": round(report.worst_cvd, 2),
                    "worst_normal_delta_e": round(report.worst_normal, 2),
                    "failed": [c.name for c in report.checks if not c.ok],
                }
            )
        out[mode] = rows
    return out


#: How far either side of a hue must also pass before that hue is offered.
#:
#: A raised ΔE threshold is the wrong instrument here. What makes the green
#: region unusable is not a low score but an unstable one: hue 120 clears at 8.5
#: while 130 beside it collapses to 2.6 against the fixed ceiling red under
#: protanopia. Meanwhile the indigo dip at 230–250 sits at a steady 9 with
#: neighbours to match, and one of the shipped presets lives there. A threshold
#: high enough to exclude the first excludes the second too.
#:
#: So the test is neighbourhood stability: a hue is offered only if it and
#: everything within this many degrees clears the gates. Cliff edges are
#: excluded because of the cliff, not because of their own score.
STABILITY_DEG = 10


def stable_hues(rows: list[dict[str, Any]], step: int, floor: float) -> list[int]:
    """Hues whose whole neighbourhood holds up. Wraps around the circle.

    ``floor`` is not a chosen number: it is the CVD separation of the weakest
    palette already shipped in this mode. A hue reaches the slider only if it is
    at least as good as something the project has already accepted, which keeps
    the bar honest without inviting a new one to be argued over.

    A consequence worth expecting: ``abyss`` sets the dark floor at 9.23 and is
    then excluded from the slider itself, because 240–245° beside it dip just
    under. That is the rule working, not failing — the nine presets are curated
    and validated one by one, so they stay on offer as named swatches; the
    free-hue control is deliberately the more cautious of the two routes.
    """
    count = len(rows)
    reach = STABILITY_DEG // step
    holds = [bool(row["ok"]) and row["worst_cvd_delta_e"] >= floor for row in rows]
    return [
        row["hue"]
        for index, row in enumerate(rows)
        if all(holds[(index + offset) % count] for offset in range(-reach, reach + 1))
    ]


def contiguous_runs(hues: list[int], step: int) -> list[list[int]]:
    """Split an ascending hue list into maximal contiguous bands."""
    runs: list[list[int]] = []
    for hue in hues:
        if runs and hue - runs[-1][-1] == step:
            runs[-1].append(hue)
        else:
            runs.append([hue])
    # Rejoin across 355 -> 0 so a band spanning the wrap counts as one.
    if len(runs) > 1 and runs[0][0] == 0 and runs[-1][-1] == 360 - step:
        runs[-1].extend(runs.pop(0))
    return runs


def build_payload() -> dict[str, Any]:
    """Everything written to ``themes.json``, kept separate from writing it.

    ``tests/test_conformance.py`` calls this to re-derive the fixture and check
    it still matches, so the generated file cannot drift away from the code that
    generated it without a test going red.
    """
    sweep = sweep_hues()
    themes = {name: theme_json(t) for name, t in sorted(THEMES.items())}

    # The bar each mode has to clear: whatever the weakest palette already
    # shipped in that mode scores.
    floors = {
        mode: min(
            data["validation"]["worst_cvd_delta_e"]
            for data in themes.values()
            if data["mode"] == mode
        )
        for mode in sweep
    }

    # The control indexes into this list rather than mapping its travel onto
    # degrees, so an excluded band is not somewhere the slider can be dragged to
    # — it simply is not on the dial.
    slider = {
        mode: {
            "stability_deg": STABILITY_DEG,
            "step_deg": HUE_STEP,
            "floor_delta_e": floors[mode],
            "hues": (hues := stable_hues(rows, HUE_STEP, floors[mode])),
            "count": len(hues),
            "bands": [[run[0], run[-1]] for run in contiguous_runs(hues, HUE_STEP)],
        }
        for mode, rows in sweep.items()
    }
    payload = {
        "schema": SCHEMA,
        # Never themed: a hazard colour that moves with the palette stops
        # reading as a hazard.
        "ceiling": CEILING,
        "contrast_targets": {"marks": CONTRAST_MARK, "text": CONTRAST_TEXT},
        "themes": themes,
        "hue_sweep": sweep,
        # Everything that merely passes. Recorded for auditing, and deliberately
        # NOT what the UI is built from — see slider_hues.
        "legal_hues": {
            mode: [row["hue"] for row in rows if row["ok"]]
            for mode, rows in sweep.items()
        },
        # What the hue control may actually offer.
        "slider_hues": slider,
    }
    return payload


def main() -> int:
    payload = build_payload()

    OUT.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
    OUT.write_text(text, encoding="utf-8", newline="\n")

    print(f"themes -> {OUT.relative_to(ROOT)}  ({len(text):,} bytes)")
    for mode, rows in payload["hue_sweep"].items():
        entry = payload["slider_hues"][mode]
        bands = ", ".join(f"{lo}-{hi}°" for lo, hi in entry["bands"]) or "none"
        print(
            f"  {mode:>5}: {len(payload['legal_hues'][mode]):>2}/{len(rows)} pass, "
            f"slider offers {entry['count']} in [{bands}] "
            f"(floor ΔE {entry['floor_delta_e']})"
        )
    for name, data in payload["themes"].items():
        alpha = data["scrim_alpha"]
        print(
            f"  {name:>8}: scrim {alpha['nominal']} "
            f"(floor {alpha['min_for_text']} vs {alpha['worst_backdrop']}), "
            f"CVD ΔE {data['validation']['worst_cvd_delta_e']}"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
