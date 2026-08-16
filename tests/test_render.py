"""Rendering: valid SVG, transparency, and the layer toggles."""

from __future__ import annotations

from xml.etree import ElementTree as ET

import pytest

from diveslate.core.models import Dive, Sample
from diveslate.render import RenderOptions, render_svg
from diveslate.render.layout import Layout, Margins, Scale, nice_step
from diveslate.render.svg import Canvas, fmt, points_to_path
from diveslate.render.theme import LIGHT, SLATE, get_theme


class TestSvgDocument:
    def test_is_well_formed_xml(self, ssrf_dive: Dive) -> None:
        ET.fromstring(render_svg(ssrf_dive))

    def test_has_no_background_rect(self, ssrf_dive: Dive) -> None:
        """Transparency is the point; an opaque backdrop would defeat it."""
        root = ET.fromstring(render_svg(ssrf_dive))
        full_size = [
            el
            for el in root.iter("{http://www.w3.org/2000/svg}rect")
            if el.get("width") == "1600" and el.get("height") == "900"
        ]
        assert full_size == []

    def test_dimensions_follow_options(self, ssrf_dive: Dive) -> None:
        svg = render_svg(ssrf_dive, width=800, height=450)
        root = ET.fromstring(svg)
        assert root.get("width") == "800"
        assert root.get("height") == "450"

    def test_title_and_description_present(self, ssrf_dive: Dive) -> None:
        root = ET.fromstring(render_svg(ssrf_dive))
        assert root.find("{http://www.w3.org/2000/svg}title") is not None
        assert root.find("{http://www.w3.org/2000/svg}desc") is not None

    def test_dive_without_samples_is_rejected(self) -> None:
        with pytest.raises(ValueError, match="no depth samples"):
            render_svg(Dive())


class TestLayers:
    def test_stats_can_be_disabled(self, ssrf_dive: Dive) -> None:
        assert "MAX DEPTH" in render_svg(ssrf_dive)
        assert "MAX DEPTH" not in render_svg(ssrf_dive, show_stats=False)

    def test_gas_markers_can_be_disabled(self, ssrf_dive: Dive) -> None:
        # The mix name also appears in the stats strip, so count the on-plot
        # marker dots rather than searching for the text anywhere.
        assert render_svg(ssrf_dive).count("<circle") > 0
        assert render_svg(ssrf_dive, show_gas=False).count("<circle") == 0

    def test_ceiling_can_be_disabled(self, ssrf_dive: Dive) -> None:
        assert "ds-ceiling" in render_svg(ssrf_dive)
        assert "ds-ceiling" not in render_svg(ssrf_dive, show_ceiling=False)

    def test_title_can_be_disabled(self, ssrf_dive: Dive) -> None:
        """The drawn title goes, but the <title> metadata element stays.

        That element is what a screen reader announces for the image, so it is
        not part of the visual layer the flag controls.
        """
        svg = render_svg(ssrf_dive, show_title=False)
        root = ET.fromstring(svg)
        metadata = root.find("{http://www.w3.org/2000/svg}title")
        assert metadata is not None and "Test Wreck" in (metadata.text or "")
        drawn = [el.text or "" for el in root.iter("{http://www.w3.org/2000/svg}text")]
        assert not any("Test Wreck" in t for t in drawn)

    def test_everything_off_still_renders(self, ssrf_dive: Dive) -> None:
        svg = render_svg(
            ssrf_dive,
            show_title=False,
            show_axes=False,
            show_grid=False,
            show_ceiling=False,
            show_gas=False,
            show_stats=False,
            show_legend=False,
        )
        ET.fromstring(svg)

    def test_dive_without_deco_omits_ceiling(self) -> None:
        dive = Dive(samples=(Sample(0, 0.0), Sample(60, 12.0), Sample(120, 0.0)))
        assert "ds-ceiling" not in render_svg(dive)


