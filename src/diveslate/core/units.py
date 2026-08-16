"""Parsers for the quantity strings dive logs write into XML attributes.

Subsurface stores quantities as human-readable strings with a trailing unit
(``depth='44.4 m'``, ``time='62:18 min'``, ``start='200.0 bar'``) rather than as
bare numbers, and the unit actually varies with the settings of the machine that
exported the file — the same field can arrive as ``ft``, ``psi`` or ``F``. Every
parser here therefore normalises to one canonical unit and refuses input it does
not recognise, rather than silently assuming metric.

Canonical units: metres, seconds, bar, degrees Celsius, litres.
"""

from __future__ import annotations

import math
import re
from typing import Final

__all__ = [
    "UnitError",
    "ceil_metres",
    "ceil_minutes",
    "format_duration",
    "format_minutes",
    "parse_depth_m",
    "parse_duration_s",
    "parse_percent",
    "parse_pressure_bar",
    "parse_temperature_c",
    "parse_volume_l",
]


class UnitError(ValueError):
    """Raised when a quantity string cannot be understood."""


# A number followed by an optional unit word: "44.4 m", "-1.2", "206.843 bar".
_QUANTITY: Final = re.compile(
    r"^\s*(?P<value>[+-]?(?:\d+\.?\d*|\.\d+))\s*(?P<unit>[^\s\d]*)\s*$"
)

# "62:18 min", "1:02:03", "44:20 min", "90 s".
_CLOCK: Final = re.compile(
    r"^\s*(?P<parts>\d+(?::\d{1,2})+)\s*(?P<unit>[a-z]*)\s*$", re.IGNORECASE
)

_FEET_PER_METRE: Final = 3.280839895013123
_PSI_PER_BAR: Final = 14.503773800721814
_CUFT_PER_LITRE: Final = 0.035314666721488586


def _split(raw: str, what: str) -> tuple[float, str]:
    match = _QUANTITY.match(raw)
    if match is None:
        raise UnitError(f"cannot parse {what} from {raw!r}")
    return float(match["value"]), match["unit"].lower()


def parse_depth_m(raw: str) -> float:
    """Parse a depth/length into metres. Accepts ``m``, ``ft``, or no unit."""
    value, unit = _split(raw, "depth")
    match unit:
        case "" | "m" | "meter" | "meters" | "metre" | "metres":
            return value
        case "ft" | "feet" | "foot":
            return value / _FEET_PER_METRE
        case _:
            raise UnitError(f"unknown length unit {unit!r} in {raw!r}")


def parse_duration_s(raw: str) -> float:
    """Parse a duration into seconds.

    Handles both the colon form Subsurface uses for sample times and event
    times (``'44:20 min'`` — that is 44 minutes 20 seconds, *not* 44.2 minutes)
    and a plain scalar with a unit (``'90 s'``, ``'3 min'``, ``'1.5 h'``).
    """
    clock = _CLOCK.match(raw)
    if clock is not None:
        parts = [int(p) for p in clock["parts"].split(":")]
        # Two parts are mm:ss, three are hh:mm:ss — the trailing 'min' label
        # Subsurface writes refers to the leading field, so it is not a scale.
        if len(parts) == 2:
            minutes, seconds = parts
            return minutes * 60.0 + seconds
        if len(parts) == 3:
            hours, minutes, seconds = parts
            return hours * 3600.0 + minutes * 60.0 + seconds
        raise UnitError(f"cannot parse duration from {raw!r}")

    value, unit = _split(raw, "duration")
    match unit:
        case "s" | "sec" | "secs" | "second" | "seconds":
            return value
        case "" | "min" | "mins" | "minute" | "minutes":
            return value * 60.0
        case "h" | "hr" | "hrs" | "hour" | "hours":
            return value * 3600.0
        case _:
            raise UnitError(f"unknown time unit {unit!r} in {raw!r}")


def parse_pressure_bar(raw: str) -> float:
    """Parse a gas pressure into bar. Accepts ``bar``, ``psi``, or no unit."""
    value, unit = _split(raw, "pressure")
    match unit:
        case "" | "bar" | "bars":
            return value
        case "psi":
            return value / _PSI_PER_BAR
        case _:
            raise UnitError(f"unknown pressure unit {unit!r} in {raw!r}")


def parse_temperature_c(raw: str) -> float:
    """Parse a temperature into degrees Celsius. Accepts ``C``, ``F``, ``K``."""
    value, unit = _split(raw, "temperature")
    match unit:
        case "" | "c" | "°c" | "celsius":
            return value
        case "f" | "°f" | "fahrenheit":
            return (value - 32.0) * 5.0 / 9.0
        case "k" | "kelvin":
            return value - 273.15
        case _:
            raise UnitError(f"unknown temperature unit {unit!r} in {raw!r}")


def parse_volume_l(raw: str) -> float:
    """Parse a cylinder volume into litres. Accepts ``l``, ``cuft``, or none."""
    value, unit = _split(raw, "volume")
    match unit:
        case "" | "l" | "ℓ" | "liter" | "liters" | "litre" | "litres":
            return value
        case "cuft" | "cf" | "ft3":
            return value / _CUFT_PER_LITRE
        case _:
            raise UnitError(f"unknown volume unit {unit!r} in {raw!r}")


def parse_percent(raw: str) -> float:
    """Parse a percentage into a fraction of 1. ``'31%'`` becomes ``0.31``."""
    value, unit = _split(raw, "percentage")
    if unit not in ("", "%"):
        raise UnitError(f"unknown percentage unit {unit!r} in {raw!r}")
    return value / 100.0


def ceil_minutes(seconds: float) -> int:
    """Whole minutes, always rounded up.

    Rounding up rather than to nearest is the diving convention — a 64:20 dive
    is a 65-minute dive, never a 64-minute one — and it keeps a badge from ever
    understating a figure.
    """
    # The epsilon absorbs float noise so an exactly-round value does not tip
    # into the next minute: 3600 s must be 60 min, not 61.
    return math.ceil(seconds / 60.0 - 1e-9)


def ceil_metres(metres: float) -> int:
    """Whole metres, always rounded up. 44.4 m becomes 45 m."""
    return math.ceil(metres - 1e-9)


def format_minutes(seconds: float) -> tuple[str, str]:
    """``(value, unit)`` for a duration rounded up to the minute.

    Past an hour the value switches to ``h:mm`` and the unit goes empty, because
    "1:05" already reads as a time whereas "65 min" makes the reader do the
    division themselves.
    """
    minutes = ceil_minutes(seconds)
    if minutes >= 60:
        return f"{minutes // 60}:{minutes % 60:02d}", ""
    return str(minutes), "min"


def format_duration(seconds: float) -> str:
    """Render seconds as ``m:ss``, or ``h:mm:ss`` past an hour — for labels."""
    total = round(seconds)
    hours, remainder = divmod(total, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{secs:02d}"
    return f"{minutes}:{secs:02d}"
