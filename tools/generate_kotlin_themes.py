"""Emit the baked palettes as Kotlin constants.

``conformance/themes.json`` is the output of running the palette gates once, in
Python, at design time. This turns it into source the Android app compiles in,
so the OKLab maths, the CVD simulation and the gamut search never ship to a
phone — and so a colour cannot be wrong at runtime, because there is no runtime
decision left to get wrong.

Run after regenerating the tokens::

    uv run python tools/export_theme_tokens.py
    uv run python tools/generate_kotlin_themes.py
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from _console import use_utf8_stdout

ROOT = Path(__file__).resolve().parent.parent
TOKENS = ROOT / "conformance" / "themes.json"
OUT = ROOT / "android/core/src/main/kotlin/io/github/paulcharp/diveslate/core/Themes.kt"

COLOUR_FIELDS = [
    ("ink", "ink"),
    ("inkSecondary", "ink_secondary"),
    ("inkMuted", "ink_muted"),
    ("halo", "halo"),
    ("grid", "grid"),
    ("axis", "axis"),
    ("scrim", "scrim"),
    ("curve", "curve"),
    ("curveFillTop", "curve_fill_top"),
    ("curveFillBottom", "curve_fill_bottom"),
    ("ceiling", "ceiling"),
    ("accent", "accent"),
]

HEADER = """package io.github.paulcharp.diveslate.core

// GENERATED — do not edit by hand.
// Source: conformance/themes.json, via tools/generate_kotlin_themes.py
//
// The palette gates (OKLab delta-E, Machado CVD simulation at severity 1.0,
// chroma floor, lightness band, WCAG contrast) run once in Python at design
// time. Their conclusions are baked here, so none of that maths ships to a
// phone and no colour decision is left to be made at runtime.

/**
 * Colour and type tokens for one palette.
 *
 * Colours are packed ARGB (`0xAARRGGBB`). Alpha is carried in the value rather
 * than applied separately, because several tokens are deliberately translucent
 * — the fills, the halo, the scrim — and splitting that out invites painting
 * one of them opaque by accident.
 */
data class SlateTheme(
    val name: String,
    val mode: String,
    /**
     * The background this palette was validated against.
     *
     * Never painted — the output is transparent — but a swatch has to show it,
     * because a palette for dark footage and one for a pale background are not
     * interchangeable and nothing else about the colours says which is which.
     */
    val assumedSurface: Long,
    val ink: Long,
    val inkSecondary: Long,
    val inkMuted: Long,
    val halo: Long,
    val grid: Long,
    val axis: Long,
    val scrim: Long,
    val curve: Long,
    val curveFillTop: Long,
    val curveFillBottom: Long,
    val ceiling: Long,
    val accent: Long,
    val fontSize: Float,
    val titleSize: Float,
    val labelSize: Float,
    /** The scrim opacity this palette was designed around. */
    val scrimAlphaNominal: Float,
    /**
     * Least scrim opacity at which ink still clears 4.5:1 against the worst
     * possible backdrop. The opacity slider clamps here: below it the panel has
     * stopped doing its job and the halo is carrying the text alone, which is
     * not enough over video.
     */
    val scrimAlphaMin: Float,
) {
    val isDark: Boolean get() = mode == "dark"
}

/**
 * The deco ceiling's red, fixed across every palette on purpose: a hazard
 * colour that shifts with the theme stops reading as a hazard.
 */
const val CEILING_ARGB: Long = 0x__CEILING__
"""


def argb(token: dict[str, Any]) -> str:
    """`0xAARRGGBB` for a token, as a Kotlin Long literal body."""
    value: str = token["argb"]
    return value.lstrip("#").upper()


def theme_kotlin(name: str, data: dict[str, Any]) -> str:
    lines = [
        f"val {name.upper()}: SlateTheme = SlateTheme(",
        f'    name = "{data["name"]}",',
        f'    mode = "{data["mode"]}",',
        f"    assumedSurface = 0xFF{data['assumed_surface'].lstrip('#').upper()},",
    ]
    for kotlin_name, json_name in COLOUR_FIELDS:
        lines.append(f"    {kotlin_name} = 0x{argb(data['tokens'][json_name])},")

    type_tokens = data["type"]
    alpha = data["scrim_alpha"]
    lines += [
        f"    fontSize = {type_tokens['font_size']}f,",
        f"    titleSize = {type_tokens['title_size']}f,",
        f"    labelSize = {type_tokens['label_size']}f,",
        f"    scrimAlphaNominal = {alpha['nominal']}f,",
        f"    scrimAlphaMin = {alpha['min_for_text']}f,",
        ")",
    ]
    return "\n".join(lines)


def main() -> int:
    use_utf8_stdout()
    payload = json.loads(TOKENS.read_text(encoding="utf-8"))
    themes = payload["themes"]

    # str.format is unusable here: the header carries Kotlin bodies full of
    # braces. Opaque alpha is prepended because the ceiling is stored as a plain
    # hex triple, and a bare 0xRRGGBB would paint the hazard fully transparent.
    ceiling = "FF" + payload["ceiling"].lstrip("#").upper()
    parts = [HEADER.replace("__CEILING__", ceiling)]

    for name in sorted(themes):
        parts.append("\n" + theme_kotlin(name, themes[name]) + "\n")

    # Default first, then the rest. The UI groups them by mode rather than
    # showing one undifferentiated row.
    ordered = ["slate", "light"] + [
        n for n in sorted(themes) if n not in ("slate", "light")
    ]
    listing = ", ".join(n.upper() for n in ordered)
    parts.append(
        "\n/** Every palette, default first. */\n"
        f"val SLATE_THEMES: List<SlateTheme> = listOf({listing})\n"
    )

    # No lookup-by-name function and no SAFE_HUES arrays are emitted. Both were
    # generated for a hue control the app never grew — it offers these nine
    # palettes and picks from SLATE_THEMES directly. The analysis behind the
    # arrays is still recorded, under `slider_hues` in conformance/themes.json;
    # what stopped shipping is Kotlin nothing referenced.

    text = "".join(parts)
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(
        f"themes -> {OUT.relative_to(ROOT)}  ({len(text):,} bytes, {len(themes)} palettes)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