class TestThemes:
    def test_themes_differ(self, ssrf_dive: Dive) -> None:
        assert render_svg(ssrf_dive, theme="slate") != render_svg(
            ssrf_dive, theme="light"
        )

    def test_theme_colour_appears(self, ssrf_dive: Dive) -> None:
        assert SLATE.curve in render_svg(ssrf_dive, theme="slate")
        assert LIGHT.curve in render_svg(ssrf_dive, theme="light")

    def test_get_theme_accepts_instance(self) -> None:
        assert get_theme(SLATE) is SLATE

    def test_unknown_theme_raises(self) -> None:
        with pytest.raises(LookupError, match="unknown theme"):
            get_theme("chartreuse")

    def test_labels_are_present_for_light_theme_relief(self, ssrf_dive: Dive) -> None:
        """The light accent is sub-3:1, so the gas name must be drawn."""
        svg = render_svg(ssrf_dive, theme="light")
        assert "EAN50" in svg


class TestScalesAndLayout:
    def test_scale_maps_endpoints(self) -> None:
        scale = Scale(0.0, 100.0, 0.0, 200.0)
        assert scale(0.0) == 0.0
        assert scale(50.0) == 100.0
        assert scale(100.0) == 200.0

    def test_scale_with_zero_span(self) -> None:
        assert Scale(5.0, 5.0, 0.0, 10.0)(5.0) == 0.0

    def test_depth_axis_starts_at_surface(self) -> None:
        layout = Layout.build(
            width=800,
            height=400,
            duration_s=1800,
            max_depth_m=38,
            margins=Margins(10, 10, 10, 10),
        )
        assert layout.y.domain_min == 0.0

    def test_depth_axis_extends_past_deepest_point(self) -> None:
        layout = Layout.build(
            width=800,
            height=400,
            duration_s=1800,
            max_depth_m=38,
            margins=Margins(10, 10, 10, 10),
        )
        assert layout.y.domain_max > 38.0

    def test_ticks_span_domain(self) -> None:
        assert Scale(0.0, 50.0, 0.0, 100.0).ticks(10.0) == [0, 10, 20, 30, 40, 50]

    def test_nice_step_picks_from_the_allowed_set(self) -> None:
        steps = (1.0, 2.0, 5.0, 10.0)
        assert nice_step(50.0, 5, steps) == 10.0


class TestSvgPrimitives:
    def test_fmt_drops_trailing_zero(self) -> None:
        assert fmt(10.0) == "10"
        assert fmt(10.25) == "10.25"

    def test_path_from_points(self) -> None:
        assert points_to_path([(0, 0), (1, 2)]) == "M 0 0 L 1 2"

    def test_closed_path(self) -> None:
        assert points_to_path([(0, 0), (1, 2)], close=True).endswith("Z")

    def test_empty_path(self) -> None:
        assert points_to_path([]) == ""

    def test_text_is_escaped(self) -> None:
        canvas = Canvas(10, 10)
        canvas.text("<script>&", 0, 0, fill="#000")
        assert "<script>" not in canvas.to_svg()
        assert "&lt;script&gt;&amp;" in canvas.to_svg()

    def test_text_is_painted_twice_when_haloed(self) -> None:
        canvas = Canvas(10, 10)
        canvas.text("hi", 0, 0, fill="#000", halo="#fff")
        assert canvas.to_svg().count("<text") == 2

    def test_text_without_halo_is_painted_once(self) -> None:
        canvas = Canvas(10, 10)
        canvas.text("hi", 0, 0, fill="#000")
        assert canvas.to_svg().count("<text") == 1

    def test_extra_attrs_become_hyphenated(self) -> None:
        canvas = Canvas(10, 10)
        canvas.text("hi", 0, 0, fill="#000", letter_spacing="0.1em")
        assert 'letter-spacing="0.1em"' in canvas.to_svg()


class TestRenderOptions:
    def test_overrides_do_not_mutate_the_original(self, ssrf_dive: Dive) -> None:
        options = RenderOptions()
        render_svg(ssrf_dive, options, width=400)
        assert options.width == 1600.0
