"""Colour and type tokens for the rendered slate.

Transparency makes colour choice harder than it looks, not easier. A normal
chart knows its own background and can be checked against it; this one is
composited onto something the renderer never sees. Two things follow, and both
are load-bearing:

1. **There are two themes, not one theme and an inversion.** :data:`SLATE`
   assumes it lands on dark imagery (underwater photos, video) and :data:`LIGHT`
   assumes a pale one (a log-book page, a document). Each was validated as its
   own palette against its own assumed surface — the dark steps are not a flip
   of the light ones.
2. **Text is haloed.** Every label is painted twice, a wide stroke in
   :attr:`Theme.halo` under a fill in the ink colour, so it survives landing on
   a background that happens to match it. The app draws that halo in
   ``android/core/.../OverlayRenderer.kt``.

The three colour-bearing marks are the depth curve, the deco ceiling and the gas
switches. They were validated together on the *all-pairs* list — the marks scatter
across the plot, so any two can end up adjacent — using the checks from the
data-viz palette validator (OKLab ΔE ×100, Machado-Oliveira-Fernandes CVD
simulation at severity 1.0):

===========  ==================  ===================  =====================
Theme        worst CVD ΔE        worst normal ΔE      contrast vs surface
===========  ==================  ===================  =====================
LIGHT        19.8 (deutan)       24.1                 accent 2.11:1 → relief
SLATE        10.2 (deutan)       16.9                 all ≥ 3:1
===========  ==================  ===================  =====================

Targets are CVD ΔE ≥ 8 and normal-vision ΔE ≥ 15, so both clear with room. The
light theme's accent sits below the 3:1 contrast bar, which is permitted only
because gas switches carry a **visible text label** naming the mix — colour never
carries that identity alone. Keep the label if you re-theme.

Two combinations were measured and rejected rather than reasoned about: orange
accent against the ceiling red (normal ΔE 10.8 — below the floor, they blur) and
violet accent against the curve blue in dark mode (CVD ΔE 1.9 — indistinguishable
to protanopes). If you swap these values, re-run the validation.
"""

from __future__ import annotations

from dataclasses import dataclass

from palette import (
    DEFAULT_SURFACE,
    PaletteReport,
    best_in_band,
    contrast,
    oklch_to_hex,
    pick_accent,
    snap_to_band,
    validate,
)

__all__ = [
    "CEILING",
    "GENERATED",
    "LIGHT",
    "SLATE",
    "STYLE_THEMES",
    "THEMES",
    "Theme",
    "accent_over_panel",
    "build_theme",
    "style_theme",
    "validate_theme",
]


@dataclass(frozen=True, slots=True)
class Theme:
    """Colour and type tokens. Colours are CSS colour strings."""

    name: str

    #: The background this theme was validated against.
    #:
    #: For the transparent styles this is an assumption about the footage — it
    #: is never painted. For a style that paints its own opaque card it is that
    #: card, which is the surface its marks are actually read against, so the
    #: contrast check measures the real thing rather than a guess about video.
    assumed_surface: str

    # Ink. Text never wears a series colour; identity comes from the mark beside it.
    ink: str
    ink_secondary: str
    ink_muted: str
    halo: str

    # Chrome, deliberately recessive.
    grid: str
    axis: str

    #: Backdrop panel behind the compact overlay slate. A photo or video frame
    #: can be any colour anywhere, and haloed text alone is not enough over a
    #: busy one — the scrim buys a predictable surface to read against.
    scrim: str

    # The three colour-bearing marks.
    curve: str
    curve_fill_top: str
    curve_fill_bottom: str
    ceiling: str
    accent: str

    #: "dark" or "light" — which end of the range this palette is built for.
    #:
    #: For a transparent style that is a statement about the footage; for an
    #: opaque one it describes the card. Either way it is what the picker groups
    #: by and what :func:`SlateStyle.adopt` preserves across a style change, so
    #: it is recorded rather than inferred from the ink.
    mode: str = "dark"

    #: Which gates this palette was measured against — see :class:`palette.Gates`.
    #: A palette that separates its marks by dash and weight rather than by hue
    #: says so here, and the renderer's tests hold it to that claim.
    profile: str = "chromatic"

    # Optional tokens. Every one of these has a defined fallback, so a palette
    # that does not use it is not carrying a hole: a style asking for a border
    # on a palette with none gets a fully transparent colour and paints nothing.

    #: Second stop of a two-stop panel. Falls back to a flat :attr:`scrim`.
    surface_tint: str | None = None

    #: Panel border, bezel or rule. Falls back to fully transparent — no edge.
    surface_edge: str | None = None

    #: Ink for a style's own ornament — sparkles, microcopy, legend, contours.
    #: Falls back to :attr:`accent`.
    ornament: str | None = None

    #: Far stop of a gradient depth curve. Falls back to a flat :attr:`curve`.
    curve_end: str | None = None

    # Type.
    font_family: str = (
        'Inter, "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif'
    )
    font_size: float = 13.0
    title_size: float = 20.0
    label_size: float = 11.5


