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
    "CHROMATIC",
    "EXPRESSIVE",
    "MONOCHROME",
    "PROFILES",
    "CheckResult",
    "Gates",
    "PaletteReport",
    "best_in_band",
    "contrast",
    "delta_e",
    "gates_for",
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


@dataclass(frozen=True, slots=True)
class Gates:
    """One profile's thresholds, and how this palette separates its marks.

    The original gates were written for a single style, and they encode more
    than measurements: they assume the marks are told apart *by hue*, over a
    panel, with a fixed hazard red among them. That assumption is what makes a
    lime curve illegal and a white one meaningless, and it stops being true the
    moment a second style draws its ceiling as a dashed white line on an opaque
    violet card.

    So the thresholds move to a profile rather than being loosened in place.
    Loosening in place would leave one suite quietly failing to certify anything
    in particular; a profile has to say which claim it is making, and
    :attr:`separation` is that claim.

    :attr:`separation` is the load-bearing field:

    ``"hue"``
        The marks differ in colour, and the CVD and normal-vision floors are
        what proves it. Fatal when they fail.
    ``"form"``
        The marks are one ink and differ in *shape* — dash, stroke width,
        hatching. Colour separation is still measured and recorded, but it is
        not the evidence, so it cannot be the gate. What replaces it is a
        contrast floor that becomes fatal (ink has nothing else to lean on) and
        a check in the renderer's own tests that the marks really do differ in
        form, which is the only place that claim can be checked at all.
    """

    name: str
    band: dict[str, tuple[float, float]]
    chroma_floor: float
    cvd_target: float
    cvd_floor: float
    normal_floor: float
    contrast_min: float
    contrast_fatal: bool
    separation: str  # "hue" | "form"


#: The gates the nine Modern palettes cleared, unchanged.
CHROMATIC = Gates(
    name="chromatic",
    band=BAND,
    chroma_floor=CHROMA_FLOOR,
    cvd_target=CVD_TARGET,
    cvd_floor=CVD_FLOOR,
    normal_floor=NORMAL_FLOOR,
    contrast_min=CONTRAST_MIN,
    contrast_fatal=False,
    separation="hue",
)

#: For styles whose card is opaque and whose colour is the point.
#:
#: The lightness band exists because a mark on a *transparent* slate lands on
#: footage of unknown brightness, so it has to survive both ends. A style that
#: paints its own opaque card has already answered that question — the mark is
#: read against a known background — which is why the band widens here and only
#: here. The separation floors do not move: they are about the eye, not about
#: the backdrop, and nothing about an opaque card makes protanopia easier.
EXPRESSIVE = Gates(
    name="expressive",
    band={"light": (0.15, 1.0), "dark": (0.15, 1.0)},
    chroma_floor=0.04,
    cvd_target=CVD_TARGET,
    cvd_floor=CVD_FLOOR,
    normal_floor=NORMAL_FLOOR,
    contrast_min=CONTRAST_MIN,
    contrast_fatal=False,
    separation="hue",
)

#: For styles drawn in a single ink: the magazine masthead, the LCD screen,
#: frosted glass.
#:
#: Every colour check that measures *difference between marks* is inapplicable
#: here rather than merely relaxed, because the marks are the same colour on
#: purpose. They are still measured and reported, so the numbers are on the
#: record, but they report as info: a gate that always fails proves nothing, and
#: one silently dropped to zero proves less. The contrast floor turns fatal in
#: exchange, since ink with no hue to spend has only its lightness left.
MONOCHROME = Gates(
    name="monochrome",
    band={"light": (0.0, 1.0), "dark": (0.0, 1.0)},
    chroma_floor=0.0,
    cvd_target=CVD_TARGET,
    cvd_floor=CVD_FLOOR,
    normal_floor=NORMAL_FLOOR,
    contrast_min=CONTRAST_MIN,
    contrast_fatal=True,
    separation="form",
)

PROFILES: dict[str, Gates] = {
    profile.name: profile for profile in (CHROMATIC, EXPRESSIVE, MONOCHROME)
}


