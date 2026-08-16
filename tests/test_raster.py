"""PNG rasterisation.

Skipped wholesale when no backend is installed — SVG output is the supported
dependency-free path, so a bare checkout must not fail its test suite for
lacking an optional extra.
"""

from __future__ import annotations

import struct
from pathlib import Path

import pytest

from diveslate.core.models import Dive
from diveslate.render import render_svg
from diveslate.render.raster import RasterError, available_backends, svg_to_png

BACKENDS = available_backends()
requires_backend = pytest.mark.skipif(
    not BACKENDS, reason="no PNG rasteriser installed"
)


def _png_header(data: bytes) -> tuple[int, int, int]:
    """(width, height, colour_type) from the IHDR chunk."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    width, height = struct.unpack(">II", data[16:24])
    colour_type = data[25]
    return width, height, colour_type


class TestRasterisation:
    @requires_backend
    def test_produces_a_png(self, ssrf_dive: Dive) -> None:
        data = svg_to_png(render_svg(ssrf_dive), width=400, height=225)
        assert data[:8] == b"\x89PNG\r\n\x1a\n"

    @requires_backend
    def test_honours_requested_size(self, ssrf_dive: Dive) -> None:
        data = svg_to_png(render_svg(ssrf_dive), width=400, height=225)
        width, height, _ = _png_header(data)
        assert (width, height) == (400, 225)

    @requires_backend
    def test_output_has_an_alpha_channel(self, ssrf_dive: Dive) -> None:
        """Colour type 6 is RGBA. Type 2 (RGB) would mean transparency was lost."""
        data = svg_to_png(render_svg(ssrf_dive), width=200, height=120)
        _, _, colour_type = _png_header(data)
        assert colour_type == 6

    @requires_backend
    def test_render_png_writes_file(self, ssrf_dive: Dive, tmp_path: Path) -> None:
        from diveslate.render import render_png

        out = render_png(ssrf_dive, tmp_path / "p.png", width=320, height=180)
        assert out.exists() and out.stat().st_size > 0


class TestBackendSelection:
    def test_unknown_backend_is_rejected(self, ssrf_dive: Dive) -> None:
        with pytest.raises(RasterError, match="unknown backend"):
            svg_to_png(render_svg(ssrf_dive), backend="crayons")

    def test_skia_is_not_offered(self) -> None:
        """skia renders no SVG text, so it must never be a silent fallback."""
        assert "skia" not in BACKENDS

    def test_error_names_the_extra_to_install(self, ssrf_dive: Dive) -> None:
        from diveslate.render import raster

        original = raster._BACKENDS
        try:
            raster._BACKENDS = {}
            with pytest.raises(RasterError, match=r"diveslate\[png\]"):
                svg_to_png(render_svg(ssrf_dive))
        finally:
            raster._BACKENDS = original