#: Default. For compositing onto dark imagery — underwater photos, video.
SLATE = Theme(
    name="slate",
    assumed_surface="#1a1a19",
    ink="#ffffff",
    ink_secondary="#c3c2b7",
    ink_muted="#898781",
    # A dark halo behind light text: the pairing that survives a mid-tone
    # background, which is where unhaloed text on a transparent PNG dies.
    halo="rgba(0,0,0,0.55)",
    grid="rgba(255,255,255,0.10)",
    axis="rgba(255,255,255,0.22)",
    scrim="rgba(8,12,18,0.62)",
    curve="#3987e5",
    curve_fill_top="rgba(57,135,229,0.38)",
    curve_fill_bottom="rgba(57,135,229,0.05)",
    ceiling="#d03b3b",
    accent="#c98500",
)

#: For compositing onto a pale background — a log-book page, a document.
LIGHT = Theme(
    name="light",
    assumed_surface="#fcfcfb",
    mode="light",
    ink="#0b0b0b",
    ink_secondary="#52514e",
    ink_muted="#898781",
    halo="rgba(255,255,255,0.70)",
    grid="rgba(11,11,11,0.10)",
    axis="rgba(11,11,11,0.25)",
    scrim="rgba(252,252,251,0.76)",
    curve="#2a78d6",
    curve_fill_top="rgba(42,120,214,0.30)",
    curve_fill_bottom="rgba(42,120,214,0.04)",
    ceiling="#d03b3b",
    accent="#eda100",
)

# ---------------------------------------------------------------------------
# generated themes


#: Status red for the deco ceiling. Fixed across every theme on purpose: a
#: hazard colour that shifts with the palette stops being a hazard colour.
CEILING = "#d03b3b"

_INK = {
    "dark": ("#ffffff", "#c3c2b7", "#898781", "rgba(0,0,0,0.55)", "rgba(8,12,18,0.62)"),
    "light": (
        "#0b0b0b",
        "#52514e",
        "#898781",
        "rgba(255,255,255,0.70)",
        "rgba(252,252,251,0.76)",
    ),
}


def _rgba(value: str, alpha: float) -> str:
    raw = value.lstrip("#")
    r, g, b = (int(raw[i : i + 2], 16) for i in (0, 2, 4))
    return f"rgba({r},{g},{b},{alpha:g})"


