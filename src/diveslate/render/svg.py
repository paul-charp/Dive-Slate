"""A very small SVG writer.

Deliberately not a general-purpose SVG library: it covers the handful of shapes
the slate needs, escapes everything it emits, and has no dependencies. That last
point is the reason it exists — the whole vector path stays pure-Python, so
``pip install diveslate`` needs no native toolchain.

Two details matter beyond the obvious:

* :func:`text` paints every label **twice** — a wide stroke in the halo colour
  underneath, the fill on top. On a transparent background you cannot know what
  is behind the label, and a single-painted label vanishes the moment it lands
  on a matching tone. The alternative, ``paint-order: stroke``, is one property
  instead of two elements but is unevenly supported by rasterisers, so the
  double paint wins on portability.
* Coordinates are rounded to :data:`PRECISION` decimals. A 2000-sample profile
  writes ~4000 numbers; full float repr roughly triples the file size for
  sub-pixel differences nobody can see.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from xml.sax.saxutils import escape, quoteattr

__all__ = [
    "PRECISION",
    "Canvas",
    "fmt",
    "points_to_path",
    "text",
]

PRECISION = 2


def fmt(value: float) -> str:
    """Format a coordinate: rounded, and without a pointless trailing ``.0``."""
    rounded = round(float(value), PRECISION)
    if rounded == int(rounded):
        return str(int(rounded))
    return f"{rounded:g}"


def _attrs(attrs: dict[str, object]) -> str:
    parts: list[str] = []
    for key, value in attrs.items():
        if value is None:
            continue
        name = key.rstrip("_").replace("_", "-")
        rendered = fmt(value) if isinstance(value, (int, float)) else str(value)
        parts.append(f"{name}={quoteattr(rendered)}")
    return (" " + " ".join(parts)) if parts else ""


def points_to_path(
    points: Sequence[tuple[float, float]], *, close: bool = False
) -> str:
    """Build an ``M/L`` path string from points."""
    if not points:
        return ""
    head = f"M {fmt(points[0][0])} {fmt(points[0][1])}"
    body = " ".join(f"L {fmt(x)} {fmt(y)}" for x, y in points[1:])
    path = f"{head} {body}".strip()
    return f"{path} Z" if close else path


def text(
    content: str,
    x: float,
    y: float,
    *,
    fill: str,
    halo: str | None = None,
    size: float = 13.0,
    family: str | None = None,
    anchor: str = "start",
    weight: str | None = None,
    baseline: str | None = None,
    opacity: float | None = None,
    halo_width: float = 3.0,
    **extra: object,
) -> str:
    """A text label, painted with a halo underneath unless ``halo`` is None.

    Extra keyword arguments become SVG attributes with ``_`` mapped to ``-``,
    so ``letter_spacing="0.08em"`` emits ``letter-spacing="0.08em"``.
    """
    if not content:
        return ""

    shared: dict[str, object] = {
        "x": x,
        "y": y,
        "font-family": family,
        "font-size": size,
        "text-anchor": anchor,
        "font-weight": weight,
        "dominant-baseline": baseline,
        "opacity": opacity,
        **extra,
    }
    escaped = escape(content)

    parts: list[str] = []
    if halo:
        parts.append(
            f"<text{_attrs({**shared, 'fill': 'none', 'stroke': halo, 'stroke-width': halo_width, 'stroke-linejoin': 'round'})}>{escaped}</text>"
        )
    parts.append(f"<text{_attrs({**shared, 'fill': fill})}>{escaped}</text>")
    return "".join(parts)


class Canvas:
    """Accumulates SVG elements and serialises the document."""

    __slots__ = ("_body", "_defs", "height", "width")

    def __init__(self, width: float, height: float) -> None:
        self.width = width
        self.height = height
        self._defs: list[str] = []
        self._body: list[str] = []

    # ---- building blocks --------------------------------------------------

    def add(self, markup: str) -> None:
        if markup:
            self._body.append(markup)

    def defs(self, markup: str) -> None:
        if markup:
            self._defs.append(markup)

    def linear_gradient(
        self,
        gradient_id: str,
        stops: Iterable[tuple[float, str]],
        *,
        vertical: bool = True,
    ) -> str:
        """Define a linear gradient and return its ``url(#id)`` reference."""
        coords = (
            'x1="0" y1="0" x2="0" y2="1"' if vertical else 'x1="0" y1="0" x2="1" y2="0"'
        )
        body = "".join(
            f'<stop offset="{fmt(offset * 100)}%" stop-color="{color}"/>'
            for offset, color in stops
        )
        self.defs(
            f'<linearGradient id="{gradient_id}" {coords}>{body}</linearGradient>'
        )
        return f"url(#{gradient_id})"

    def hatch(
        self,
        pattern_id: str,
        color: str,
        *,
        angle: float = 45.0,
        spacing: float = 7.0,
        width: float = 1.6,
        background: str | None = None,
    ) -> str:
        """Define a diagonal hatch fill and return its ``url(#id)`` reference.

        Used for the deco ceiling. A solid wash there would stack on top of the
        depth-area fill occupying the same band and turn muddy; a hatch stays
        legible over another fill and carries the "keep out" reading that a flat
        tint does not. It also survives greyscale printing and forced-colors.
        """
        back = (
            f'<rect width="{fmt(spacing)}" height="{fmt(spacing)}" fill="{background}"/>'
            if background
            else ""
        )
        self.defs(
            f'<pattern id="{pattern_id}" patternUnits="userSpaceOnUse" '
            f'width="{fmt(spacing)}" height="{fmt(spacing)}" '
            f'patternTransform="rotate({fmt(angle)})">'
            f"{back}"
            f'<line x1="0" y1="0" x2="0" y2="{fmt(spacing)}" '
            f'stroke="{color}" stroke-width="{fmt(width)}"/>'
            f"</pattern>"
        )
        return f"url(#{pattern_id})"

    def clip_rect(self, clip_id: str, x: float, y: float, w: float, h: float) -> str:
        self.defs(
            f'<clipPath id="{clip_id}"><rect{_attrs({"x": x, "y": y, "width": w, "height": h})}/></clipPath>'
        )
        return f"url(#{clip_id})"

    # ---- shapes -----------------------------------------------------------

    def rect(self, x: float, y: float, w: float, h: float, **attrs: object) -> None:
        self.add(f"<rect{_attrs({'x': x, 'y': y, 'width': w, 'height': h, **attrs})}/>")

    def line(self, x1: float, y1: float, x2: float, y2: float, **attrs: object) -> None:
        self.add(f"<line{_attrs({'x1': x1, 'y1': y1, 'x2': x2, 'y2': y2, **attrs})}/>")

    def path(self, d: str, **attrs: object) -> None:
        if d:
            self.add(f"<path{_attrs({'d': d, **attrs})}/>")

    def circle(self, cx: float, cy: float, r: float, **attrs: object) -> None:
        self.add(f"<circle{_attrs({'cx': cx, 'cy': cy, 'r': r, **attrs})}/>")

    def group(self, markup: str, **attrs: object) -> None:
        if markup:
            self.add(f"<g{_attrs(attrs)}>{markup}</g>")

    def text(self, *args: object, **kwargs: object) -> None:
        self.add(text(*args, **kwargs))  # type: ignore[arg-type]

    # ---- output -----------------------------------------------------------

    def to_svg(
        self, *, title: str | None = None, description: str | None = None
    ) -> str:
        """Serialise the document.

        No background rectangle is ever emitted — transparency is the whole
        point of this renderer, and a `fill` on the root would defeat it.
        """
        head = (
            f'<svg xmlns="http://www.w3.org/2000/svg" '
            f'width="{fmt(self.width)}" height="{fmt(self.height)}" '
            f'viewBox="0 0 {fmt(self.width)} {fmt(self.height)}" '
            f'fill="none">'
        )
        meta = ""
        if title:
            meta += f"<title>{escape(title)}</title>"
        if description:
            meta += f"<desc>{escape(description)}</desc>"
        defs = f"<defs>{''.join(self._defs)}</defs>" if self._defs else ""
        return f"{head}{meta}{defs}{''.join(self._body)}</svg>"
