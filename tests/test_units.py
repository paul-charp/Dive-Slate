"""Quantity string parsing."""

from __future__ import annotations

import pytest

from diveslate.core.units import (
    UnitError,
    ceil_metres,
    ceil_minutes,
    format_duration,
    format_minutes,
    parse_depth_m,
    parse_duration_s,
    parse_percent,
    parse_pressure_bar,
    parse_temperature_c,
    parse_volume_l,
)


class TestDepth:
    def test_metres(self) -> None:
        assert parse_depth_m("44.4 m") == pytest.approx(44.4)

    def test_bare_number_is_metres(self) -> None:
        assert parse_depth_m("12") == pytest.approx(12.0)

    def test_feet_convert(self) -> None:
        assert parse_depth_m("100 ft") == pytest.approx(30.48, abs=1e-3)

    def test_unknown_unit_raises(self) -> None:
        with pytest.raises(UnitError):
            parse_depth_m("44.4 furlongs")


class TestDuration:
    def test_colon_form_is_minutes_and_seconds(self) -> None:
        # The trap: '44:20 min' is 44 min 20 s, not 44.2 minutes.
        assert parse_duration_s("44:20 min") == 44 * 60 + 20

    def test_hours_colon_form(self) -> None:
        assert parse_duration_s("1:02:03") == 3600 + 120 + 3

    def test_scalar_minutes(self) -> None:
        assert parse_duration_s("3 min") == 180.0

    def test_scalar_seconds(self) -> None:
        assert parse_duration_s("90 s") == 90.0

    def test_bare_scalar_is_minutes(self) -> None:
        assert parse_duration_s("2") == 120.0


class TestOtherQuantities:
    def test_pressure_bar(self) -> None:
        assert parse_pressure_bar("206.843 bar") == pytest.approx(206.843)

    def test_pressure_psi(self) -> None:
        assert parse_pressure_bar("3000 psi") == pytest.approx(206.84, abs=0.01)

    def test_temperature_celsius(self) -> None:
        assert parse_temperature_c("15.0 C") == pytest.approx(15.0)

    def test_temperature_fahrenheit(self) -> None:
        assert parse_temperature_c("59 F") == pytest.approx(15.0)

    def test_temperature_kelvin(self) -> None:
        assert parse_temperature_c("288.15 K") == pytest.approx(15.0)

    def test_volume_litres(self) -> None:
        assert parse_volume_l("24.0 l") == pytest.approx(24.0)

    def test_percent_becomes_fraction(self) -> None:
        assert parse_percent("31%") == pytest.approx(0.31)


class TestFormatDuration:
    @pytest.mark.parametrize(
        ("seconds", "expected"),
        [(0, "0:00"), (65, "1:05"), (3860, "1:04:20"), (599, "9:59")],
    )
    def test_formats(self, seconds: float, expected: str) -> None:
        assert format_duration(seconds) == expected


class TestCeilingRounding:
    """Badge figures round up, never to nearest — 44.4 m is a 45 m dive."""

    @pytest.mark.parametrize(
        ("metres", "expected"),
        [(44.4, 45), (38.0, 38), (0.1, 1), (23.33, 24), (12.999, 13)],
    )
    def test_ceil_metres(self, metres: float, expected: int) -> None:
        assert ceil_metres(metres) == expected

    @pytest.mark.parametrize(
        ("seconds", "expected"),
        [(3860, 65), (1800, 30), (1, 1), (3600, 60), (1400, 24), (0, 0)],
    )
    def test_ceil_minutes(self, seconds: float, expected: int) -> None:
        assert ceil_minutes(seconds) == expected

    def test_exact_values_do_not_tip_over(self) -> None:
        """A whole number of minutes must not round up to the next one."""
        assert ceil_minutes(3600.0) == 60
        assert ceil_metres(38.0) == 38

    @pytest.mark.parametrize(
        ("seconds", "expected"),
        [
            (3860, ("1:05", "")),  # past an hour reads as h:mm
            (1400, ("24", "min")),
            (1800, ("30", "min")),
            (3600, ("1:00", "")),
        ],
    )
    def test_format_minutes(self, seconds: float, expected: tuple[str, str]) -> None:
        assert format_minutes(seconds) == expected