def build_theme(
    name: str, base: str, *, mode: str = "dark", strict: bool = True
) -> Theme:
    """Derive a complete, validated theme from one base colour.

    The base becomes the depth curve — the mark the eye goes to first — after
    being snapped into the mode's lightness band. The area fill is that same
    hue at two alphas. The ceiling stays the fixed status red. Only the gas
    accent is free, and :func:`~palette.pick_accent` searches
    the hue circle for the one that separates best from both, because the right
    accent depends on the base: a hue that sings beside blue can vanish beside
    teal.

    The result is checked before it is returned, so a caller passing an arbitrary
    brand colour cannot end up with a palette that merely looks fine to them.
    Pass ``strict=False`` to get the theme back anyway with the failure reported
    by :func:`validate_theme`.

    **Only base hues from roughly 180° to 330° work** — cyan through blue,
    violet and magenta. Warm and green bases are rejected, and the reason is
    the fixed red ceiling: measured against it, a green curve scores a normal-
    vision ΔE of 24 (looks maximally different) but a CVD ΔE of 2.2, because
    red and green collapse together under protanopia and deuteranopia. This is
    exactly the failure that eyeballing a palette never catches, which is why
    the check runs here rather than in a comment.
    """
    if mode not in ("dark", "light"):
        raise ValueError(f"mode must be 'dark' or 'light', got {mode!r}")

    curve = snap_to_band(base, mode)
    accent = pick_accent(curve, CEILING, mode=mode)
    ink, ink_secondary, ink_muted, halo, scrim = _INK[mode]
    top, bottom = (0.38, 0.05) if mode == "dark" else (0.30, 0.04)

    theme = Theme(
        name=name,
        assumed_surface=DEFAULT_SURFACE[mode],
        mode=mode,
        ink=ink,
        ink_secondary=ink_secondary,
        ink_muted=ink_muted,
        halo=halo,
        grid=("rgba(255,255,255,0.10)" if mode == "dark" else "rgba(11,11,11,0.10)"),
        axis=("rgba(255,255,255,0.22)" if mode == "dark" else "rgba(11,11,11,0.25)"),
        scrim=scrim,
        curve=curve,
        curve_fill_top=_rgba(curve, top),
        curve_fill_bottom=_rgba(curve, bottom),
        ceiling=CEILING,
        accent=accent,
    )

    report = validate_theme(theme)
    if strict and not report.ok:
        raise ValueError(
            f"base colour {base!r} cannot make a valid {mode} palette:\n"
            f"{report}\n"
            "Warm and green bases collide with the fixed deco-ceiling red under "
            "colour-vision deficiency; use a hue between 180° (cyan) and 330° "
            "(magenta), or pass strict=False to accept the failure."
        )
    return theme


def validate_theme(theme: Theme) -> PaletteReport:
    """Check a theme's colour-bearing marks against its own profile's gates.

    The marks are the depth curve, the deco ceiling and the gas accent — plus
    the curve's far stop where a style draws the curve as a gradient, since a
    ramp that ends somewhere unmeasured is a mark nobody checked.
    """
    marks = [theme.curve, theme.ceiling, theme.accent]
    if theme.curve_end is not None:
        marks.append(theme.curve_end)
    return validate(
        marks,
        mode=theme.mode,
        surface=theme.assumed_surface,
        pairs="all",
        profile=theme.profile,
    )


#: Generated presets. Each is one base hue put through :func:`build_theme`;
#: ``tests/test_palette.py`` asserts every one still passes, so a change to the
#: generator cannot quietly ship a palette that fails.
#: ``(name, OKLCH hue°, mode)``. Hues are spread across the viable 180–330 band
#: rather than picked by eye, so the presets stay maximally distinct from each
#: other as well as from the ceiling.
_PRESETS: tuple[tuple[str, int, str], ...] = (
    ("reef", 185, "dark"),  # cyan — tropical shallows
    ("lagoon", 205, "dark"),  # blue-cyan
    ("abyss", 250, "dark"),  # indigo — deep water
    ("twilight", 285, "dark"),  # violet
    ("orchid", 315, "dark"),  # magenta
    ("paper", 240, "light"),  # blue, for pale backgrounds
    ("ink", 205, "light"),  # teal-blue, for pale backgrounds
)


def _base_for(hue: float, mode: str) -> str:
    return oklch_to_hex(*best_in_band(hue, mode), hue)


GENERATED: dict[str, Theme] = {
    name: build_theme(name, _base_for(hue, mode), mode=mode)
    for name, hue, mode in _PRESETS
}

THEMES: dict[str, Theme] = {t.name: t for t in (SLATE, LIGHT)} | GENERATED


# ---------------------------------------------------------------------------
# style palette families


