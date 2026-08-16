"""Turn the SVG into a PNG with its alpha channel intact.

Rasterisation is the one part of this library that cannot be done in pure
Python, so it is optional and pluggable. Backends are tried in order and the
first one that actually produces bytes wins; if none does, the error names the
extras to install rather than surfacing an ``ImportError`` — or worse an
``OSError`` about a missing shared library — from a transitive dependency.

The alpha channel is the whole point, so each backend is configured for a fully
transparent background explicitly. Several rasterisers default to compositing
onto opaque white, which produces a file that *looks* right in a viewer with a
white page and is useless the moment it is layered over anything.
"""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

__all__ = ["RasterError", "available_backends", "svg_to_png"]


class RasterError(RuntimeError):
    """Raised when no rasteriser is available, or one fails."""


def _via_cairosvg(svg: str, width: int | None, height: int | None) -> bytes:
    import cairosvg  # type: ignore[import-untyped]

    return bytes(
        cairosvg.svg2png(
            bytestring=svg.encode("utf-8"),
            output_width=width,
            output_height=height,
            # Explicitly transparent; cairosvg's default is already None but
            # states it here so a future default change cannot silently opaque
            # every render.
            background_color=None,
        )
    )


def _via_resvg(svg: str, width: int | None, height: int | None) -> bytes:
    import resvg_py

    data = resvg_py.svg_to_bytes(svg_string=svg, width=width, height=height)
    return bytes(data)


#: Backend name → callable, in preference order.
#:
#: resvg leads because it is the only one of the three that ships self-contained
#: wheels *and* renders SVG text. cairosvg renders text well but needs a system
#: cairo, which a stock Windows box does not have.
#:
#: skia-python is deliberately **absent** despite being installable and fast.
#: Its ``SVGDOM`` exposes no font-manager hook, so it renders every ``<text>``
#: element as nothing at all — the output is a clean-looking chart silently
#: missing its title, axis numbers, gas labels and stats. A backend that fails
#: loudly is fine; one that quietly drops half the content is not, and it must
#: never sit in an automatic fallback chain.
_BACKENDS: dict[str, Callable[[str, int | None, int | None], bytes]] = {
    "resvg": _via_resvg,
    "cairosvg": _via_cairosvg,
}

_EXTRAS = {"resvg": "diveslate[png]", "cairosvg": "diveslate[cairo]"}


def available_backends() -> list[str]:
    """Names of rasteriser backends that actually work here.

    Importability is not enough: ``cairosvg`` imports cleanly and then raises
    ``OSError`` at first use when the cairo shared library is missing, which is
    the default state of a Windows machine. Each candidate is therefore proved
    on a trivial document rather than trusted.
    """
    probe = (
        '<svg xmlns="http://www.w3.org/2000/svg" width="4" height="4">'
        '<rect width="4" height="4" fill="#000"/></svg>'
    )
    found: list[str] = []
    for name, backend in _BACKENDS.items():
        try:
            backend(probe, 4, 4)
        except Exception:  # noqa: BLE001,S112 - any failure means unusable here
            continue
        found.append(name)
    return found


def svg_to_png(
    svg: str,
    *,
    width: int | None = None,
    height: int | None = None,
    backend: str | None = None,
) -> bytes:
    """Rasterise ``svg`` to PNG bytes, preserving transparency."""
    if backend is not None:
        if backend not in _BACKENDS:
            known = ", ".join(_BACKENDS)
            raise RasterError(f"unknown backend {backend!r}; available: {known}")
        candidates = [backend]
    else:
        candidates = list(_BACKENDS)

    failures: list[str] = []
    for name in candidates:
        try:
            return _BACKENDS[name](svg, width, height)
        except ImportError:
            failures.append(f"{name}: not installed ({_EXTRAS[name]})")
        except RasterError:
            raise
        except Exception as exc:  # noqa: BLE001 - report and try the next backend
            failures.append(f"{name}: {type(exc).__name__}: {exc}")

    detail = "\n  ".join(failures) or "no backends configured"
    raise RasterError(
        "could not rasterise to PNG — no working backend.\n  "
        + detail
        + "\n\nInstall one:  pip install 'diveslate[png]'   (or 'diveslate[cairo]')"
        + "\nSVG output needs no extra dependency: render to a .svg file instead."
    )


def write_png(
    svg: str,
    path: str | Path,
    *,
    width: int | None = None,
    height: int | None = None,
    backend: str | None = None,
) -> Path:
    """Rasterise and write to ``path``."""
    path = Path(path)
    path.write_bytes(svg_to_png(svg, width=width, height=height, backend=backend))
    return path
