"""Draws the slate: depth curve, ceiling, gas switches, axes, stats.

Layer order is back-to-front and deliberate — the deco ceiling sits *under* the
depth curve so the curve always reads as the subject, and every label goes on
last so nothing paints over text.

One decision worth stating because the alternative is tempting: temperature and
tank pressure are **not** plotted as second curves against their own y-axis. A
second y-scale lets the author imply any correlation they like by sliding one
axis, and the reader has no way to see it happening; the numbers go in the stats
strip instead, where they are honest. Depth is the only thing this chart maps to
vertical space.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from diveslate.core.models import Dive, Sample
from diveslate.core.units import format_duration
from diveslate.render.layout import Layout, Margins
from diveslate.render.svg import Canvas, fmt, points_to_path, text
from diveslate.render.theme import Theme, get_theme

__all__ = ["RenderOptions", "render_svg"]


@dataclass(frozen=True, slots=True)
class RenderOptions:
    """What to draw and how big."""

    width: float = 1600.0
    height: float = 900.0
    theme: Theme | str = "slate"

    show_title: bool = True
    show_axes: bool = True
    show_grid: bool = True
    show_ceiling: bool = True
    show_gas: bool = True
    show_stats: bool = True
    show_legend: bool = True
    show_deco: bool = True

    #: Extra padding around the whole drawing, in pixels.
    padding: float = 8.0

    def resolved_theme(self) -> Theme:
        return get_theme(self.theme)


@dataclass(slots=True)
class _Stat:
    label: str
    value: str
    unit: str = ""


@dataclass(slots=True)
class _Legend:
    entries: list[tuple[str, str, str]] = field(default_factory=list)
    """(label, colour, kind) where kind is 'line', 'area' or 'dot'."""


# ---------------------------------------------------------------------------
# geometry helpers


#: Vertical room reserved inside the plot, below its top edge, for the row of
#: gas-switch labels. They hang from the top so they stay clear of the curve,
#: which means the title band above must not reach into this space.
GAS_LABEL_BAND = 34.0


def _margins(opts: RenderOptions, *, stats_rows: int) -> Margins:
    # The title band holds a title line and a subtitle line; the gas labels sit
    # below it inside the plot. Undersizing this is what makes the subtitle and
    # the first gas label collide on dives that switch gas near the surface.
    top = opts.padding + (58.0 if opts.show_title else 14.0)
    right = opts.padding + 20.0
    left = opts.padding + (56.0 if opts.show_axes else 12.0)
    bottom = opts.padding + (34.0 if opts.show_axes else 10.0)
    if stats_rows:
        bottom += 74.0
    return Margins(top=top, right=right, bottom=bottom, left=left)


def _curve_points(dive: Dive, layout: Layout) -> list[tuple[float, float]]:
    return [(layout.x(s.time_s), layout.y(s.depth_m)) for s in dive.samples]


def _ceiling_runs(dive: Dive) -> list[list[Sample]]:
    """Contiguous runs of samples that have a non-zero ceiling."""
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
    return runs


# ---------------------------------------------------------------------------
# layers


def _draw_grid(canvas: Canvas, layout: Layout, theme: Theme) -> None:
    for depth in layout.y.ticks(layout.depth_step):
        y = layout.y(depth)
        canvas.line(
            layout.plot_left,
            y,
            layout.plot_right,
            y,
            stroke=theme.grid,
            stroke_width=1,
        )
    for t in layout.x.ticks(layout.time_step):
        x = layout.x(t)
        canvas.line(
            x,
            layout.plot_top,
            x,
            layout.plot_bottom,
            stroke=theme.grid,
            stroke_width=1,
        )


def _draw_ceiling(canvas: Canvas, dive: Dive, layout: Layout, theme: Theme) -> bool:
    """Shade the region shallower than the ceiling. Returns whether anything drew.

    The shaded band is the water the diver may *not* ascend into, so it hangs
    from the surface down to the ceiling — the curve staying clear of it is the
    thing a reader checks at a glance.
    """
    runs = _ceiling_runs(dive)
    if not runs:
        return False

    hatch = canvas.hatch("ds-ceiling", theme.ceiling, angle=45, spacing=7, width=1.4)

    for run in runs:
        # A stepped edge, not an interpolated one: a ceiling moves in discrete
        # 3 m stops, and a sloped line would misrepresent it as continuous.
        edge: list[tuple[float, float]] = []
        previous_y: float | None = None
        for sample in run:
            x = layout.x(sample.time_s)
            y = layout.y(sample.stop_depth_m or 0.0)
            if previous_y is not None and y != previous_y:
                edge.append((x, previous_y))
            edge.append((x, y))
            previous_y = y

        if not edge:
            continue

        top = layout.y(0.0)
        polygon = [(edge[0][0], top), *edge, (edge[-1][0], top)]
        canvas.path(
            points_to_path(polygon, close=True),
            fill=hatch,
            stroke="none",
        )
        canvas.path(
            points_to_path(edge),
            fill="none",
            stroke=theme.ceiling,
            stroke_width=2,
            stroke_linejoin="round",
        )
    return True


def _draw_curve(canvas: Canvas, dive: Dive, layout: Layout, theme: Theme) -> None:
    points = _curve_points(dive, layout)
    if len(points) < 2:
        return

    fill = canvas.linear_gradient(
        "ds-depth-fill",
        [(0.0, theme.curve_fill_top), (1.0, theme.curve_fill_bottom)],
    )
    surface_y = layout.y(0.0)
    area = [(points[0][0], surface_y), *points, (points[-1][0], surface_y)]
    canvas.path(points_to_path(area, close=True), fill=fill, stroke="none")
    canvas.path(
        points_to_path(points),
        fill="none",
        stroke=theme.curve,
        stroke_width=2.5,
        stroke_linejoin="round",
        stroke_linecap="round",
    )


def _draw_axes(canvas: Canvas, layout: Layout, theme: Theme) -> None:
    # Surface line — the reference the whole profile is read against, so it is
    # the one piece of chrome allowed to be more than a hairline.
    surface_y = layout.y(0.0)
    canvas.line(
        layout.plot_left,
        surface_y,
        layout.plot_right,
        surface_y,
        stroke=theme.axis,
        stroke_width=1.5,
    )

    ticks = layout.y.ticks(layout.depth_step)
    for index, depth in enumerate(ticks):
        if depth == 0:
            continue
        y = layout.y(depth)
        # The deepest gridline sits on the plot's bottom edge, where its label
        # would crowd the time axis directly beneath it. The gridline still
        # draws; only the number is dropped.
        if index == len(ticks) - 1 and layout.plot_bottom - y < 12:
            continue
        # Unit on the first labelled tick only — repeating "m" down the axis is
        # noise, and a floating unit label collides with the title band.
        label = f"{fmt(depth)} m" if index == 1 else fmt(depth)
        canvas.add(
            text(
                label,
                layout.plot_left - 10,
                y + 4,
                fill=theme.ink_muted,
                halo=theme.halo,
                size=theme.label_size,
                family=theme.font_family,
                anchor="end",
            )
        )

    for t in layout.x.ticks(layout.time_step):
        x = layout.x(t)
        canvas.add(
            text(
                format_duration(t),
                x,
                layout.plot_bottom + 22,
                fill=theme.ink_muted,
                halo=theme.halo,
                size=theme.label_size,
                family=theme.font_family,
                anchor="middle",
            )
        )


def _draw_gas_switches(
    canvas: Canvas, dive: Dive, layout: Layout, theme: Theme
) -> bool:
    """Mark each gas change on the curve and label it with the mix name.

    The label is not decoration: the accent colour alone does not say *which*
    mix, and on the light theme it sits below the 3:1 contrast bar — the text is
    what carries identity. Do not drop it to reduce clutter.
    """
    if not dive.gas_switches:
        return False

    depth_at = {s.time_s: s.depth_m for s in dive.samples}
    times = sorted(depth_at)

    def depth_for(t: float) -> float:
        if not times:
            return 0.0
        best = min(times, key=lambda candidate: abs(candidate - t))
        return depth_at[best]

    # Alternate label sides so two switches close in time do not overprint.
    last_x = float("-inf")
    flip = False

    for switch in dive.gas_switches:
        x = layout.x(switch.time_s)
        y = layout.y(depth_for(switch.time_s))

        if x - last_x < 90:
            flip = not flip
        else:
            flip = False
        last_x = x

        canvas.line(
            x,
            layout.plot_top,
            x,
            y,
            stroke=theme.accent,
            stroke_width=1.5,
            stroke_dasharray="4 4",
            opacity=0.75,
        )
        # A ring in the halo colour keeps the dot readable where it lands on the
        # curve stroke it sits on top of.
        canvas.circle(x, y, 5.5, fill="none", stroke=theme.halo, stroke_width=3)
        canvas.circle(x, y, 5.5, fill=theme.accent, stroke="none")

        label_y = layout.plot_top + (GAS_LABEL_BAND - 12 if flip else 12)
        canvas.add(
            text(
                switch.gas.name,
                x + 8,
                label_y,
                fill=theme.ink,
                halo=theme.halo,
                size=theme.label_size,
                family=theme.font_family,
                weight="600",
            )
        )
    return True


def _draw_title(canvas: Canvas, dive: Dive, opts: RenderOptions, theme: Theme) -> None:
    x = opts.padding + 4
    canvas.add(
        text(
            dive.title,
            x,
            opts.padding + 24,
            fill=theme.ink,
            halo=theme.halo,
            size=theme.title_size,
            family=theme.font_family,
            weight="700",
            halo_width=4,
        )
    )

    bits: list[str] = []
    if dive.when is not None:
        bits.append(dive.when.strftime("%d %b %Y · %H:%M"))
    if dive.computer:
        bits.append(dive.computer)
    if dive.deco_model:
        bits.append(dive.deco_model)
    if bits:
        canvas.add(
            text(
                "   ·   ".join(bits),
                x,
                opts.padding + 42,
                fill=theme.ink_secondary,
                halo=theme.halo,
                size=theme.label_size,
                family=theme.font_family,
            )
        )


def _draw_legend(
    canvas: Canvas, legend: _Legend, layout: Layout, opts: RenderOptions, theme: Theme
) -> None:
    """Right-aligned swatch+label row in the title band.

    Present whenever two or more colour-bearing marks are on the plot; a lone
    depth curve is named by the title and needs no box.
    """
    if len(legend.entries) < 2:
        return

    y = opts.padding + 22
    x = layout.plot_right
    for label, color, kind in reversed(legend.entries):
        width = 6.6 * len(label)
        canvas.add(
            text(
                label,
                x,
                y + 4,
                fill=theme.ink_secondary,
                halo=theme.halo,
                size=theme.label_size,
                family=theme.font_family,
                anchor="end",
            )
        )
        swatch_x = x - width - 16
        if kind == "dot":
            canvas.circle(swatch_x + 6, y, 4.5, fill=color, stroke="none")
        elif kind == "area":
            canvas.rect(swatch_x, y - 5, 14, 10, fill=color, stroke="none", rx=2)
        else:
            canvas.line(
                swatch_x,
                y,
                swatch_x + 14,
                y,
                stroke=color,
                stroke_width=2.5,
                stroke_linecap="round",
            )
        x = swatch_x - 18


def _collect_stats(dive: Dive, *, show_deco: bool = True) -> list[_Stat]:
    stats: list[_Stat] = [
        _Stat("Max depth", f"{dive.computed_max_depth_m:.1f}", "m"),
    ]
    if (mean := dive.computed_mean_depth_m) is not None:
        stats.append(_Stat("Avg depth", f"{mean:.1f}", "m"))
    stats.append(_Stat("Runtime", format_duration(dive.computed_duration_s), ""))

    if (span := dive.temperature_range_c) is not None:
        stats.append(_Stat("Temp", f"{span[0]:.0f}", "°C"))
    elif dive.water_temp_c is not None:
        stats.append(_Stat("Temp", f"{dive.water_temp_c:.0f}", "°C"))

    if show_deco and (deco := dive.deco_time_s()) is not None:
        stats.append(_Stat("Deco", format_duration(deco), ""))

    if dive.gas_switches:
        names: list[str] = []
        for switch in dive.gas_switches:
            if switch.gas.name not in names:
                names.append(switch.gas.name)
        stats.append(_Stat("Gas", " → ".join(names), ""))

    if (gf := dive.gradient_factors) is not None:
        stats.append(_Stat("GF", f"{gf[0]}/{gf[1]}", ""))
    if (used := dive.gas_used_l) is not None:
        stats.append(_Stat("Gas used", f"{used:.0f}", "L"))
    if dive.sac_l_min is not None:
        stats.append(_Stat("SAC", f"{dive.sac_l_min:.1f}", "L/min"))
    if dive.cns is not None:
        stats.append(_Stat("CNS", f"{dive.cns * 100:.0f}", "%"))

    return stats


def _draw_stats(
    canvas: Canvas,
    stats: list[_Stat],
    layout: Layout,
    opts: RenderOptions,
    theme: Theme,
) -> None:
    if not stats:
        return

    baseline = opts.height - opts.padding - 30
    x = layout.plot_left
    available = layout.plot_width
    slot = available / len(stats)

    for index, stat in enumerate(stats):
        left = x + index * slot
        canvas.add(
            text(
                stat.label.upper(),
                left,
                baseline - 20,
                fill=theme.ink_muted,
                halo=theme.halo,
                size=10,
                family=theme.font_family,
                weight="600",
                # Tracking makes an all-caps micro-label legible at 10px.
                letter_spacing="0.08em",
            )
        )
        canvas.add(
            text(
                stat.value,
                left,
                baseline + 6,
                fill=theme.ink,
                halo=theme.halo,
                size=22,
                family=theme.font_family,
                weight="700",
                halo_width=4,
            )
        )
        if stat.unit:
            offset = 12.6 * len(stat.value) + 5
            canvas.add(
                text(
                    stat.unit,
                    left + offset,
                    baseline + 6,
                    fill=theme.ink_secondary,
                    halo=theme.halo,
                    size=12,
                    family=theme.font_family,
                )
            )


# ---------------------------------------------------------------------------
# entry point


def render_svg(
    dive: Dive, options: RenderOptions | None = None, **overrides: object
) -> str:
    """Render ``dive`` to an SVG document string with a transparent background."""
    opts = options or RenderOptions()
    if overrides:
        from dataclasses import replace

        opts = replace(opts, **overrides)  # type: ignore[arg-type]
    theme = opts.resolved_theme()

    if not dive.samples:
        raise ValueError(
            "this dive has no depth samples, so there is no profile to draw"
        )

    stats = _collect_stats(dive, show_deco=opts.show_deco) if opts.show_stats else []
    margins = _margins(opts, stats_rows=1 if stats else 0)
    layout = Layout.build(
        width=opts.width,
        height=opts.height,
        duration_s=dive.computed_duration_s,
        max_depth_m=dive.computed_max_depth_m,
        margins=margins,
    )

    canvas = Canvas(opts.width, opts.height)
    legend = _Legend()

    if opts.show_grid:
        _draw_grid(canvas, layout, theme)

    drew_ceiling = False
    if opts.show_ceiling:
        drew_ceiling = _draw_ceiling(canvas, dive, layout, theme)

    _draw_curve(canvas, dive, layout, theme)
    legend.entries.append(("Depth", theme.curve, "line"))
    if drew_ceiling:
        legend.entries.append(("Deco ceiling", theme.ceiling, "area"))

    if opts.show_axes:
        _draw_axes(canvas, layout, theme)

    if opts.show_gas and _draw_gas_switches(canvas, dive, layout, theme):
        legend.entries.append(("Gas switch", theme.accent, "dot"))

    if opts.show_title:
        _draw_title(canvas, dive, opts, theme)
    if opts.show_legend:
        _draw_legend(canvas, legend, layout, opts, theme)
    if stats:
        _draw_stats(canvas, stats, layout, opts, theme)

    return canvas.to_svg(
        title=dive.title,
        description=(
            f"Dive profile: max {dive.computed_max_depth_m:.1f} m, "
            f"runtime {format_duration(dive.computed_duration_s)}."
        ),
    )
