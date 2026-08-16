"""Subsurface XML parsing, with emphasis on the sparse-attribute semantics."""

from __future__ import annotations

import pytest

from diveslate.core.models import Dive
from diveslate.parsers import ParseError, SubsurfaceParser


class TestMetadata:
    def test_site_resolved_from_uuid(self, ssrf_dive: Dive) -> None:
        assert ssrf_dive.site == "Test Wreck"

    def test_scalar_fields(self, ssrf_dive: Dive) -> None:
        assert ssrf_dive.number == 42
        assert ssrf_dive.buddy == "Test Buddy"
        assert ssrf_dive.computer == "Test Computer"
        assert ssrf_dive.deco_model == "GF 40/85"
        assert ssrf_dive.tags == ("deco", "test")

    def test_cns_is_a_fraction(self, ssrf_dive: Dive) -> None:
        assert ssrf_dive.cns == pytest.approx(0.08)

    def test_datetime(self, ssrf_dive: Dive) -> None:
        assert ssrf_dive.when is not None
        assert ssrf_dive.when.isoformat() == "2026-05-04T10:15:00"


class TestCarryForward:
    """Subsurface writes an attribute only when it changes."""

    def test_temperature_carries_until_restated(self, ssrf_dive: Dive) -> None:
        by_time = {s.time_s: s for s in ssrf_dive.samples}
        # 12 C is set at 2:00 and not restated until 17 C at 30:00.
        assert by_time[120.0].temp_c == pytest.approx(12.0)
        assert by_time[180.0].temp_c == pytest.approx(12.0)
        assert by_time[900.0].temp_c == pytest.approx(12.0)
        assert by_time[1800.0].temp_c == pytest.approx(17.0)

    def test_in_deco_stays_set_until_cleared(self, ssrf_dive: Dive) -> None:
        by_time = {s.time_s: s for s in ssrf_dive.samples}
        assert by_time[180.0].in_deco is False  # before the flag appears
        assert by_time[240.0].in_deco is True  # in_deco='1' here
        assert by_time[600.0].in_deco is True  # not restated, must persist
        assert by_time[1500.0].in_deco is True
        assert by_time[1680.0].in_deco is False  # in_deco='0' here

    def test_ceiling_carries_forward(self, ssrf_dive: Dive) -> None:
        by_time = {s.time_s: s for s in ssrf_dive.samples}
        assert by_time[360.0].stop_depth_m == pytest.approx(9.0)
        # 10:00 restates nothing, so the 9 m ceiling holds.
        assert by_time[600.0].stop_depth_m == pytest.approx(9.0)
        assert by_time[900.0].stop_depth_m == pytest.approx(6.0)

    def test_zero_ceiling_becomes_none(self, ssrf_dive: Dive) -> None:
        by_time = {s.time_s: s for s in ssrf_dive.samples}
        assert by_time[1680.0].stop_depth_m is None


class TestGas:
    def test_cylinders(self, ssrf_dive: Dive) -> None:
        assert len(ssrf_dive.cylinders) == 2
        bottom, deco = ssrf_dive.cylinders
        assert bottom.gas.name == "Air"  # no o2 attribute means air
        assert deco.gas.name == "EAN50"
        assert bottom.used_bar == pytest.approx(140.0)

    def test_switch_without_o2_resolves_via_cylinder_index(
        self, ssrf_dive: Dive
    ) -> None:
        first = ssrf_dive.gas_switches[0]
        assert first.time_s == 30.0
        assert first.gas.name == "Air"
        assert first.cylinder_index == 0

    def test_switch_with_explicit_o2(self, ssrf_dive: Dive) -> None:
        second = ssrf_dive.gas_switches[1]
        assert second.time_s == 22 * 60
        assert second.gas.name == "EAN50"

    def test_gas_at_time(self, ssrf_dive: Dive) -> None:
        assert ssrf_dive.gas_at(0.0) is None  # before the first switch
        assert ssrf_dive.gas_at(600.0) is not None
        assert ssrf_dive.gas_at(600.0).name == "Air"  # type: ignore[union-attr]
        assert ssrf_dive.gas_at(1500.0).name == "EAN50"  # type: ignore[union-attr]


class TestDerived:
    def test_deco_span(self, ssrf_dive: Dive) -> None:
        spans = ssrf_dive.deco_spans()
        assert len(spans) == 1
        assert spans[0].start_s == 240.0
        assert spans[0].end_s == 1680.0

    def test_max_depth_from_samples(self, ssrf_dive: Dive) -> None:
        assert ssrf_dive.computed_max_depth_m == pytest.approx(38.0)

    def test_temperature_range(self, ssrf_dive: Dive) -> None:
        assert ssrf_dive.temperature_range_c == (12.0, 18.0)


class TestTrips:
    """Dives grouped into trips must be found, not silently skipped.

    Subsurface nests a trip's dives inside ``<trip>`` rather than leaving them
    as direct children of ``<dives>``. Matching only the direct children found
    nothing at all in a logbook where every dive belongs to a trip — a real
    export that "parsed fine" and yielded zero dives.
    """

    def test_finds_dives_inside_and_outside_a_trip(self, trips_log: object) -> None:
        assert [d.number for d in trips_log.dives] == [87, 88, 89]  # type: ignore[attr-defined]

    def test_trip_dives_keep_their_own_metadata(self, trips_log: object) -> None:
        first = trips_log.dives[0]  # type: ignore[attr-defined]
        assert first.site == "Sample Wall"
        assert first.computed_max_depth_m == pytest.approx(16.9)
        assert first.gradient_factors == (85, 85)

    def test_ungrouped_dive_is_not_lost(self, trips_log: object) -> None:
        last = trips_log.dives[-1]  # type: ignore[attr-defined]
        assert last.number == 89
        assert last.site == "Sample Wall"


class TestErrors:
    def test_wrong_root_rejected(self) -> None:
        with pytest.raises(ParseError, match="expected a <divelog> root"):
            SubsurfaceParser.parse("<notalog/>")

    def test_malformed_xml_rejected(self) -> None:
        with pytest.raises(ParseError, match="malformed"):
            SubsurfaceParser.parse("<divelog><dives>")

    def test_sniff(self, ssrf_path) -> None:  # type: ignore[no-untyped-def]
        assert SubsurfaceParser.sniff(ssrf_path.read_text(encoding="utf-8"))
        assert not SubsurfaceParser.sniff("<uddf/>")
