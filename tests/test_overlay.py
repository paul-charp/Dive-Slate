"""The compact overlay slate."""

from __future__ import annotations

from pathlib import Path
from xml.etree import ElementTree as ET

import pytest

from diveslate.core.models import Dive, Sample
from diveslate.render.overlay import (
    CANVAS_SIZES,
    OverlayOptions,
    _envelope,
    render_overlay,
    render_overlay_canvas,
)

SVG_NS = "{http://www.w3.org/2000/svg}"


def _texts(svg: str) -> list[str]:
    root = ET.fromstring(svg)
    return [el.text or "" for el in root.iter(f"{SVG_NS}text")]


class TestHeading:
    def test_site_only_by_default(self, ssrf_dive: Dive) -> None:
        """Date and dive number are noise on a post; the site name is the caption."""
        texts = _texts(render_overlay(ssrf_dive))
        assert "TEST WRECK" in texts
        assert not any("2026" in s for s in texts)
        assert not any("#42" in s for s in texts)

    def test_date_can_be_added(self, ssrf_dive: Dive) -> None:
        texts = _texts(render_overlay(ssrf_dive, show_date=True))
        assert any("2026" in s for s in texts)

    def test_date_line_carries_no_dive_number(self, ssrf_dive: Dive) -> None:
        texts = _texts(render_overlay(ssrf_dive, show_date=True))
        assert not any("#42" in s for s in texts)


class TestSlate:
    def test_is_well_formed(self, ssrf_dive: Dive) -> None:
        ET.fromstring(render_overlay(ssrf_dive))

    def test_is_compact_and_wider_than_tall(self, ssrf_dive: Dive) -> None:
        root = ET.fromstring(render_overlay(ssrf_dive))
        width = float(root.get("width") or 0)
        height = float(root.get("height") or 0)
        assert width == 1080
        assert height < width  # a badge, not a chart

    def test_height_scales_with_width(self, ssrf_dive: Dive) -> None:
        small = ET.fromstring(render_overlay(ssrf_dive, width=540))
        large = ET.fromstring(render_overlay(ssrf_dive, width=1080))
        ratio = float(large.get("height") or 0) / float(small.get("height") or 1)
        assert ratio == pytest.approx(2.0, abs=0.02)

    def test_headline_numbers_are_rounded_up(self, ssrf_dive: Dive) -> None:
        """Depth to the next whole metre, durations to the next whole minute."""
        texts = _texts(render_overlay(ssrf_dive))
        assert "38" in texts  # 38.0 m max depth
        assert "30" in texts  # 1800 s runtime
        assert "38.0" not in texts
        assert "30:00" not in texts

    def test_no_axis_chrome(self, ssrf_dive: Dive) -> None:
        """Axis ticks and a legend are unreadable at badge size, so they are absent."""
        texts = _texts(render_overlay(ssrf_dive))
        assert "MAX DEPTH" in texts
        assert "Deco ceiling" not in texts  # legend entry from the chart renderer
        assert "0:00" not in texts  # time-axis tick

    def test_dive_without_samples_is_rejected(self) -> None:
        with pytest.raises(ValueError, match="no depth samples"):
            render_overlay(Dive())


class TestScrim:
    def test_scrim_on_by_default(self, ssrf_dive: Dive) -> None:
        root = ET.fromstring(render_overlay(ssrf_dive))
        rects = list(root.iter(f"{SVG_NS}rect"))
        assert rects and rects[0].get("rx") is not None

    def test_scrim_can_be_dropped(self, ssrf_dive: Dive) -> None:
        root = ET.fromstring(render_overlay(ssrf_dive, show_scrim=False))
        assert not list(root.iter(f"{SVG_NS}rect"))


class TestStats:
    def test_deco_dive_shows_deco_not_temp(self, ssrf_dive: Dive) -> None:
        """The third slot is the interesting fact: deco time on a deco dive."""
        texts = _texts(render_overlay(ssrf_dive))
        assert "DECO" in texts
        assert "TEMP" not in texts

    def test_non_deco_dive_falls_back_to_temp(self) -> None:
        dive = Dive(
            samples=(
                Sample(0, 0.0, temp_c=20.0),
                Sample(600, 18.0, temp_c=16.0),
                Sample(1200, 0.0, temp_c=18.0),
            )
        )
        texts = _texts(render_overlay(dive))
        assert "TEMP" in texts
        assert "DECO" not in texts

    def test_explicit_stat_selection(self, ssrf_dive: Dive) -> None:
        texts = _texts(render_overlay(ssrf_dive, stats=("depth", "avg")))
        assert "AVG DEPTH" in texts
        assert "RUNTIME" not in texts

    def test_unavailable_stat_is_skipped_not_fatal(self) -> None:
        dive = Dive(samples=(Sample(0, 0.0), Sample(60, 10.0)))
        texts = _texts(render_overlay(dive, stats=("depth", "sac")))
        assert "MAX DEPTH" in texts
        assert "SAC" not in texts

    def test_unknown_stat_raises(self, ssrf_dive: Dive) -> None:
        with pytest.raises(LookupError, match="unknown stat"):
            render_overlay(ssrf_dive, stats=("vibes",))