def gates_for(profile: str | Gates) -> Gates:
    if isinstance(profile, Gates):
        return profile
    try:
        return PROFILES[profile]
    except KeyError:
        raise ValueError(
            f"unknown palette profile {profile!r}; have {sorted(PROFILES)}"
        ) from None


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
    #: "pass" | "warn" | "fail" | "info". "info" is a check that does not
    #: apply to this profile: measured and recorded, but not evidence.
    state: str
    detail: str

    @property
    def ok(self) -> bool:
        return self.state != "fail"


@dataclass(frozen=True, slots=True)
class PaletteReport:
    checks: tuple[CheckResult, ...]
    worst_cvd: float
    worst_normal: float
    #: Which gates produced this verdict. A "PASS" means nothing without it.
    profile: str = "chromatic"

    @property
    def ok(self) -> bool:
        return all(check.ok for check in self.checks)

    @property
    def warnings(self) -> tuple[CheckResult, ...]:
        return tuple(c for c in self.checks if c.state == "warn")

    def summary(self) -> str:
        verdict = "PASS" if self.ok else "FAIL"
        return (
            f"{verdict} [{self.profile}]  CVD ΔE {self.worst_cvd:.1f}  "
            f"normal ΔE {self.worst_normal:.1f}"
        )

    def __str__(self) -> str:
        lines = [self.summary()]
        lines += [
            f"  [{c.state.upper():4}] {c.name:22} {c.detail}" for c in self.checks
        ]
        return "\n".join(lines)


#: Chroma at or below which a colour is a neutral by construction — white,
#: black, a true grey — rather than a hue that lost its saturation.
NEUTRAL_CHROMA = 0.02


def _is_neutral(value: str) -> bool:
    """Whether this colour is achromatic on purpose.

    The chroma floor exists to catch a mark *meant* as a hue that came out grey,
    and that failure lands in the ambiguous middle — chroma around 0.03 to 0.09,
    saturated enough to have been intended and too flat to read. A mark at
    chroma zero is not that: nothing lands exactly on zero by accident, so it
    was typed as a neutral, and a white ceiling on a violet card is a decision
    rather than a slip.
    """
    _, chroma, _ = hex_to_oklch(value)
    return chroma <= NEUTRAL_CHROMA


def validate(
    colours: list[str],
    *,
    mode: str = "dark",
    surface: str | None = None,
    pairs: str = "all",
    profile: str | Gates = "chromatic",
) -> PaletteReport:
    """Run the six checks over the colour-bearing marks of a palette.

    ``pairs="all"`` is the right setting for this renderer: the marks scatter
    across the plot rather than sitting in a fixed stacking order, so any two
    can end up side by side.

    ``profile`` selects the thresholds and, more importantly, what this suite is
    being asked to certify — see :class:`Gates`. It defaults to ``"chromatic"``,
    so a caller that does not think about it gets the strictest set.
    """
    surface = surface or DEFAULT_SURFACE[mode]
    gates = gates_for(profile)
    by_hue = gates.separation == "hue"
    low, high = gates.band[mode]
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

    # A near-white or near-black mark is a neutral on purpose — the white
    # ceiling on an opaque card, say — not a hue that desaturated into grey,
    # which is the failure this floor exists to catch. Exempting them by
    # lightness keeps the check aimed at the thing it was written for. It
    # changes nothing for the chromatic palettes: none of their marks are
    # neutral, so none are exempt.
    low_chroma = [
        (c, round(hex_to_oklch(c)[1], 3))
        for c in colours
        if hex_to_oklch(c)[1] < gates.chroma_floor and not _is_neutral(c)
    ]
    checks.append(
        CheckResult(
            "Chroma floor",
            "info" if gates.chroma_floor <= 0.0 else "fail" if low_chroma else "pass",
            "waived: this palette is one ink"
            if gates.chroma_floor <= 0.0
            else f"reads grey: {low_chroma}"
            if low_chroma
            else f"all {len(colours)} ≥ {gates.chroma_floor}",
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
        if worst_cvd >= gates.cvd_target
        else "warn"
        if worst_cvd >= gates.cvd_floor or not by_hue
        else "fail"
    )
    if not by_hue:
        cvd_state = "info"
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
            "info"
            if not by_hue
            else "pass"
            if worst_normal >= gates.normal_floor
            else "fail",
            f"worst {worst_normal_pair[0]}↔{worst_normal_pair[1]} ΔE {worst_normal:.1f}"
            if pairlist
            else "single mark",
        )
    )

    dim = [
        (c, round(contrast(c, surface), 2))
        for c in colours
        if contrast(c, surface) < gates.contrast_min
    ]
    checks.append(
        CheckResult(
            "Contrast vs surface",
            ("fail" if gates.contrast_fatal else "warn") if dim else "pass",
            f"below {gates.contrast_min}:1"
            + (
                ", and nothing else separates these marks"
                if gates.contrast_fatal
                else ", needs a text label"
            )
            + f": {dim}"
            if dim
            else f"all {len(colours)} ≥ {gates.contrast_min}:1",
        )
    )

    return PaletteReport(tuple(checks), worst_cvd, worst_normal, gates.name)


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
    hue_deg: float,
    mode: str,
    ceiling: float = 0.16,
    *,
    profile: str | Gates = "chromatic",
) -> tuple[float, float]:
    """``(lightness, chroma)`` for the most saturated usable version of a hue.

    The sRGB gamut is far from a cylinder — cyan and yellow run out of chroma at
    lightnesses where blue and magenta have plenty left. Asking for a fixed
    chroma across the hue circle therefore silently produces near-grey marks in
    the narrow regions, which then fail the chroma floor. Sweeping the band for
    the lightness that admits the most chroma avoids that without hand-tuning
    each hue.
    """
    low, high = gates_for(profile).band[mode]
    steps = 40
    best = (low, 0.0)
    for index in range(steps + 1):
        lightness = low + index * (high - low) / steps
        chroma = min(max_chroma(lightness, hue_deg), ceiling)
        if chroma > best[1]:
            best = (lightness, chroma)
    return best