def style_theme(
    name: str,
    *,
    mode: str,
    profile: str,
    surface: str,
    ink: str,
    curve: str,
    ceiling: str,
    accent: str,
    scrim: str | None = None,
    scrim_alpha: float = 1.0,
    fill: str | None = None,
    fill_alpha: tuple[float, float] = (0.38, 0.05),
    ink_secondary: str | None = None,
    ink_muted: str | None = None,
    halo: str | None = None,
    grid: str | None = None,
    axis: str | None = None,
    surface_tint: str | None = None,
    surface_edge: str | None = None,
    ornament: str | None = None,
    curve_end: str | None = None,
    strict: bool = True,
) -> Theme:
    """One style's palette, stated rather than derived from a single hue.

    :func:`build_theme` walks one base colour into a whole palette, which works
    because every Modern palette is the same picture in a different hue. The
    styles that follow are not that: a violet card with lime type and a green
    LCD screen do not differ by a hue rotation, and pretending they do would
    mean inventing a derivation for every token and then fighting it.

    So the tokens are given, and what is *computed* is the part that was always
    computed — the verdict. Every palette here goes through the same gates its
    profile prescribes, and a failure raises rather than returning something
    that merely looks right on the screen it was picked on.
    """
    surface_ink = ink_secondary or _rgba_of(ink, 0.86)
    fill_colour = fill or curve
    theme = Theme(
        name=name,
        assumed_surface=surface,
        mode=mode,
        profile=profile,
        ink=ink,
        ink_secondary=surface_ink,
        ink_muted=ink_muted or _rgba_of(ink, 0.66),
        # An opaque card is its own known background, so the halo — which exists
        # to save text landing on footage of unknown colour — has nothing to do
        # and is dropped rather than drawn as a muddy outline nobody asked for.
        halo=halo or ("rgba(0,0,0,0)" if scrim_alpha >= 0.999 else _INK[mode][3]),
        grid=grid or _rgba_of(ink, 0.12),
        axis=axis or _rgba_of(ink, 0.28),
        scrim=_rgba_of(scrim or surface, scrim_alpha),
        curve=curve,
        curve_fill_top=_rgba_of(fill_colour, fill_alpha[0]),
        curve_fill_bottom=_rgba_of(fill_colour, fill_alpha[1]),
        ceiling=ceiling,
        accent=accent,
        surface_tint=surface_tint,
        surface_edge=surface_edge,
        ornament=ornament,
        curve_end=curve_end,
    )
    report = validate_theme(theme)
    if strict and not report.ok:
        raise ValueError(
            f"palette {name!r} does not clear the {profile} gates:\n{report}"
        )

    over_panel = accent_over_panel(theme)
    if strict and over_panel < 3.0:
        raise ValueError(
            f"palette {name!r}: the gas accent {theme.accent} measures "
            f"{over_panel:.2f}:1 against the panel it is drawn against, below "
            "the 3:1 bar for a mark"
        )
    return theme


def accent_over_panel(theme: Theme) -> float:
    """Contrast of the gas accent against the palette's own panel colour.

    The gas switch is the one mark that carries a label, and the label sits in a
    tab filled with this panel colour — so the accent, which outlines that tab
    and marks the point on the profile, is read against the panel rather than
    against the other marks. The separation gates cannot see this: they compare
    marks with each other, and a pink accent on pink water clears every one of
    them while being invisible, because none of the marks it was compared to is
    the thing behind it. That is what the first wrapped palette shipped.

    Measured at full opacity even where the panel is translucent, because the tab
    is drawn opaque: a label's background is the one place on a transparent slate
    that does not get to be see-through.
    """
    panel = theme.scrim
    if panel.startswith("rgb"):
        inner = panel[panel.index("(") + 1 : panel.rindex(")")]
        r, g, b = (int(float(part.strip())) for part in inner.split(",")[:3])
        panel = f"#{r:02x}{g:02x}{b:02x}"
    return contrast(theme.accent, panel)


