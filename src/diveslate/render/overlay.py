"""The compact slate: a badge to drop over a photo or a video frame.

This is a different object from :mod:`diveslate.render.profile`, not a smaller
version of it. That module draws a *chart* — something you read axis values off.
This one draws a *badge*: the profile silhouette as a recognisable shape, three
big numbers, and nothing else. On a phone screen at a third of frame width, axis
ticks and a legend are unreadable noise, so they are gone rather than shrunk.

Three constraints come from the medium and drive the whole layout:

* **The backdrop is arbitrary and moving.** Text halos handle a still photo;
  they are not enough over video where the frame behind a label changes every
  few frames. Hence the scrim panel, on by default.
* **It gets scaled down.** Everything is sized generously so the slate survives
  being dropped in at 40% and re-encoded by Instagram.
* **Its own size is the deliverable.** The slate renders at its natural compact
  size so it can be dragged around in an editor. Use :func:`render_overlay_canvas`
  to get it pre-placed on a full Instagram canvas instead.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, replace
from typing import Any, Literal

from diveslate.core.models import Dive, Sample
from diveslate.core.units import ceil_metres, format_minutes
from diveslate.render.svg import Canvas, points_to_path
from diveslate.render.theme import Theme, get_theme

__all__ = [
    "CANVAS_SIZES",
    "OverlayOptions",
    "Position",
    "render_overlay",
    "render_overlay_canvas",
]

Position = Literal[
    "top-left",
    "top-right",
    "bottom-left",
    "bottom-right",
    "top-center",
    "bottom-center",
    "center",
]

#: Instagram's canvas sizes. Feed posts are 1080 wide whatever the ratio.
CANVAS_SIZES: dict[str, tuple[int, int]] = {
    "square": (1080, 1080),
    "portrait": (1080, 1350),
    "story": (1080, 1920),
    "landscape": (1080, 566),
}


@dataclass(frozen=True, slots=True)
class OverlayOptions:
    """Shape and content of the compact slate."""

    #: Slate width in pixels. 1080 matches Instagram's native width, so a slate
    #: dropped in at full width stays pixel-crisp.
    width: float = 1080.0
    theme: Theme | str = "slate"

    show_scrim: bool = True
    show_site: bool = True
    show_date: bool = False
    show_ceiling: bool = True

    #: Gas-switch markers and mix labels on the curve itself.
    show_gas: bool = False

    #: Whether the derived deco time may take an automatic stat slot. It is an
    #: inference from the ceiling, not a figure the log states, so it is worth
    #: being able to drop without hand-listing every other stat.
    show_deco: bool = True

    #: Which summary values to show, in order. ``None`` picks automatically.
    stats: tuple[str, ...] | None = None
    max_stats: int = 3

    #: ``"wide"`` is the landscape badge that suits a feed post or a corner of a
    #: video. ``"tall"`` enlarges the type and gives the profile far more height,
    #: for a 9:16 story where a wide strip reads as a small band in a big empty
    #: frame — and where the viewer is holding the phone, so the numbers need to
    #: survive being seen at arm's length.
    #: ``None`` means auto: :func:`render_overlay` treats it as ``"wide"``, and
    #: :func:`render_overlay_canvas` upgrades it to ``"tall"`` for a tall canvas.
    layout: Literal["wide", "tall"] | None = None

    corner_radius: float = 30.0

    def resolved_theme(self) -> Theme:
        return get_theme(self.theme)


# ---------------------------------------------------------------------------
# curve simplification


def _envelope(
    points: list[tuple[float, float]], target_width: float
) -> list[tuple[float, float]]:
    """Reduce the sample series to about two points per horizontal pixel.

    A 2000-sample dive drawn into a 900px-wide badge puts several samples on
    every pixel column. Plain every-Nth decimation would drop the deepest point
    of a column and visibly clip the spikes that give a profile its character,
    so each column keeps its shallowest and deepest samples, emitted in the
    order they occur so the line still reads left to right.
    """
    if len(points) <= target_width * 2:
        return points

    columns: dict[int, list[tuple[float, float]]] = {}
    for x, y in points:
        columns.setdefault(int(x), []).append((x, y))

    reduced: list[tuple[float, float]] = []
    for key in sorted(columns):
        bucket = columns[key]
        if len(bucket) <= 2:
            reduced.extend(bucket)
            continue
        shallowest = min(bucket, key=lambda p: p[1])
        deepest = max(bucket, key=lambda p: p[1])
        pair = sorted({shallowest, deepest}, key=bucket.index)
        reduced.extend(pair)
    return reduced


# ---------------------------------------------------------------------------
# content


def _auto_stats(
    dive: Dive, limit: int, *, allow_deco: bool = True
) -> list[tuple[str, str, str]]:
    """(label, value, unit) triples, most headline-worthy first.

    Depth and runtime lead because they are the two numbers every diver reads
    first. After that the order is deco, gradient factors, gas used, temperature,
    mix — whichever the log can actually supply, until ``limit`` is reached. GF
    sits beside deco because it is the setting that produced that deco time; the
    two are read together. Consumption ranks above temperature because it varies
    with how the dive was run, whereas water temperature is a property of the
    site that day. A candidate the log cannot answer is skipped, not shown blank.
    """
    runtime, runtime_unit = format_minutes(dive.computed_duration_s)
    chosen: list[tuple[str, str, str]] = [
        ("Max depth", str(ceil_metres(dive.computed_max_depth_m)), "m"),
        ("Runtime", runtime, runtime_unit),
    ]

    order = ["deco", "gf", "used", "temp", "gas"]
    if not allow_deco:
        order.remove("deco")
    for key in order:
        if len(chosen) >= limit:
            break
        if (built := _STAT_BUILDERS[key](dive)) is not None:
            chosen.append(built)

    return chosen[:limit]


StatBuilder = Callable[[Dive], tuple[str, str, str] | None]

_STAT_BUILDERS: dict[str, StatBuilder] = {
    "depth": lambda d: ("Max depth", str(ceil_metres(d.computed_max_depth_m)), "m"),
    "time": lambda d: ("Runtime", *format_minutes(d.computed_duration_s)),
    "avg": lambda d: (
        ("Avg depth", str(ceil_metres(d.computed_mean_depth_m)), "m")
        if d.computed_mean_depth_m is not None
        else None
    ),
    "temp": lambda d: (
        ("Temp", f"{d.temperature_range_c[0]:.0f}", "°C")
        if d.temperature_range_c is not None
        else (("Temp", f"{d.water_temp_c:.0f}", "°C") if d.water_temp_c else None)
    ),
    "deco": lambda d: (
        ("Deco", *format_minutes(deco))
        if (deco := d.deco_time_s()) is not None
        else None
    ),
    "gf": lambda d: (
        ("GF", f"{gf[0]}/{gf[1]}", "")
        if (gf := d.gradient_factors) is not None
        else None
    ),
    "used": lambda d: (
        ("Gas used", f"{used:.0f}", "L") if (used := d.gas_used_l) is not None else None
    ),
    "sac": lambda d: (
        ("SAC", f"{d.sac_l_min:.1f}", "L/min") if d.sac_l_min is not None else None
    ),
    "cns": lambda d: ("CNS", f"{d.cns * 100:.0f}", "%") if d.cns is not None else None,
    "gas": lambda d: (
        ("Gas", "/".join(dict.fromkeys(s.gas.name for s in d.gas_switches)), "")
        if d.gas_switches
        else None
    ),
}

STAT_KEYS = tuple(_STAT_BUILDERS)


def _named_stats(dive: Dive, keys: tuple[str, ...]) -> list[tuple[str, str, str]]:
    out: list[tuple[str, str, str]] = []
    for key in keys:
        builder = _STAT_BUILDERS.get(key)
        if builder is None:
            raise LookupError(
                f"unknown stat {key!r}; available: {', '.join(STAT_KEYS)}"
            )
        # Silently skip a stat the log cannot supply — asking for temperature on
        # a computer that never recorded it should not blank the whole slate.
        if (built := builder(dive)) is not None:
            out.append(built)
    return out


# ---------------------------------------------------------------------------
# rendering


def render_overlay(
    dive: Dive, options: OverlayOptions | None = None, **overrides: object
) -> str:
    """Render the compact slate to a transparent SVG document string."""
    opts = options or OverlayOptions()
    if overrides:
        opts = replace(opts, **overrides)  # type: ignore[arg-type]
    theme = opts.resolved_theme()

    if not dive.samples:
        raise ValueError(
            "this dive has no depth samples, so there is no profile to draw"
        )

    stats = (
        _named_stats(dive, opts.stats)
        if opts.stats is not None
        else _auto_stats(dive, opts.max_stats, allow_deco=opts.show_deco)
    )

    # Everything scales off the slate width so the design holds at any size;
    # the layout then chooses the proportions within that.
    scale = opts.width / 1080.0
    tall = opts.layout == "tall"

    pad = (56.0 if tall else 44.0) * scale
    site_size = (46.0 if tall else 34.0) * scale
    date_size = (28.0 if tall else 22.0) * scale
    value_size = (86.0 if tall else 56.0) * scale
    label_size = (24.0 if tall else 18.0) * scale
    curve_height = (430.0 if tall else 210.0) * scale
    gap = (34.0 if tall else 26.0) * scale

    has_heading = bool(
        (opts.show_site and dive.site) or (opts.show_date and dive.when is not None)
    )

    y = pad
    heading_block = 0.0
    if has_heading:
        if opts.show_site and dive.site:
            heading_block += site_size
        if opts.show_date and dive.when is not None:
            heading_block += date_size + 8.0 * scale
        heading_block += gap

    stats_block = value_size + label_size + 10.0 * scale
    height = pad + heading_block + curve_height + gap + stats_block + pad

    canvas = Canvas(opts.width, height)

    if opts.show_scrim:
        canvas.rect(
            0,
            0,
            opts.width,
            height,
            fill=theme.scrim,
            rx=opts.corner_radius * scale,
        )

    # ---- heading ----------------------------------------------------------
    if opts.show_site and dive.site:
        y += site_size * 0.78
        canvas.text(
            dive.site.upper(),
            pad,
            y,
            fill=theme.ink,
            halo=theme.halo,
            size=site_size,
            family=theme.font_family,
            weight="700",
            halo_width=5 * scale,
            letter_spacing="0.02em",
        )
        y += site_size * 0.32
    if opts.show_date and dive.when is not None:
        y += date_size * 0.9
        canvas.text(
            dive.when.strftime("%d %b %Y"),
            pad,
            y,
            fill=theme.ink_secondary,
            halo=theme.halo,
            size=date_size,
            family=theme.font_family,
            halo_width=4 * scale,
        )
    if has_heading:
        y = pad + heading_block

    # ---- profile ----------------------------------------------------------
    plot_left, plot_right = pad, opts.width - pad
    plot_top, plot_bottom = y, y + curve_height
    plot_width = plot_right - plot_left

    duration = max(dive.computed_duration_s, 1.0)
    # Headroom so the deepest point does not touch the baseline.
    depth_max = max(dive.computed_max_depth_m, 1.0) * 1.06

    def sx(t: float) -> float:
        return plot_left + (t / duration) * plot_width

    def sy(d: float) -> float:
        return plot_top + (d / depth_max) * curve_height

    points = _envelope(
        [(sx(s.time_s), sy(s.depth_m)) for s in dive.samples], plot_width
    )

    # Surface line: the reference the silhouette is read against, and the only
    # piece of chrome that survives into the badge.
    canvas.line(
        plot_left,
        plot_top,
        plot_right,
        plot_top,
        stroke=theme.axis,
        stroke_width=2 * scale,
    )

    if opts.show_ceiling:
        _draw_ceiling(canvas, dive, sx, sy, plot_top, theme, scale)

    fill = canvas.linear_gradient(
        "ds-overlay-fill",
        [(0.0, theme.curve_fill_top), (1.0, theme.curve_fill_bottom)],
    )
    area = [(points[0][0], plot_top), *points, (points[-1][0], plot_top)]
    canvas.path(points_to_path(area, close=True), fill=fill, stroke="none")
    canvas.path(
        points_to_path(points),
        fill="none",
        stroke=theme.curve,
        stroke_width=4.0 * scale,
        stroke_linejoin="round",
        stroke_linecap="round",
    )

    if opts.show_gas:
        _draw_gas(canvas, dive, sx, sy, theme, scale)

    # ---- stats ------------------------------------------------------------
    y = plot_bottom + gap + value_size * 0.8
    slot = (opts.width - pad * 2) / max(len(stats), 1)
    for index, (label, value, unit) in enumerate(stats):
        left = pad + index * slot
        canvas.text(
            value,
            left,
            y,
            fill=theme.ink,
            halo=theme.halo,
            size=value_size,
            family=theme.font_family,
            weight="700",
            halo_width=6 * scale,
        )
        if unit:
            canvas.text(
                unit,
                left + len(value) * value_size * 0.56 + 8 * scale,
                y,
                fill=theme.ink_secondary,
                halo=theme.halo,
                size=label_size * 1.35,
                family=theme.font_family,
                halo_width=4 * scale,
            )
        canvas.text(
            label.upper(),
            left,
            y + label_size + 8 * scale,
            fill=theme.ink_muted,
            halo=theme.halo,
            size=label_size,
            family=theme.font_family,
            weight="600",
            halo_width=4 * scale,
            letter_spacing="0.10em",
        )

    return canvas.to_svg(
        title=dive.title,
        description=(
            f"Dive profile: max {dive.computed_max_depth_m:.1f} m, "
            f"runtime {dive.computed_duration_s / 60:.0f} min."
        ),
    )


def _draw_ceiling(
    canvas: Canvas,
    dive: Dive,
    sx: Callable[[float], float],
    sy: Callable[[float], float],
    plot_top: float,
    theme: Theme,
    scale: float,
) -> None:
    """Stepped deco ceiling, hatched — same reading as the full chart's."""
    runs: list[list[Sample]] = []
    current: list[Sample] = []
    for sample in dive.samples:
        if sample.stop_depth_m:
            current.append(sample)
        elif current:
            runs.append(current)
            current = []
    if current:
        runs.append(current)
    if not runs:
        return

    hatch = canvas.hatch(
        "ds-overlay-ceiling",
        theme.ceiling,
        angle=45,
        spacing=9 * scale,
        width=1.8 * scale,
    )

    for run in runs:
        edge: list[tuple[float, float]] = []
        previous: float | None = None
        for sample in run:
            x = sx(sample.time_s)
            y = sy(sample.stop_depth_m or 0.0)
            if previous is not None and y != previous:
                edge.append((x, previous))
            edge.append((x, y))
            previous = y
        if not edge:
            continue
        polygon = [(edge[0][0], plot_top), *edge, (edge[-1][0], plot_top)]
        canvas.path(points_to_path(polygon, close=True), fill=hatch, stroke="none")
        canvas.path(
            points_to_path(edge),
            fill="none",
            stroke=theme.ceiling,
            stroke_width=2.5 * scale,
            stroke_linejoin="round",
        )