def snap_to_band(value: str, mode: str, *, profile: str | Gates = "chromatic") -> str:
    """Move a colour into the mode's lightness band, keeping its hue usable.

    Chroma is raised to the floor where the gamut allows it, and where it does
    not the lightness moves to wherever that hue is most saturated — a colour
    that lands below the chroma floor reads as grey and stops being a hue at all.
    """
    gates = gates_for(profile)
    low, high = gates.band[mode]
    big_l, chroma, hue = hex_to_oklch(value)
    target = min(high - 0.01, max(low + 0.01, big_l))

    floor = max(gates.chroma_floor, 0.01)
    wanted = max(chroma, floor + 0.02)
    if min(max_chroma(target, hue), wanted) < floor + 0.01:
        target, wanted = best_in_band(hue, mode, profile=gates)

    return oklch_to_hex(target, wanted, hue)


def pick_accent(
    *marks: str,
    mode: str = "dark",
    surface: str | None = None,
    step_deg: int = 6,
    profile: str | Gates = "chromatic",
) -> str:
    """Choose the gas-switch accent by search rather than by eye.

    Walks the hue circle at the mode's mid-lightness and keeps the candidate
    whose *worst* separation from every given mark is largest, so the accent is
    as far from all of them as the gamut allows. Searching beats picking: the
    same hue that reads well beside a blue curve can collapse against a teal
    one, and only measuring catches that.

    ``marks`` is variadic because a style may put more than two colours on the
    plot — a gradient curve has two ends, and an accent measured against only
    one of them is measured against half the picture.
    """
    surface = surface or DEFAULT_SURFACE[mode]
    gates = gates_for(profile)
    low, high = gates.band[mode]
    lightness = (low + high) / 2

    best, best_score = "#c98500", -math.inf
    for hue in range(0, 360, step_deg):
        for chroma in (0.16, 0.13, 0.10):
            candidate = oklch_to_hex(lightness, chroma, hue)
            got_l, got_c, _ = hex_to_oklch(candidate)
            if got_c < gates.chroma_floor or not low <= got_l <= high:
                continue

            separations = [
                min(
                    delta_e(candidate, other, "protan"),
                    delta_e(candidate, other, "deutan"),
                )
                for other in marks
            ]
            normals = [delta_e(candidate, other) for other in marks]
            # A candidate that fails the hard normal-vision gate is unusable no
            # matter how well it separates under simulation.
            if min(normals) < gates.normal_floor:
                continue

            score = min(min(separations), min(normals) / 2)
            if contrast(candidate, surface) < gates.contrast_min:
                score -= 2.0  # usable, but leans on its text label
            if score > best_score:
                best, best_score = candidate, score
    return best