def _rgba_of(value: str, alpha: float) -> str:
    """``value`` at ``alpha``, accepting either hex or an existing rgba string."""
    if value.startswith("rgb"):
        inner = value[value.index("(") + 1 : value.rindex(")")]
        parts = [p.strip() for p in inner.split(",")]
        r, g, b = (int(float(p)) for p in parts[:3])
        existing = float(parts[3]) if len(parts) > 3 else 1.0
        alpha = alpha * existing
    else:
        raw = value.lstrip("#")
        r, g, b = (int(raw[i : i + 2], 16) for i in (0, 2, 4))
    if alpha >= 1.0:
        return f"#{r:02x}{g:02x}{b:02x}"
    return f"rgba({r},{g},{b},{alpha:g})"


#: Every style's palettes, default first.
#:
#: Two rules shape these lists, and both are structural rather than aesthetic:
#:
#: 1. **A style may not borrow another style's palettes.** A palette is measured
#:    against the marks it will be painted as, so a list is only valid for the
#:    picture it was measured on. ``SlateStyle.themes`` is that list in Kotlin,
#:    and ``renderOverlay`` refuses a palette outside it.
#: 2. **Every style ships at least one dark and one light palette.** Switching
#:    style keeps the dark/light choice — it is a statement about the footage
#:    the slate will land on, which the incoming style knows nothing about — so
#:    a style with no light palette would silently drop that choice on the way
#:    in. ``test_themes.py`` holds this.
STYLE_THEMES: dict[str, tuple[Theme, ...]] = {}


def _register(style: str, *themes: Theme) -> None:
    STYLE_THEMES[style] = themes


# ---- wrapped: flat, loud, feed-post energy --------------------------------
_register(
    "wrapped",
    style_theme(
        "wrapped-violet",
        mode="dark",
        profile="expressive",
        surface="#6528f7",
        ink="#d2f34c",
        ink_secondary="rgba(255,255,255,0.92)",
        ink_muted="rgba(255,255,255,0.78)",
        curve="#d2f34c",
        # Flat, not a wash: the fill is a shape in its own right here rather
        # than a fade under the line, so both stops are the same solid pink.
        fill="#ff6ec7",
        fill_alpha=(1.0, 1.0),
        # White rather than the hazard red. On a violet card at this chroma the
        # red loses to the surface, and the ceiling is a dashed step over a
        # hatch — the geometry says hazard where the colour cannot.
        ceiling="#ffffff",
        # A fourth colour, and it earns its place. The gas mark used to be the
        # same pink as the water it is drawn on — a mark you cannot see rather
        # than one you can argue about — and no separation gate caught it,
        # because every gate measured it against marks that were not behind it.
        # Searched for separation from the curve, the ceiling and the fill, with
        # a contrast floor against the card so the tab reads as its own object.
        accent="#ff9c3f",
        ornament="#ff6ec7",
    ),
    style_theme(
        "wrapped-solar",
        mode="light",
        profile="expressive",
        surface="#ffd400",
        ink="#141414",
        ink_secondary="rgba(20,20,20,0.86)",
        ink_muted="rgba(20,20,20,0.66)",
        curve="#141414",
        fill="#1b3bd6",
        fill_alpha=(1.0, 1.0),
        # White would vanish on a yellow card at 1.43:1 — measured, not
        # assumed. The hazard reverts to a red here because the card can
        # carry one.
        ceiling="#d1002b",
        # Likewise: the gas mark cannot be the blue it is drawn on top of.
        accent="#00766d",
        ornament="#1b3bd6",
    ),
)

# ---- magazine: type and line straight onto the footage --------------------
_register(
    "magazine",
    style_theme(
        "magazine-white",
        mode="dark",
        profile="monochrome",
        surface="#1a1a19",
        ink="#ffffff",
        curve="#ffffff",
        fill="#ffffff",
        fill_alpha=(0.0, 0.0),
        ceiling="#ffffff",
        accent="#ffffff",
        ornament="#ffffff",
        scrim="rgba(8,10,14,1)",
        scrim_alpha=0.62,
        surface_edge="rgba(255,255,255,0.95)",
    ),
    style_theme(
        "magazine-black",
        mode="light",
        profile="monochrome",
        surface="#fcfcfb",
        ink="#0b0b0b",
        curve="#0b0b0b",
        fill="#0b0b0b",
        fill_alpha=(0.0, 0.0),
        ceiling="#0b0b0b",
        accent="#0b0b0b",
        ornament="#0b0b0b",
        scrim="rgba(252,252,251,1)",
        scrim_alpha=0.78,
        surface_edge="rgba(11,11,11,0.95)",
    ),
)

