"""Render a Subsurface or UDDF dive log into a single transparent image.

EXPERIMENTAL — this renders logged data for illustration. It is not a dive
planning or analysis tool, and nothing it draws should be used to make decisions
in the water.

    >>> from diveslate import parse_file, render_svg
    >>> log = parse_file("divetest.ssrf")
    >>> svg = render_svg(log.only())
"""

from diveslate.core.models import (
    AIR,
    Cylinder,
    DecoSpan,
    Dive,
    DiveLog,
    GasMix,
    GasSwitch,
    Sample,
)
from diveslate.parsers import ParseError, parse_file, parse_text, sniff

__version__ = "0.1.0"

__all__ = [
    "AIR",
    "Cylinder",
    "DecoSpan",
    "Dive",
    "DiveLog",
    "GasMix",
    "GasSwitch",
    "ParseError",
    "Sample",
    "__version__",
    "parse_file",
    "parse_text",
    "sniff",
]


def __getattr__(name: str) -> object:
    # Rendering pulls in the render package; keep `import diveslate` cheap for
    # callers that only want to parse.
    if name in ("render_svg", "render_png", "Theme"):
        from diveslate import render

        return getattr(render, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
