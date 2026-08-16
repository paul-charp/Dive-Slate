"""The format-neutral dive model every parser produces and the renderer consumes.

Nothing here knows about XML. Parsers in :mod:`diveslate.parsers` are responsible
for reconciling their format's quirks into these types, so adding a log format
never touches the renderer.

All quantities are canonical units (see :mod:`diveslate.core.units`): metres,
seconds, bar, Celsius, litres. Every field a log may omit is ``None`` rather
than a sentinel zero — a dive with no recorded temperature must not render as a
dive at 0 °C.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Self

__all__ = [
    "AIR",
    "DECO_STOP_TOLERANCE_M",
    "Cylinder",
    "DecoSpan",
    "Dive",
    "DiveLog",
    "GasMix",
    "GasSwitch",
    "Sample",
]

#: How far below the ceiling a diver still counts as "at" their stop, in metres.
#: Stops are held by eye against a fluctuating computer reading, so an exact
#: match would only ever register on a simulated profile.
DECO_STOP_TOLERANCE_M = 3.0


@dataclass(frozen=True, slots=True)
class GasMix:
    """A breathing mix, stored as fractions of 1."""

    o2: float = 0.21
    he: float = 0.0

    @property
    def n2(self) -> float:
        return 1.0 - self.o2 - self.he

    @property
    def is_air(self) -> bool:
        # Dive computers round trip 21% through decimal strings, so compare with
        # a tolerance rather than testing equality against 0.21.
        return abs(self.o2 - 0.21) < 0.005 and self.he < 0.005

    @property
    def name(self) -> str:
        """Short label divers actually use: ``Air``, ``EAN32``, ``Tx18/45``, ``O2``."""
        o2_pct = round(self.o2 * 100)
        he_pct = round(self.he * 100)
        if he_pct > 0:
            return f"Tx{o2_pct}/{he_pct}"
        if o2_pct >= 99:
            return "O2"
        if self.is_air:
            return "Air"
        return f"EAN{o2_pct}"

    def mod_m(self, ppo2_max: float = 1.4) -> float:
        """Maximum operating depth in metres at ``ppo2_max``, in salt water."""
        return (ppo2_max / self.o2 - 1.0) * 10.0


AIR = GasMix()


@dataclass(frozen=True, slots=True)
class Cylinder:
    """A cylinder carried on the dive."""

    gas: GasMix = AIR
    description: str | None = None
    size_l: float | None = None
    work_pressure_bar: float | None = None
    start_bar: float | None = None
    end_bar: float | None = None

    @property
    def used_bar(self) -> float | None:
        if self.start_bar is None or self.end_bar is None:
            return None
        return self.start_bar - self.end_bar

    @property
    def used_l(self) -> float | None:
        """Free-gas volume consumed, litres at surface pressure."""
        used = self.used_bar
        if used is None or self.size_l is None:
            return None
        return used * self.size_l

    @property
    def label(self) -> str:
        return self.description or self.gas.name


@dataclass(frozen=True, slots=True)
class Sample:
    """One instant on the profile.

    Note that most fields are optional: dive computers record different subsets,
    and Subsurface only writes an attribute when its value *changes*. Parsers
    carry values forward so that consumers here see a fully populated series.
    """

    time_s: float
    depth_m: float
    temp_c: float | None = None
    ndl_s: float | None = None
    tts_s: float | None = None
    in_deco: bool = False
    stop_depth_m: float | None = None
    stop_time_s: float | None = None
    cns: float | None = None
    pressure_bar: float | None = None


@dataclass(frozen=True, slots=True)
class GasSwitch:
    """A change of breathing gas at a point in time."""

    time_s: float
    gas: GasMix
    cylinder_index: int | None = None


@dataclass(frozen=True, slots=True)
class DecoSpan:
    """A contiguous stretch of the dive spent in decompression obligation."""

    start_s: float
    end_s: float

    @property
    def duration_s(self) -> float:
        return self.end_s - self.start_s


@dataclass(frozen=True, slots=True)
class Dive:
    """A single dive: metadata, gas, and the sampled profile."""

    samples: tuple[Sample, ...] = ()
    cylinders: tuple[Cylinder, ...] = ()
    gas_switches: tuple[GasSwitch, ...] = ()

    number: int | None = None
    when: datetime | None = None
    site: str | None = None
    buddy: str | None = None
    notes: str | None = None
    rating: int | None = None

    duration_s: float | None = None
    max_depth_m: float | None = None
    mean_depth_m: float | None = None
    water_temp_c: float | None = None
    surface_pressure_bar: float | None = None
    salinity_g_l: float | None = None

    sac_l_min: float | None = None
    otu: float | None = None
    cns: float | None = None

    computer: str | None = None
    deco_model: str | None = None
    tags: tuple[str, ...] = ()

    # ---- derived views over the sample series -----------------------------

    @property
    def computed_max_depth_m(self) -> float:
        """Deepest sampled depth, falling back to the logged summary value."""
        if self.samples:
            return max(s.depth_m for s in self.samples)
        return self.max_depth_m or 0.0

    @property
    def computed_duration_s(self) -> float:
        """Profile length, falling back to the logged summary value."""
        if self.samples:
            return self.samples[-1].time_s
        return self.duration_s or 0.0

    @property
    def computed_mean_depth_m(self) -> float | None:
        """Time-weighted average depth over the sampled profile.

        Prefers the logged value: a computer averages over its own raw series,
        which is usually finer than what it exports.
        """
        if self.mean_depth_m is not None:
            return self.mean_depth_m
        if len(self.samples) < 2:
            return None
        area = 0.0
        for previous, current in zip(self.samples, self.samples[1:], strict=False):
            dt = current.time_s - previous.time_s
            area += (previous.depth_m + current.depth_m) / 2.0 * dt
        total = self.samples[-1].time_s - self.samples[0].time_s
        return area / total if total > 0 else None

    @property
    def temperature_range_c(self) -> tuple[float, float] | None:
        temps = [s.temp_c for s in self.samples if s.temp_c is not None]
        if not temps:
            return None
        return min(temps), max(temps)

    def deco_spans(self) -> tuple[DecoSpan, ...]:
        """Contiguous stretches where the computer showed a deco obligation.

        A span is closed at the first sample that clears the obligation, so a
        dive that surfaces still in deco yields a span ending at the last sample.
        """
        spans: list[DecoSpan] = []
        start: float | None = None
        for sample in self.samples:
            if sample.in_deco and start is None:
                start = sample.time_s
            elif not sample.in_deco and start is not None:
                spans.append(DecoSpan(start, sample.time_s))
                start = None
        if start is not None and self.samples:
            spans.append(DecoSpan(start, self.samples[-1].time_s))
        return tuple(spans)

    def deco_time_s(self, tolerance_m: float = DECO_STOP_TOLERANCE_M) -> float | None:
        """Time actually spent decompressing, or ``None`` on a no-stop dive.

        This is **not** the same as the total length of :meth:`deco_spans`, and
        the difference is large enough to matter. An obligation appears the
        moment the computer's ceiling leaves the surface — typically while the
        diver is still on the bottom — so the obligation span covers most of the
        dive. Reporting that as "deco" claims the diver spent fifty minutes on
        stops when they spent fifty minutes *owing* them.

        What a diver means by deco time is the hang: from first reaching the
        ceiling on the way up until the obligation clears. ``tolerance_m`` is
        the slack allowed in "reaching" it, since nobody holds a stop to the
        centimetre; the result is insensitive to its exact value because the
        ascent through the last few metres is quick.

        Each span is measured against the end of *its own* obligation and the
        hangs summed. A dive that clears deco and then re-incurs it served two
        hangs; the interval in between was spent clear and is not deco time.
        Spans whose ceiling was never reached contribute nothing rather than
        voiding the hangs that were served.
        """
        spans = self.deco_spans()
        if not spans:
            return None

        total = 0.0
        served = False
        for span in spans:
            for sample in self.samples:
                if sample.time_s < span.start_s:
                    continue
                if sample.time_s >= span.end_s:
                    break
                if (
                    sample.in_deco
                    and sample.stop_depth_m
                    and sample.depth_m <= sample.stop_depth_m + tolerance_m
                ):
                    total += span.end_s - sample.time_s
                    served = True
                    break

        # An obligation that never got served — the dive ended still in deco.
        return total if served else None

    @property
    def gradient_factors(self) -> tuple[int, int] | None:
        """``(low, high)`` gradient factors, read out of the deco model string.

        There is no dedicated field for these. Computers write them into a free
        text label — Shearwater logs ``"GF 70/80"``, others ``"ZHL16C GF30/85"``
        or ``"Buhlmann ZH-L16C + GF 30/85"`` — so they are recovered by pattern
        rather than parsed. Anything that does not look like a pair of
        percentages returns ``None`` instead of a guess; a VPM-B dive has no
        gradient factors at all and must not be made to appear as though it does.
        """
        if not self.deco_model:
            return None
        match = re.search(r"(\d{1,3})\s*/\s*(\d{1,3})", self.deco_model)
        if match is None:
            return None
        low, high = int(match[1]), int(match[2])
        # Gradient factors are percentages, and low never exceeds high.
        if not (0 < low <= 100 and 0 < high <= 100 and low <= high):
            return None
        return low, high

    @property
    def gas_used_l(self) -> float | None:
        """Total free gas consumed across every cylinder, litres at the surface.

        ``None`` unless at least one cylinder records a size *and* both start and
        end pressures — plenty of logs have one without the others. Cylinders
        that came back fuller than they went in are dropped rather than
        subtracted: that is a mistyped pressure, and letting it cancel out real
        consumption from another tank would quietly understate the total.
        """
        volumes = [
            used
            for cylinder in self.cylinders
            if (used := cylinder.used_l) is not None and used > 0
        ]
        return sum(volumes) if volumes else None

    @property
    def gas_used_by_cylinder(self) -> tuple[tuple[str, float], ...]:
        """``(label, litres)`` per cylinder that recorded enough to compute it."""
        return tuple(
            (cylinder.label, used)
            for cylinder in self.cylinders
            if (used := cylinder.used_l) is not None and used > 0
        )

    def gas_at(self, time_s: float) -> GasMix | None:
        """The mix being breathed at ``time_s``, or ``None`` before the first switch."""
        current: GasMix | None = None
        for switch in self.gas_switches:
            if switch.time_s > time_s:
                break
            current = switch.gas
        return current

    @property
    def title(self) -> str:
        """A human label for the dive, best-effort from whatever the log carries."""
        parts: list[str] = []
        if self.number is not None:
            parts.append(f"#{self.number}")
        if self.site:
            parts.append(self.site)
        elif self.when is not None:
            parts.append(self.when.strftime("%Y-%m-%d"))
        return " · ".join(parts) if parts else "Dive"


@dataclass(frozen=True, slots=True)
class DiveLog:
    """A parsed log file: one or more dives plus the program that wrote it."""

    dives: tuple[Dive, ...] = ()
    program: str | None = None
    source: str | None = None
    sites: dict[str, str] = field(default_factory=dict)

    def __len__(self) -> int:
        return len(self.dives)

    def __iter__(self) -> object:
        return iter(self.dives)

    def __getitem__(self, index: int) -> Dive:
        return self.dives[index]

    def only(self) -> Dive:
        """The single dive in this log, erroring if the count is not exactly one."""
        if len(self.dives) != 1:
            raise ValueError(
                f"expected exactly one dive, found {len(self.dives)}; "
                "select one explicitly"
            )
        return self.dives[0]

    @classmethod
    def of(cls, dive: Dive, **kwargs: object) -> Self:
        return cls(dives=(dive,), **kwargs)  # type: ignore[arg-type]
