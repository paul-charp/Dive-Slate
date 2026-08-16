"""UDDF parsing: SI units, namespaces, and mandatory-vs-safety stops."""

from __future__ import annotations

import pytest

from diveslate.core.models import Dive
from diveslate.parsers import ParseError, UddfParser, parse_file


class TestUddf:
    def test_namespaced_document_parses(self, uddf_dive: Dive) -> None:
        assert uddf_dive.number == 42
        assert len(uddf_dive.samples) == 8

    def test_divetime_is_seconds_not_minutes(self, uddf_dive: Dive) -> None:
        assert uddf_dive.samples[1].time_s == 60.0
        assert uddf_dive.computed_duration_s == 1800.0

    def test_temperature_converted_from_kelvin(self, uddf_dive: Dive) -> None:
        assert uddf_dive.samples[0].temp_c == pytest.approx(18.0)
        assert uddf_dive.water_temp_c == pytest.approx(12.0)

    def test_tank_pressure_converted_from_pascal(self, uddf_dive: Dive) -> None:
        cylinder = uddf_dive.cylinders[0]
        assert cylinder.start_bar == pytest.approx(230.0)
        assert cylinder.end_bar == pytest.approx(90.0)

    def test_switchmix_produces_gas_switches(self, uddf_dive: Dive) -> None:
        names = [s.gas.name for s in uddf_dive.gas_switches]
        assert names == ["Air", "EAN50"]

    def test_safety_stop_is_not_deco(self, uddf_dive: Dive) -> None:
        """kind='safety' must not count as an obligation."""
        last_stop = uddf_dive.samples[6]
        assert last_stop.time_s == 1680.0
        assert last_stop.in_deco is False

    def test_deco_span_covers_mandatory_stops_only(self, uddf_dive: Dive) -> None:
        spans = uddf_dive.deco_spans()
        assert len(spans) == 1
        assert spans[0].start_s == 240.0
        assert spans[0].end_s == 1680.0

    def test_no_carry_forward_in_uddf(self, uddf_dive: Dive) -> None:
        """Unlike Subsurface, a UDDF waypoint is self-contained."""
        assert uddf_dive.samples[1].temp_c is None

    def test_detected_by_content(self, uddf_path) -> None:  # type: ignore[no-untyped-def]
        log = parse_file(uddf_path)
        assert log.program == "diveslate test fixture"

    def test_wrong_root_rejected(self) -> None:
        with pytest.raises(ParseError, match="expected a <uddf> root"):
            UddfParser.parse("<divelog/>")
