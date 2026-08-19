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
    ("surfaceTint", "surface_tint"),
    ("surfaceEdge", "surface_edge"),
    ("ornament", "ornament"),
    ("curveEnd", "curve_end"),
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
     * For a style that paints no card this is an assumption about the footage
     * and is never painted; for one that paints its own it is that card, which
     * is the surface the marks are genuinely read against. Either way a swatch
     * has to show it, because a palette for dark footage and one for a pale
     * background are not interchangeable and nothing else about the colours
     * says which is which.
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
    /**
     * Second stop of a two-stop panel, equal to [scrim] where the panel is flat.
     *
     * The optional tokens below are resolved at design time rather than left
     * null, so a style asking a palette for something it does not have gets a
     * defined answer — a transparent edge paints nothing — instead of every
     * style inventing its own fallback.
     */
    val surfaceTint: Long,
    /** Panel border, bezel or rule. Fully transparent where there is none. */
    val surfaceEdge: Long,
    /** Ink for a style's ornament: sparkles, microcopy, legend, contours. */
    val ornament: Long,
    /** Far stop of a gradient curve, equal to [curve] where it is flat. */
    val curveEnd: Long,
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
    /**
     * Which gates this palette cleared — see `tools/palette.py`.
     *
     * `"chromatic"` and `"expressive"` mean the marks were proved distinct by
     * *colour*, at CVD and normal-vision delta-E floors. `"monochrome"` means
     * they were not, because the palette is one ink on purpose: what tells the
     * curve from the ceiling there is dash and stroke width, and that claim is
     * checked in `SlateStyleTest` rather than in Python, since form is not
     * something a colour gate can measure.
     */
    val paletteProfile: String,
) {
    val isDark: Boolean get() = mode == "dark"

    /** Whether this palette's marks are told apart by shape rather than hue. */
    val separatesByForm: Boolean get() = paletteProfile == "monochrome"
}

/**
 * The deco ceiling's red, as the Modern style paints it in every palette.
 *
 * It was once fixed for every style there was, on the argument that a hazard
 * colour which shifts with the theme stops reading as a hazard. That argument
 * still holds where the hazard is carried by colour — which is why Modern's
 * nine all use this value, and why the styles that keep a red keep this one.
 *
 * What changed is that it is no longer the only way to carry a hazard. A white
 * dashed step over a hatch on a violet card is not a red mark, and it is not
 * ambiguous either: the hatch says region-to-avoid and the dash says boundary,
 * and neither depends on hue. So a style may re-colour the ceiling, and the
 * palettes that do had the substitute *measured* against the card they land on
 * — a white ceiling on the yellow card was rejected at 1.43:1 and reverted to
 * a red. What is not allowed is dropping the hatch or the dash, because that is
 * where the meaning went.
 */
const val CEILING_ARGB: Long = 0x__CEILING__
"""


def argb(token: dict[str, Any]) -> str:
    """`0xAARRGGBB` for a token, as a Kotlin Long literal body."""
    value: str = token["argb"]
    return value.lstrip("#").upper()


def constant(name: str) -> str:
    """`wrapped-violet` -> `WRAPPED_VIOLET`."""
    return name.upper().replace("-", "_")


def theme_kotlin(name: str, data: dict[str, Any]) -> str:
    lines = [
        f"val {constant(name)}: SlateTheme = SlateTheme(",
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
        f'    paletteProfile = "{data["profile"]}",',
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

    # Modern's nine. Everything not claimed by a later style belongs here,
    # computed as a difference rather than listed, so a palette added to a style
    # family cannot also silently appear in Modern's list.
    families: dict[str, list[str]] = payload["style_themes"]
    claimed = {name for family in families.values() for name in family}
    ordered = ["slate", "light"] + [
        n for n in sorted(themes) if n not in ("slate", "light") and n not in claimed
    ]
    listing = ", ".join(constant(n) for n in ordered)
    parts.append(
        "\n/** Every palette the Modern style offers, default first. */\n"
        f"val SLATE_THEMES: List<SlateTheme> = listOf({listing})\n"
    )

    # One list per style. A style may not borrow another's: a palette is
    # measured against the marks it will be painted as, so a list is only valid
    # for the picture it was measured on, and renderOverlay refuses anything
    # outside it.
    for style, names in families.items():
        listing = ", ".join(constant(n) for n in names)
        parts.append(
            f"\n/** The {style} style's palettes, default first. */\n"
            f"val {style.upper()}_THEMES: List<SlateTheme> = listOf({listing})\n"
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