class TestGas:
    def test_switch_markers_off_by_default(self, ssrf_dive: Dive) -> None:
        root = ET.fromstring(render_overlay(ssrf_dive))
        assert not list(root.iter(f"{SVG_NS}circle"))

    def test_switch_markers_can_be_enabled(self, ssrf_dive: Dive) -> None:
        svg = render_overlay(ssrf_dive, show_gas=True)
        assert "EAN50" in _texts(svg)
        assert list(ET.fromstring(svg).iter(f"{SVG_NS}circle"))


class TestCeiling:
    def test_ceiling_drawn_for_deco_dive(self, ssrf_dive: Dive) -> None:
        assert "ds-overlay-ceiling" in render_overlay(ssrf_dive)

    def test_ceiling_can_be_dropped(self, ssrf_dive: Dive) -> None:
        assert "ds-overlay-ceiling" not in render_overlay(ssrf_dive, show_ceiling=False)


class TestEnvelope:
    def test_short_series_is_untouched(self) -> None:
        points = [(float(i), float(i)) for i in range(10)]
        assert _envelope(points, 100) == points

    def test_long_series_is_reduced(self) -> None:
        points = [(i / 10.0, float(i % 7)) for i in range(4000)]
        assert len(_envelope(points, 200)) < len(points)

    def test_extremes_survive_reduction(self) -> None:
        """The deepest point must not be decimated away — it is the headline."""
        points = [(i / 20.0, 10.0) for i in range(4000)]
        points[1500] = (75.0, 99.0)
        reduced = _envelope(points, 200)
        assert max(y for _, y in reduced) == 99.0


class TestCanvasPlacement:
    @pytest.mark.parametrize("name", sorted(CANVAS_SIZES))
    def test_canvas_dimensions(self, ssrf_dive: Dive, name: str) -> None:
        root = ET.fromstring(render_overlay_canvas(ssrf_dive, canvas=name))
        expected = CANVAS_SIZES[name]
        assert (int(root.get("width") or 0), int(root.get("height") or 0)) == expected

    def test_position_changes_the_transform(self, ssrf_dive: Dive) -> None:
        top = render_overlay_canvas(ssrf_dive, position="top-left")
        bottom = render_overlay_canvas(ssrf_dive, position="bottom-left")
        assert top != bottom

    def test_slate_fits_inside_the_canvas(self, ssrf_dive: Dive) -> None:
        svg = render_overlay_canvas(ssrf_dive, canvas="story", position="bottom-right")
        root = ET.fromstring(svg)
        group = next(iter(root.iter(f"{SVG_NS}g")))
        transform = group.get("transform") or ""
        x, y = (float(v) for v in transform[len("translate(") : -1].split())
        assert x >= 0 and y >= 0

    def test_unknown_canvas_raises(self, ssrf_dive: Dive) -> None:
        with pytest.raises(LookupError, match="unknown canvas"):
            render_overlay_canvas(ssrf_dive, canvas="billboard")

    def test_still_transparent(self, ssrf_dive: Dive) -> None:
        """No canvas-sized backdrop — only the slate's own scrim."""
        root = ET.fromstring(render_overlay_canvas(ssrf_dive, canvas="square"))
        full = [el for el in root.iter(f"{SVG_NS}rect") if el.get("width") == "1080"]
        assert full == []


class TestOverlayCli:
    def test_writes_svg(self, ssrf_path: Path, tmp_path: Path) -> None:
        from diveslate.cli import main

        out = tmp_path / "slate.svg"
        assert main(["overlay", str(ssrf_path), "-o", str(out)]) == 0
        ET.fromstring(out.read_text(encoding="utf-8"))

    def test_canvas_flag(self, ssrf_path: Path, tmp_path: Path) -> None:
        from diveslate.cli import main

        out = tmp_path / "story.svg"
        main(["overlay", str(ssrf_path), "-o", str(out), "--canvas", "story"])
        root = ET.fromstring(out.read_text(encoding="utf-8"))
        assert root.get("height") == "1920"

    def test_stats_flag(self, ssrf_path: Path, tmp_path: Path) -> None:
        from diveslate.cli import main

        out = tmp_path / "s.svg"
        main(["overlay", str(ssrf_path), "-o", str(out), "--stats", "depth,temp"])
        assert "AVG DEPTH" not in out.read_text(encoding="utf-8")


class TestOptions:
    def test_overrides_do_not_mutate_the_original(self, ssrf_dive: Dive) -> None:
        options = OverlayOptions()
        render_overlay(ssrf_dive, options, width=400)
        assert options.width == 1080.0


class TestGradientFactors:
    def test_gf_appears_once_there_is_room(self, ssrf_dive: Dive) -> None:
        from diveslate.render.overlay import _auto_stats

        assert "GF" not in [label for label, _, _ in _auto_stats(ssrf_dive, 3)]
        assert "GF" in [label for label, _, _ in _auto_stats(ssrf_dive, 4)]

    def test_gf_rendered_as_low_over_high(self, ssrf_dive: Dive) -> None:
        texts = _texts(render_overlay(ssrf_dive, stats=("gf",)))
        assert "40/85" in texts
