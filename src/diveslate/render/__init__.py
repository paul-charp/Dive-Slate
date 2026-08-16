"""Rendering: SVG generation and optional PNG rasterisation."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from diveslate.core.models import Dive
from diveslate.render.overlay import (
    CANVAS_SIZES,
    OverlayOptions,
    render_overlay,
    render_overlay_canvas,
)
from diveslate.render.profile import RenderOptions, render_svg
from diveslate.render.raster import (
    RasterError,
    available_backends,
    svg_to_png,
    write_png,
)
from diveslate.render.theme import LIGHT, SLATE, THEMES, Theme, get_theme

__all__ = [
    "CANVAS_SIZES",
    "LIGHT",
    "SLATE",
    "THEMES",
    "OverlayOptions",
    "RasterError",
    "RenderOptions",
    "Theme",
    "available_backends",
    "get_theme",
    "render_overlay",
    "render_overlay_canvas",
    "render_overlay_png",
    "render_png",
    "render_svg",
    "svg_to_png",
    "write_png",
]


def render_png(
    dive: Dive,
    path: str | Path,
    *,
    options: RenderOptions | None = None,
    backend: str | None = None,
    **overrides: object,
) -> Path:
    """Render ``dive`` straight to a transparent PNG at ``path``.

    The raster size follows the SVG's own pixel dimensions, so ``width=3200``
    renders at 3200px rather than upscaling a smaller bitmap.
    """
    opts = options or RenderOptions()
    if overrides:
        from dataclasses import replace

        opts = replace(opts, **overrides)  # type: ignore[arg-type]

    svg = render_svg(dive, opts)
    return write_png(
        svg,
        path,
        width=int(opts.width),
        height=int(opts.height),
        backend=backend,
    )


def render_overlay_png(
    dive: Dive,
    path: str | Path,
    *,
    canvas: str | None = None,
    position: str = "bottom-left",
    options: OverlayOptions | None = None,
    backend: str | None = None,
    **overrides: Any,
) -> Path:
    """Render the compact overlay slate to a transparent PNG at ``path``.

    With ``canvas`` (``square``/``portrait``/``story``/``landscape``) the slate is
    placed on a full Instagram frame at ``position``; without it you get the
    tight-cropped slate to position yourself in an editor.
    """
    if canvas is not None:
        svg = render_overlay_canvas(
            dive,
            canvas=canvas,
            position=position,  # type: ignore[arg-type]
            options=options,
            **overrides,
        )
    else:
        svg = render_overlay(dive, options, **overrides)

    width, height = _svg_dimensions(svg)
    return write_png(svg, path, width=width, height=height, backend=backend)


def _svg_dimensions(svg: str) -> tuple[int, int]:
    def attr(name: str) -> int:
        marker = f'{name}="'
        start = svg.index(marker) + len(marker)
        return round(float(svg[start : svg.index('"', start)]))

    return attr("width"), attr("height")
