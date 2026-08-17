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
    "THEMES",
    "Theme",
    "build_theme",
    "validate_theme",
]


@dataclass(frozen=True, slots=True)
class Theme:
    """Colour and type tokens. Colours are CSS colour strings."""

    name: str

    #: The background this theme was validated against. Never painted — the
    #: output is transparent — but recorded so the palette can be re-checked.
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
    ceiling_fill: str
    accent: str

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
    ceiling_fill="rgba(208,59,59,0.22)",
    accent="#c98500",
)

#: For compositing onto a pale background — a log-book page, a document.
LIGHT = Theme(
    name="light",
    assumed_surface="#fcfcfb",
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
    ceiling_fill="rgba(208,59,59,0.18)",
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
        ceiling_fill=_rgba(CEILING, 0.22 if mode == "dark" else 0.18),
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
    """Check a theme's three colour-bearing marks against the palette gates."""
    mode = "dark" if theme.assumed_surface == DEFAULT_SURFACE["dark"] else "light"
    return validate(
        [theme.curve, theme.ceiling, theme.accent],
        mode=mode,
        surface=theme.assumed_surface,
        pairs="all",
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