def _draw_gas(
    canvas: Canvas,
    dive: Dive,
    sx: Callable[[float], float],
    sy: Callable[[float], float],
    theme: Theme,
    scale: float,
) -> None:
    depth_at = {s.time_s: s.depth_m for s in dive.samples}
    times = sorted(depth_at)
    for switch in dive.gas_switches:
        nearest = min(times, key=lambda c: abs(c - switch.time_s))
        x, y = sx(switch.time_s), sy(depth_at[nearest])
        canvas.circle(
            x, y, 8 * scale, fill="none", stroke=theme.halo, stroke_width=4 * scale
        )
        canvas.circle(x, y, 8 * scale, fill=theme.accent, stroke="none")
        canvas.text(
            switch.gas.name,
            x + 12 * scale,
            y - 12 * scale,
            fill=theme.ink,
            halo=theme.halo,
            size=20 * scale,
            family=theme.font_family,
            weight="700",
            halo_width=4 * scale,
        )


def render_overlay_canvas(
    dive: Dive,
    *,
    canvas: str = "portrait",
    position: Position = "bottom-left",
    margin: float = 48.0,
    slate_scale: float = 0.86,
    options: OverlayOptions | None = None,
    **overrides: Any,
) -> str:
    """Place the slate on a full Instagram canvas, ready to drop straight on.

    Use this when you want the export to line up with the post exactly. For an
    editor where you position it by hand, :func:`render_overlay` gives a
    tight-cropped slate instead.
    """
    if canvas not in CANVAS_SIZES:
        known = ", ".join(CANVAS_SIZES)
        raise LookupError(f"unknown canvas {canvas!r}; available: {known}")
    canvas_w, canvas_h = CANVAS_SIZES[canvas]

    opts = options or OverlayOptions()
    if overrides:
        opts = replace(opts, **overrides)

    # A 9:16 story gets the tall layout unless one was explicitly asked for: the
    # wide badge is only a quarter of that frame's height and reads as a small
    # band adrift in it.
    if opts.layout is None and canvas_h / canvas_w >= 1.6:
        opts = replace(opts, layout="tall")

    # The canvas dictates the slate width, so this overrides any width the
    # caller passed — placement only makes sense at a known scale.
    opts = replace(opts, width=canvas_w * slate_scale)

    slate = render_overlay(dive, opts)
    slate_w, slate_h = _svg_size(slate)

    if "left" in position:
        x = margin
    elif "right" in position:
        x = canvas_w - slate_w - margin
    else:
        x = (canvas_w - slate_w) / 2

    if "top" in position:
        y = margin
    elif "bottom" in position:
        y = canvas_h - slate_h - margin
    else:
        y = (canvas_h - slate_h) / 2

    inner = slate[slate.index(">") + 1 : slate.rindex("</svg>")]
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{canvas_w}" '
        f'height="{canvas_h}" viewBox="0 0 {canvas_w} {canvas_h}" fill="none">'
        f'<g transform="translate({x:.2f} {y:.2f})">{inner}</g>'
        f"</svg>"
    )


def _svg_size(svg: str) -> tuple[float, float]:
    def attr(name: str) -> float:
        marker = f'{name}="'
        start = svg.index(marker) + len(marker)
        return float(svg[start : svg.index('"', start)])

    return attr("width"), attr("height")
