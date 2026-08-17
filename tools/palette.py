"""Colour maths, so themes can be generated and checked instead of eyeballed.

Every theme this package ships is *computed*: a base hue goes in, and a full set
of tokens comes out with its accent chosen by search rather than by taste, then
verified against the same gates the hand-built themes passed. That is the only
way to let callers pick colours without quietly losing the guarantees — an
arbitrary hex looks fine to the author and can be invisible to a protanope.

The checks are the standard data-viz ones, on OKLab ΔE ×100 with the
Machado-Oliveira-Fernandes (2009) CVD transforms at severity 1.0:

* lightness inside the mode's band, so marks read against their surface
* chroma above a floor, below which a hue reads as grey
* CVD separation ≥ 8 between every pair of colour-bearing marks (6–8 is a floor
  band, legal only where a text label carries the meaning too)
* normal-vision separation ≥ 15 — a hard gate, since full-colour readers must
  also tell the marks apart
* WCAG contrast ≥ 3:1 against the surface, or a documented text-label relief

The thresholds and the simulation matrices are calibrated together; swapping the
CVD model would move borderline pairs and invalidate the numbers.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

__all__ = [
    "BAND",
    "CheckResult",
    "PaletteReport",
    "best_in_band",
    "contrast",
    "delta_e",
    "hex_to_oklch",
    "max_chroma",
    "oklch_to_hex",
    "pick_accent",
    "simulate",
    "validate",
]

#: OKLCH lightness band per mode. Dark is narrower: a mark on a near-black
#: surface has less room before it either disappears or glares.
BAND: dict[str, tuple[float, float]] = {"light": (0.43, 0.77), "dark": (0.48, 0.67)}

CHROMA_FLOOR = 0.10
CVD_TARGET, CVD_FLOOR = 8.0, 6.0
NORMAL_FLOOR = 15.0
CONTRAST_MIN = 3.0

DEFAULT_SURFACE = {"light": "#fcfcfb", "dark": "#1a1a19"}

_MACHADO = {
    "protan": (
        (0.152286, 1.052583, -0.204868),
        (0.114503, 0.786281, 0.099216),
        (-0.003882, -0.048116, 1.051998),
    ),
    "deutan": (
        (0.367322, 0.860646, -0.227968),
        (0.280085, 0.672501, 0.047413),
        (-0.011820, 0.042940, 0.968881),
    ),
    "tritan": (
        (1.255528, -0.076749, -0.178779),
        (-0.078411, 0.930809, 0.147602),
        (0.004733, 0.691367, 0.303900),
    ),
}


# ---------------------------------------------------------------------------
# conversions


def _hex_to_srgb(value: str) -> tuple[float, float, float]:
    raw = value.strip().lstrip("#")
    if len(raw) == 3:
        raw = "".join(c * 2 for c in raw)
    if len(raw) != 6:
        raise ValueError(f"not a hex colour: {value!r}")
    return tuple(int(raw[i : i + 2], 16) / 255 for i in (0, 2, 4))  # type: ignore[return-value]


def _s2lin(c: float) -> float:
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _lin2s(c: float) -> float:
    c = min(1.0, max(0.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055


def _linear(value: str) -> tuple[float, float, float]:
    r, g, b = _hex_to_srgb(value)
    return _s2lin(r), _s2lin(g), _s2lin(b)


def _oklab_from_linear(rgb: tuple[float, float, float]) -> tuple[float, float, float]:
    r, g, b = rgb
    l = (0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b) ** (1 / 3)
    m = (0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b) ** (1 / 3)
    s = (0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b) ** (1 / 3)
    return (
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )


def _linear_from_oklab(lab: tuple[float, float, float]) -> tuple[float, float, float]:
    big_l, a, b = lab
    l_ = big_l + 0.3963377774 * a + 0.2158037573 * b
    m_ = big_l - 0.1055613458 * a - 0.0638541728 * b
    s_ = big_l - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_**3, m_**3, s_**3
    return (
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )


def hex_to_oklch(value: str) -> tuple[float, float, float]:
    """``(L, C, H°)`` for a hex colour."""
    big_l, a, b = _oklab_from_linear(_linear(value))
    return big_l, math.hypot(a, b), math.degrees(math.atan2(b, a)) % 360


def oklch_to_hex(big_l: float, chroma: float, hue_deg: float) -> str:
    """Hex for an OKLCH triple, desaturating as needed to land in sRGB.

    Out-of-gamut requests are common when walking hues at fixed chroma — the
    sRGB cube is markedly wider in blue than in yellow-green. Reducing chroma
    while holding lightness and hue keeps the colour recognisably the one asked
    for, which clipping RGB channels does not.
    """
    radians = math.radians(hue_deg)
    for step in range(101):
        c = chroma * (1 - step / 100)
        lab = (big_l, c * math.cos(radians), c * math.sin(radians))
        rgb = _linear_from_oklab(lab)
        if all(-1e-4 <= channel <= 1 + 1e-4 for channel in rgb):
            return "#" + "".join(
                f"{round(_lin2s(channel) * 255):02x}" for channel in rgb
            )
    return "#808080"


# ---------------------------------------------------------------------------
# measurements


def _relative_luminance(value: str) -> float:
    r, g, b = _linear(value)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast(a: str, b: str) -> float:
    """WCAG contrast ratio between two colours."""
    high, low = sorted((_relative_luminance(a), _relative_luminance(b)), reverse=True)
    return (high + 0.05) / (low + 0.05)


def simulate(value: str, kind: str) -> tuple[float, float, float]:
    """Linear-RGB of ``value`` as seen with ``protan``/``deutan``/``tritan``."""
    r, g, b = _linear(value)
    matrix = _MACHADO[kind]
    return tuple(  # type: ignore[return-value]
        min(1.0, max(0.0, row[0] * r + row[1] * g + row[2] * b)) for row in matrix
    )


def delta_e(a: str, b: str, kind: str | None = None) -> float:
    """OKLab ΔE ×100 between two colours, optionally under simulated CVD."""
    first = _oklab_from_linear(simulate(a, kind) if kind else _linear(a))
    second = _oklab_from_linear(simulate(b, kind) if kind else _linear(b))
    return 100 * math.dist(first, second)


# ---------------------------------------------------------------------------
# validation


@dataclass(frozen=True, slots=True)
class CheckResult:
    name: str
    state: str  # "pass" | "warn" | "fail"
    detail: str

    @property
    def ok(self) -> bool:
        return self.state != "fail"


@dataclass(frozen=True, slots=True)
class PaletteReport:
    checks: tuple[CheckResult, ...]
    worst_cvd: float
    worst_normal: float

    @property
    def ok(self) -> bool:
        return all(check.ok for check in self.checks)

    @property
    def warnings(self) -> tuple[CheckResult, ...]:
        return tuple(c for c in self.checks if c.state == "warn")

    def summary(self) -> str:
        verdict = "PASS" if self.ok else "FAIL"
        return (
            f"{verdict}  CVD ΔE {self.worst_cvd:.1f}  normal ΔE {self.worst_normal:.1f}"
        )

    def __str__(self) -> str:
        lines = [self.summary()]
        lines += [
            f"  [{c.state.upper():4}] {c.name:22} {c.detail}" for c in self.checks
        ]
        return "\n".join(lines)


def validate(
    colours: list[str],
    *,
    mode: str = "dark",
    surface: str | None = None,
    pairs: str = "all",
) -> PaletteReport:
    """Run the six checks over the colour-bearing marks of a palette.

    ``pairs="all"`` is the right setting for this renderer: the marks scatter
    across the plot rather than sitting in a fixed stacking order, so any two
    can end up side by side.
    """
    surface = surface or DEFAULT_SURFACE[mode]
    low, high = BAND[mode]
    checks: list[CheckResult] = []

    off_band = [
        (c, round(hex_to_oklch(c)[0], 3))
        for c in colours
        if not low <= hex_to_oklch(c)[0] <= high
    ]
    checks.append(
        CheckResult(
            "Lightness band",
            "fail" if off_band else "pass",
            f"outside L {low}–{high}: {off_band}"
            if off_band
            else f"all {len(colours)} inside L {low}–{high}",
        )
    )

    low_chroma = [
        (c, round(hex_to_oklch(c)[1], 3))
        for c in colours
        if hex_to_oklch(c)[1] < CHROMA_FLOOR
    ]
    checks.append(
        CheckResult(
            "Chroma floor",
            "fail" if low_chroma else "pass",
            f"reads grey: {low_chroma}"
            if low_chroma
            else f"all {len(colours)} ≥ {CHROMA_FLOOR}",
        )
    )

    count = len(colours)
    if pairs == "all":
        pairlist = [(i, j) for i in range(count) for j in range(i + 1, count)]
    else:
        pairlist = [(i, i + 1) for i in range(count - 1)]

    worst_cvd, worst_cvd_pair = math.inf, ("", "", "")
    for kind in ("protan", "deutan"):
        for i, j in pairlist:
            measured = delta_e(colours[i], colours[j], kind)
            if measured < worst_cvd:
                worst_cvd = measured
                worst_cvd_pair = (colours[i], colours[j], kind)
    if not pairlist:
        worst_cvd = 99.0

    cvd_state = (
        "pass"
        if worst_cvd >= CVD_TARGET
        else "warn"
        if worst_cvd >= CVD_FLOOR
        else "fail"
    )
    checks.append(
        CheckResult(
            "CVD separation",
            cvd_state,
            f"worst {worst_cvd_pair[0]}↔{worst_cvd_pair[1]} "
            f"ΔE {worst_cvd:.1f} ({worst_cvd_pair[2]})"
            if pairlist
            else "single mark",
        )
    )

    worst_normal, worst_normal_pair = math.inf, ("", "")
    for i, j in pairlist:
        measured = delta_e(colours[i], colours[j])
        if measured < worst_normal:
            worst_normal, worst_normal_pair = measured, (colours[i], colours[j])
    if not pairlist:
        worst_normal = 99.0
    checks.append(
        CheckResult(
            "Normal-vision floor",
            "pass" if worst_normal >= NORMAL_FLOOR else "fail",
            f"worst {worst_normal_pair[0]}↔{worst_normal_pair[1]} ΔE {worst_normal:.1f}"
            if pairlist
            else "single mark",
        )
    )

    dim = [
        (c, round(contrast(c, surface), 2))
        for c in colours
        if contrast(c, surface) < CONTRAST_MIN
    ]
    checks.append(
        CheckResult(
            "Contrast vs surface",
            "warn" if dim else "pass",
            f"below {CONTRAST_MIN}:1, needs a text label: {dim}"
            if dim
            else f"all {len(colours)} ≥ {CONTRAST_MIN}:1",
        )
    )

    return PaletteReport(tuple(checks), worst_cvd, worst_normal)


# ---------------------------------------------------------------------------
# generation


def _in_gamut(big_l: float, chroma: float, hue_deg: float) -> bool:
    radians = math.radians(hue_deg)
    rgb = _linear_from_oklab(
        (big_l, chroma * math.cos(radians), chroma * math.sin(radians))
    )
    return all(-1e-4 <= channel <= 1 + 1e-4 for channel in rgb)


def max_chroma(big_l: float, hue_deg: float, limit: float = 0.4) -> float:
    """Greatest chroma sRGB can hold at this lightness and hue."""
    low, high = 0.0, limit
    for _ in range(24):
        mid = (low + high) / 2
        if _in_gamut(big_l, mid, hue_deg):
            low = mid
        else:
            high = mid
    return low


def best_in_band(
    hue_deg: float, mode: str, ceiling: float = 0.16
) -> tuple[float, float]:
    """``(lightness, chroma)`` for the most saturated usable version of a hue.

    The sRGB gamut is far from a cylinder — cyan and yellow run out of chroma at
    lightnesses where blue and magenta have plenty left. Asking for a fixed
    chroma across the hue circle therefore silently produces near-grey marks in
    the narrow regions, which then fail the chroma floor. Sweeping the band for
    the lightness that admits the most chroma avoids that without hand-tuning
    each hue.
    """
    low, high = BAND[mode]
    steps = 40
    best = (low, 0.0)
    for index in range(steps + 1):
        lightness = low + index * (high - low) / steps
        chroma = min(max_chroma(lightness, hue_deg), ceiling)
        if chroma > best[1]:
            best = (lightness, chroma)
    return best


def snap_to_band(value: str, mode: str) -> str:
    """Move a colour into the mode's lightness band, keeping its hue usable.

    Chroma is raised to the floor where the gamut allows it, and where it does
    not the lightness moves to wherever that hue is most saturated — a colour
    that lands below the chroma floor reads as grey and stops being a hue at all.
    """
    low, high = BAND[mode]
    big_l, chroma, hue = hex_to_oklch(value)
    target = min(high - 0.01, max(low + 0.01, big_l))

    wanted = max(chroma, CHROMA_FLOOR + 0.02)
    if min(max_chroma(target, hue), wanted) < CHROMA_FLOOR + 0.01:
        target, wanted = best_in_band(hue, mode)

    return oklch_to_hex(target, wanted, hue)


def pick_accent(
    curve: str,
    ceiling: str,
    *,
    mode: str = "dark",
    surface: str | None = None,
    step_deg: int = 6,
) -> str:
    """Choose the gas-switch accent by search rather than by eye.

    Walks the hue circle at the mode's mid-lightness and keeps the candidate
    whose *worst* separation from the curve and the ceiling is largest, so the
    accent is as far from both as the gamut allows. Searching beats picking:
    the same hue that reads well beside a blue curve can collapse against a
    teal one, and only measuring catches that.
    """
    surface = surface or DEFAULT_SURFACE[mode]
    low, high = BAND[mode]
    lightness = (low + high) / 2

    best, best_score = "#c98500", -math.inf
    for hue in range(0, 360, step_deg):
        for chroma in (0.16, 0.13, 0.10):
            candidate = oklch_to_hex(lightness, chroma, hue)
            got_l, got_c, _ = hex_to_oklch(candidate)
            if got_c < CHROMA_FLOOR or not low <= got_l <= high:
                continue

            separations = [
                min(
                    delta_e(candidate, other, "protan"),
                    delta_e(candidate, other, "deutan"),
                )
                for other in (curve, ceiling)
            ]
            normals = [delta_e(candidate, other) for other in (curve, ceiling)]
            # A candidate that fails the hard normal-vision gate is unusable no
            # matter how well it separates under simulation.
            if min(normals) < NORMAL_FLOOR:
                continue

            score = min(*separations, min(normals) / 2)
            if contrast(candidate, surface) < CONTRAST_MIN:
                score -= 2.0  # usable, but leans on its text label
            if score > best_score:
                best, best_score = candidate, score
    return best