# ---- frosted glass: the story-native translucent card ---------------------
_register(
    "frosted",
    style_theme(
        "frosted-smoke",
        mode="dark",
        profile="monochrome",
        surface="#12181f",
        ink="#ffffff",
        curve="#ffffff",
        fill="#ffffff",
        fill_alpha=(0.24, 0.02),
        ceiling="#ffffff",
        accent="#ffffff",
        ornament="rgba(255,255,255,0.8)",
        scrim="rgba(16,22,30,1)",
        scrim_alpha=0.62,
        surface_tint="#2c3f52",
        surface_edge="rgba(255,255,255,0.5)",
    ),
    style_theme(
        "frosted-mist",
        mode="light",
        profile="monochrome",
        surface="#f7f9fc",
        ink="#10161c",
        curve="#10161c",
        fill="#10161c",
        fill_alpha=(0.16, 0.02),
        ceiling="#10161c",
        accent="#10161c",
        ornament="rgba(16,22,28,0.75)",
        scrim="rgba(250,252,255,1)",
        scrim_alpha=0.74,
        surface_tint="#ffffff",
        surface_edge="rgba(255,255,255,0.95)",
    ),
)

# ---- story sticker: reads as a native social sticker ----------------------
_register(
    "sticker",
    style_theme(
        "sticker-day",
        mode="light",
        profile="expressive",
        surface="#ffffff",
        ink="#262626",
        ink_secondary="#5a5a5a",
        ink_muted="#8e8e8e",
        # The curve is a ramp rather than one colour, so both ends are marks and
        # both are measured — see validate_theme. The warm end is darker than
        # the social ramp it quotes because that ramp's orange sits at 2.64:1 on
        # white and this one has to carry the profile line.
        curve="#e8730f",
        curve_end="#4f5bd5",
        fill="#d62976",
        fill_alpha=(0.20, 0.0),
        ceiling="#d62976",
        surface_edge="#e9e4ee",
        # Searched, not picked. The obvious violet — the third stop of the ramp
        # — measures ΔE 3.8 against the ramp's own blue end under deuteranopia,
        # which is the failure eyeballing never catches.
        accent="#0c879e",
        ornament="#d62976",
    ),
    style_theme(
        "sticker-night",
        mode="dark",
        profile="expressive",
        surface="#15151c",
        ink="#f6f6f8",
        ink_secondary="rgba(246,246,248,0.82)",
        ink_muted="rgba(246,246,248,0.6)",
        curve="#ff9d4d",
        curve_end="#7c8bff",
        fill="#ff5f9e",
        fill_alpha=(0.26, 0.0),
        ceiling="#ff5f9e",
        surface_edge="#2c2c38",
        accent="#5b882e",
        ornament="#ff5f9e",
    ),
)

# ---- holographic HUD: a sci-fi glass panel -------------------------------
_register(
    "holo",
    style_theme(
        "holo-cyan",
        mode="dark",
        profile="expressive",
        surface="#0c1e2e",
        ink="#eafcff",
        ink_secondary="rgba(110,231,255,0.92)",
        ink_muted="rgba(110,231,255,0.72)",
        curve="#6ee7ff",
        fill="#6ee7ff",
        fill_alpha=(0.22, 0.0),
        # Warning amber rather than red: on a panel this saturated in cyan the
        # amber is the further of the two under both simulations, and the
        # ceiling keeps its hatch and its dash either way.
        ceiling="#ffb86b",
        accent="#c39bff",
        ornament="rgba(110,231,255,0.8)",
        scrim="rgba(10,28,44,1)",
        scrim_alpha=0.82,
        surface_tint="#081828",
        surface_edge="rgba(110,231,255,0.55)",
        grid="rgba(110,231,255,0.16)",
        axis="rgba(110,231,255,0.4)",
    ),
    style_theme(
        "holo-daylight",
        mode="light",
        profile="expressive",
        surface="#eaf2f7",
        ink="#0c2836",
        ink_secondary="#155066",
        ink_muted="#4a7186",
        curve="#00708f",
        fill="#00708f",
        fill_alpha=(0.20, 0.0),
        ceiling="#b4530a",
        accent="#7a3fb8",
        ornament="rgba(0,112,143,0.8)",
        scrim="rgba(232,242,248,1)",
        scrim_alpha=0.9,
        surface_tint="#d6e7f0",
        surface_edge="rgba(0,112,143,0.5)",
        grid="rgba(0,112,143,0.14)",
        axis="rgba(0,112,143,0.35)",
    ),
)

