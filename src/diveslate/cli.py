"""``diveslate`` command line interface.

Built on :mod:`argparse` rather than a CLI framework so the command works from a
bare ``pip install diveslate`` with no dependencies at all. ``rich`` is used for
output when present and plain text otherwise.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from diveslate import __version__
from diveslate.core.models import Dive, DiveLog
from diveslate.core.units import format_duration
from diveslate.parsers import ParseError, parse_file
from diveslate.render.overlay import CANVAS_SIZES, STAT_KEYS
from diveslate.render.theme import THEMES

__all__ = ["main"]


def _echo(message: str = "") -> None:
    """Print, surviving consoles that cannot encode the character set.

    Windows terminals still default to a legacy code page, and dive logs are
    full of accented site names and the '·' separator. Replacing unencodable
    characters keeps the CLI usable instead of dying on a UnicodeEncodeError
    halfway through a report.
    """
    stream = sys.stdout
    encoding = stream.encoding or "utf-8"
    try:
        stream.write(message + "\n")
    except UnicodeEncodeError:
        stream.write(message.encode(encoding, "replace").decode(encoding) + "\n")


def _select_dive(log: DiveLog, index: int | None) -> Dive:
    if index is not None:
        try:
            return log.dives[index]
        except IndexError:
            raise SystemExit(
                f"dive index {index} is out of range: the log has {len(log)} dive(s)"
            ) from None
    if len(log) == 1:
        return log.dives[0]
    raise SystemExit(
        f"this log holds {len(log)} dives — choose one with --dive N (0-based), "
        f"or run `diveslate info` to list them"
    )


def _cmd_render(args: argparse.Namespace) -> int:
    from diveslate.render import RenderOptions, render_svg
    from diveslate.render.raster import RasterError, write_png

    log = parse_file(args.log)
    dive = _select_dive(log, args.dive)

    options = RenderOptions(
        width=args.width,
        height=args.height,
        theme=args.theme,
        show_title=not args.no_title,
        show_axes=not args.no_axes,
        show_grid=not args.no_grid,
        show_ceiling=not args.no_ceiling,
        show_gas=not args.no_gas,
        show_stats=not args.no_stats,
        show_legend=not args.no_legend,
        show_deco=not args.no_deco,
    )

    svg = render_svg(dive, options)
    out = Path(args.output)

    if out.suffix.lower() == ".svg":
        out.write_text(svg, encoding="utf-8")
    elif out.suffix.lower() == ".png":
        try:
            write_png(
                svg,
                out,
                width=int(args.width),
                height=int(args.height),
                backend=args.backend,
            )
        except RasterError as exc:
            _echo(str(exc))
            return 2
    else:
        _echo(f"unsupported output extension {out.suffix!r}; use .svg or .png")
        return 2

    _echo(f"wrote {out}  ({int(args.width)}x{int(args.height)}, transparent)")
    return 0


def _cmd_overlay(args: argparse.Namespace) -> int:
    from diveslate.render import OverlayOptions, render_overlay, render_overlay_canvas
    from diveslate.render.raster import RasterError, write_png

    log = parse_file(args.log)
    dive = _select_dive(log, args.dive)

    options = OverlayOptions(
        width=args.width,
        theme=args.theme,
        show_scrim=not args.no_scrim,
        show_site=not args.no_site,
        show_date=args.date,
        show_ceiling=not args.no_ceiling,
        show_gas=args.gas,
        show_deco=not args.no_deco,
        layout=args.layout,
        stats=tuple(args.stats.split(",")) if args.stats else None,
        max_stats=args.max_stats,
    )

    if args.canvas:
        svg = render_overlay_canvas(
            dive, canvas=args.canvas, position=args.position, options=options
        )
    else:
        svg = render_overlay(dive, options)

    out = Path(args.output)
    width, height = _svg_dimensions(svg)

    if out.suffix.lower() == ".svg":
        out.write_text(svg, encoding="utf-8")
    elif out.suffix.lower() == ".png":
        try:
            write_png(svg, out, width=width, height=height, backend=args.backend)
        except RasterError as exc:
            _echo(str(exc))
            return 2
    else:
        _echo(f"unsupported output extension {out.suffix!r}; use .svg or .png")
        return 2

    _echo(f"wrote {out}  ({width}x{height}, transparent)")
    return 0


def _svg_dimensions(svg: str) -> tuple[int, int]:
    def attr(name: str) -> int:
        marker = f'{name}="'
        start = svg.index(marker) + len(marker)
        return round(float(svg[start : svg.index('"', start)]))

    return attr("width"), attr("height")


def _cmd_info(args: argparse.Namespace) -> int:
    log = parse_file(args.log)
    _echo(f"{args.log}  —  {log.program or 'unknown program'}, {len(log)} dive(s)")

    for index, dive in enumerate(log.dives):
        _echo("")
        _echo(f"[{index}] {dive.title}")
        if dive.when is not None:
            _echo(f"      when      {dive.when:%Y-%m-%d %H:%M}")
        _echo(
            f"      profile   {len(dive.samples)} samples, "
            f"max {dive.computed_max_depth_m:.1f} m, "
            f"runtime {format_duration(dive.computed_duration_s)}"
        )
        if (mean := dive.computed_mean_depth_m) is not None:
            _echo(f"      avg depth {mean:.1f} m")
        if dive.computer:
            _echo(
                f"      computer  {dive.computer}"
                + (f"  ({dive.deco_model})" if dive.deco_model else "")
            )
        if dive.cylinders:
            for cylinder in dive.cylinders:
                used = cylinder.used_bar
                tail = f", used {used:.0f} bar" if used is not None else ""
                _echo(f"      cylinder  {cylinder.label} — {cylinder.gas.name}{tail}")
        if dive.gas_switches:
            switches = ", ".join(
                f"{s.gas.name} @ {format_duration(s.time_s)}" for s in dive.gas_switches
            )
            _echo(f"      gas       {switches}")
        if spans := dive.deco_spans():
            total = sum(s.duration_s for s in spans)
            _echo(
                f"      deco      {len(spans)} span(s), {format_duration(total)} total"
            )
        else:
            _echo("      deco      none")
        if (span := dive.temperature_range_c) is not None:
            _echo(f"      temp      {span[0]:.0f}–{span[1]:.0f} °C")
    return 0


def _cmd_backends(_: argparse.Namespace) -> int:
    from diveslate.render.raster import available_backends

    found = available_backends()
    if found:
        _echo("working PNG backends: " + ", ".join(found))
    else:
        _echo("no working PNG backend found.")
        _echo("install one with:  pip install 'diveslate[png]'")
        _echo("SVG output works without any extra dependency.")
    _echo("themes: " + ", ".join(sorted(THEMES)))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="diveslate",
        description=(
            "Render a Subsurface or UDDF dive log as a transparent dive profile "
            "image. Experimental — not for real dive planning."
        ),
    )
    parser.add_argument(
        "--version", action="version", version=f"diveslate {__version__}"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    render = subparsers.add_parser("render", help="render a dive profile image")
    render.add_argument("log", help="path to a .ssrf, .xml or .uddf dive log")
    render.add_argument(
        "-o", "--output", required=True, help="output path, .svg or .png"
    )
    render.add_argument(
        "--dive",
        type=int,
        default=None,
        help="which dive to render, 0-based (needed when the log holds several)",
    )
    render.add_argument(
        "--theme",
        default="slate",
        choices=sorted(THEMES),
        help="slate suits dark backgrounds, light suits pale ones (default: slate)",
    )
    render.add_argument(
        "--width", type=float, default=1600.0, help="pixels (default 1600)"
    )
    render.add_argument(
        "--height", type=float, default=900.0, help="pixels (default 900)"
    )
    render.add_argument(
        "--backend",
        default=None,
        help="force a PNG rasteriser instead of auto-detecting",
    )
    for flag, help_text in (
        ("title", "the dive title and subtitle"),
        ("axes", "depth and time axis labels"),
        ("grid", "the background grid"),
        ("ceiling", "the decompression ceiling"),
        ("gas", "gas switch markers"),
        ("stats", "the summary strip"),
        ("legend", "the legend"),
        ("deco", "deco time from the summary (it is derived, not logged)"),
    ):
        render.add_argument(
            f"--no-{flag}", action="store_true", help=f"omit {help_text}"
        )
    render.set_defaults(func=_cmd_render)

    overlay = subparsers.add_parser(
        "overlay",
        help="render a compact slate to sit over a photo or video",
        description=(
            "Renders a small badge — profile silhouette plus three big numbers — "
            "sized for Instagram. Without --canvas you get a tight-cropped slate "
            "to position yourself in an editor; with it, the slate is placed on a "
            "full Instagram frame ready to drop straight on."
        ),
    )
    overlay.add_argument("log", help="path to a .ssrf, .xml or .uddf dive log")
    overlay.add_argument("-o", "--output", required=True, help="output .svg or .png")
    overlay.add_argument("--dive", type=int, default=None, help="which dive, 0-based")
    overlay.add_argument(
        "--theme",
        default="slate",
        choices=sorted(THEMES),
        help="slate for dark footage, light for bright (default: slate)",
    )
    overlay.add_argument(
        "--width",
        type=float,
        default=1080.0,
        help="slate width in pixels, ignored with --canvas (default 1080)",
    )
    overlay.add_argument(
        "--canvas",
        default=None,
        choices=sorted(CANVAS_SIZES),
        help="place the slate on a full Instagram frame of this shape",
    )
    overlay.add_argument(
        "--position",
        default="bottom-left",
        choices=[
            "top-left",
            "top-right",
            "bottom-left",
            "bottom-right",
            "top-center",
            "bottom-center",
            "center",
        ],
        help="where on the canvas to place it (default: bottom-left)",
    )
    overlay.add_argument(
        "--stats",
        default=None,
        help=(
            "comma-separated values to show, e.g. depth,time,temp. "
            f"Available: {', '.join(STAT_KEYS)}. Default picks automatically."
        ),
    )
    overlay.add_argument(
        "--gas", action="store_true", help="mark and label gas switches on the curve"
    )
    overlay.add_argument("--backend", default=None, help="force a PNG rasteriser")
    overlay.add_argument(
        "--no-scrim",
        action="store_true",
        help="drop the backdrop panel (only safe over calm, even footage)",
    )
    overlay.add_argument("--no-site", action="store_true", help="omit the site name")
    overlay.add_argument(
        "--date", action="store_true", help="add the dive date under the site name"
    )
    overlay.add_argument(
        "--no-ceiling", action="store_true", help="omit the deco ceiling hatching"
    )
    overlay.add_argument(
        "--no-deco",
        action="store_true",
        help="keep deco time out of the summary (it is derived, not logged)",
    )
    overlay.add_argument(
        "--layout",
        default=None,
        choices=["wide", "tall"],
        help="wide badge or tall story card (default: tall for --canvas story)",
    )
    overlay.add_argument(
        "--max-stats",
        type=int,
        default=3,
        help="how many summary values to show (default 3)",
    )
    overlay.set_defaults(func=_cmd_overlay)

    info = subparsers.add_parser("info", help="summarise what a log contains")
    info.add_argument("log", help="path to a dive log")
    info.set_defaults(func=_cmd_info)

    backends = subparsers.add_parser(
        "backends", help="show working PNG rasterisers and themes"
    )
    backends.set_defaults(func=_cmd_backends)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        result = args.func(args)
        return int(result)
    except FileNotFoundError as exc:
        _echo(f"no such file: {exc.filename}")
        return 2
    except (ParseError, LookupError, ValueError) as exc:
        _echo(f"error: {exc}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