# ---- retro dive computer: an old segment LCD ------------------------------
#
# Three, not two, and the extra one earns its place: a dive computer's screen is
# where this style's whole identity lives, and green, amber and blue-EL are
# three different machines rather than three tints of one.
_register(
    "retro",
    style_theme(
        "retro-lcd",
        mode="light",
        profile="monochrome",
        surface="#b0bc9c",
        ink="#1c241c",
        ink_secondary="rgba(28,36,28,0.88)",
        ink_muted="rgba(58,69,54,0.85)",
        curve="#1c241c",
        fill="#1c241c",
        fill_alpha=(0.2, 0.2),
        ceiling="#1c241c",
        accent="#1c241c",
        ornament="rgba(28,36,28,0.8)",
        surface_tint="#a9b596",
        surface_edge="#2e3230",
    ),
    style_theme(
        "retro-amber",
        mode="dark",
        profile="monochrome",
        surface="#1e1608",
        ink="#ffb347",
        ink_secondary="rgba(255,179,71,0.88)",
        ink_muted="rgba(255,179,71,0.68)",
        curve="#ffb347",
        fill="#ffb347",
        fill_alpha=(0.24, 0.24),
        ceiling="#ffb347",
        accent="#ffb347",
        ornament="rgba(255,179,71,0.8)",
        surface_tint="#150f04",
        surface_edge="#2a2318",
    ),
    style_theme(
        "retro-el",
        mode="dark",
        profile="monochrome",
        surface="#07202c",
        ink="#7ff0ff",
        ink_secondary="rgba(127,240,255,0.88)",
        ink_muted="rgba(127,240,255,0.66)",
        curve="#7ff0ff",
        fill="#7ff0ff",
        fill_alpha=(0.22, 0.22),
        ceiling="#7ff0ff",
        accent="#7ff0ff",
        ornament="rgba(127,240,255,0.8)",
        surface_tint="#04141c",
        surface_edge="#10222c",
    ),
)

# ---- topographic survey: a field map --------------------------------------
_register(
    "topo",
    style_theme(
        "topo-field",
        mode="light",
        profile="expressive",
        surface="#ece7d3",
        ink="#4a3b28",
        ink_secondary="#6b5738",
        ink_muted="#8a7a55",
        curve="#33546b",
        fill="#5a8caa",
        fill_alpha=(0.18, 0.18),
        ceiling="#b23a2e",
        # Searched. A deeper violet reads better on cream but measures ΔE 12.5
        # against the survey blue to normal vision — below the floor, so the
        # two would blur into one series on the plot.
        accent="#915bc2",
        ornament="#7a643c",
        surface_tint="#e5dfc7",
        surface_edge="#b5a880",
        grid="rgba(74,59,40,0.18)",
        axis="rgba(74,59,40,0.35)",
    ),
    style_theme(
        "topo-blueprint",
        mode="dark",
        profile="expressive",
        surface="#0f3050",
        ink="#dfeaf6",
        ink_secondary="rgba(223,234,246,0.84)",
        ink_muted="rgba(223,234,246,0.62)",
        curve="#8fd0ff",
        fill="#8fd0ff",
        fill_alpha=(0.2, 0.2),
        ceiling="#ff8f7a",
        accent="#ffd166",
        ornament="rgba(223,234,246,0.42)",
        surface_tint="#0b2540",
        surface_edge="rgba(223,234,246,0.4)",
        grid="rgba(223,234,246,0.16)",
        axis="rgba(223,234,246,0.34)",
    ),
)
