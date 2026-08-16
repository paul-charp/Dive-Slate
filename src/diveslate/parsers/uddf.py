"""Reader for UDDF (Universal Dive Data Format) 3.x logs.

Two things differ sharply from the Subsurface reader:

* **Units are strict SI and unlabelled.** Depths are metres, times are *seconds*
  (not the mm:ss Subsurface uses), temperatures are *Kelvin*, pressures are
  *Pascal*. There are no unit suffixes to check against, so a misread here is
  silent — hence the explicit conversions below rather than reuse of
  :mod:`diveslate.core.units`.
* **Namespaces vary by minor version** (``.../uddf/3.0/``, ``3.1``, ``3.2``) and
  some exporters emit none at all. Tags are matched on local name only.

Unlike Subsurface, UDDF waypoints are self-contained: a value absent from a
waypoint is genuinely absent, not inherited. The one exception is the breathing
mix, which is set by a ``<switchmix>`` and holds until the next one.
"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import ClassVar
from xml.etree import ElementTree as ET

from diveslate.core.models import (
    Cylinder,
    Dive,
    DiveLog,
    GasMix,
    GasSwitch,
    Sample,
)
from diveslate.parsers.base import ParseError

__all__ = ["UddfParser"]

_KELVIN_OFFSET = 273.15
_PASCAL_PER_BAR = 100_000.0


def _local(tag: str) -> str:
    """The tag name without its ``{namespace}`` prefix."""
    return tag.rpartition("}")[2]


def _find(element: ET.Element, *path: str) -> ET.Element | None:
    """Namespace-agnostic descent through direct children."""
    current = element
    for name in path:
        for child in current:
            if _local(child.tag) == name:
                current = child
                break
        else:
            return None
    return current


def _findall(element: ET.Element, name: str) -> list[ET.Element]:
    return [child for child in element if _local(child.tag) == name]


def _iterfind(element: ET.Element, name: str) -> list[ET.Element]:
    """All descendants with local tag ``name``, at any depth."""
    return [el for el in element.iter() if _local(el.tag) == name]


def _text_float(element: ET.Element | None, *path: str) -> float | None:
    if element is None:
        return None
    target = _find(element, *path) if path else element
    if target is None or target.text is None or not target.text.strip():
        return None
    try:
        return float(target.text.strip())
    except ValueError:
        return None


def _fraction(value: float | None) -> float | None:
    """Normalise a gas fraction that may have been written as a percentage."""
    if value is None:
        return None
    return value / 100.0 if value > 1.0 else value


def _parse_mixes(root: ET.Element) -> dict[str, GasMix]:
    """Build the ``id`` → mix table that ``<switchmix ref=...>`` points into."""
    mixes: dict[str, GasMix] = {}
    for mix_el in _iterfind(root, "mix"):
        mix_id = mix_el.get("id")
        if not mix_id:
            continue
        o2 = _fraction(_text_float(mix_el, "o2"))
        he = _fraction(_text_float(mix_el, "he")) or 0.0
        if o2 is None:
            # Some writers give only n2/he and leave o2 implied by the remainder.
            n2 = _fraction(_text_float(mix_el, "n2"))
            o2 = max(0.0, 1.0 - n2 - he) if n2 is not None else 0.21
        mixes[mix_id] = GasMix(o2=o2, he=he)
    return mixes


def _parse_waypoints(
    samples_el: ET.Element, mixes: dict[str, GasMix]
) -> tuple[tuple[Sample, ...], tuple[GasSwitch, ...]]:
    samples: list[Sample] = []
    switches: list[GasSwitch] = []
    current_gas: GasMix | None = None

    for wp in _findall(samples_el, "waypoint"):
        time_s = _text_float(wp, "divetime")
        depth_m = _text_float(wp, "depth")
        if time_s is None or depth_m is None:
            continue

        # A mix switch is recorded on the waypoint where it happens.
        if (switch_el := _find(wp, "switchmix")) is not None:
            ref = switch_el.get("ref")
            if ref and (gas := mixes.get(ref)) is not None and gas != current_gas:
                switches.append(GasSwitch(time_s=time_s, gas=gas))
                current_gas = gas

        temp_k = _text_float(wp, "temperature")
        pressure_pa = _text_float(wp, "tankpressure")

        # <decostop kind="mandatory"> is an obligation; kind="safety" is not, and
        # treating a safety stop as deco would shade half the recreational dives
        # ever logged.
        stop_depth_m: float | None = None
        stop_time_s: float | None = None
        in_deco = False
        for stop_el in _findall(wp, "decostop"):
            if stop_el.get("kind", "mandatory") != "mandatory":
                continue
            in_deco = True
            try:
                stop_depth_m = float(stop_el.get("decodepth", "") or "nan")
                stop_time_s = float(stop_el.get("duration", "") or "nan")
            except ValueError:
                pass
            break

        # Some exporters signal the obligation with an alarm instead.
        if not in_deco:
            in_deco = any(
                (el.text or "").strip() == "deco" for el in _findall(wp, "alarm")
            )

        samples.append(
            Sample(
                time_s=time_s,
                depth_m=depth_m,
                temp_c=None if temp_k is None else temp_k - _KELVIN_OFFSET,
                in_deco=in_deco,
                stop_depth_m=stop_depth_m if stop_depth_m else None,
                stop_time_s=stop_time_s,
                pressure_bar=(
                    None if pressure_pa is None else pressure_pa / _PASCAL_PER_BAR
                ),
            )
        )

    return tuple(samples), tuple(switches)


def _parse_when(dive_el: ET.Element) -> datetime | None:
    before = _find(dive_el, "informationbeforedive")
    if before is None:
        return None
    dt_el = _find(before, "datetime")
    if dt_el is None:
        return None
    raw = (dt_el.text or "").strip()
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw)
    except ValueError:
        return None


class UddfParser:
    """Parses UDDF 3.x ``<uddf>`` documents."""

    extensions: ClassVar[tuple[str, ...]] = (".uddf", ".xml")
    format_name: ClassVar[str] = "UDDF"

    @classmethod
    def sniff(cls, text: str) -> bool:
        head = text[:4096]
        return "<uddf" in head or "uddf" in head and "<profiledata" in head

    @classmethod
    def parse(cls, text: str, *, source: str | None = None) -> DiveLog:
        try:
            root = ET.fromstring(text)
        except ET.ParseError as exc:
            raise ParseError(f"malformed UDDF: {exc}") from exc

        if _local(root.tag) != "uddf":
            raise ParseError(f"expected a <uddf> root, found <{_local(root.tag)}>")

        mixes = _parse_mixes(root)
        generator = _find(root, "generator")
        program = None
        if generator is not None:
            name_el = _find(generator, "name")
            program = (name_el.text or "").strip() if name_el is not None else None

        dives = tuple(
            cls._parse_dive(el, mixes)
            for el in _iterfind(root, "dive")
            # <dive> also appears under <gasdefinitions> in some files; only
            # those carrying waypoints are profiles.
            if _find(el, "samples") is not None
        )
        return DiveLog(dives=dives, program=program, source=source)

    @classmethod
    def _parse_dive(cls, dive_el: ET.Element, mixes: dict[str, GasMix]) -> Dive:
        samples_el = _find(dive_el, "samples")
        samples: tuple[Sample, ...] = ()
        switches: tuple[GasSwitch, ...] = ()
        if samples_el is not None:
            samples, switches = _parse_waypoints(samples_el, mixes)

        before = _find(dive_el, "informationbeforedive")
        after = _find(dive_el, "informationafterdive")

        # `if after` would test the element's child count, not its presence.
        lowest_k = (
            _text_float(after, "lowesttemperature") if after is not None else None
        )

        # Cylinders are described per-dive by <tankdata>.
        cylinders: list[Cylinder] = []
        for tank in _findall(dive_el, "tankdata"):
            ref_el = _find(tank, "link")
            ref = ref_el.get("ref") if ref_el is not None else None
            start_pa = _text_float(tank, "tankpressurebegin")
            end_pa = _text_float(tank, "tankpressureend")
            cylinders.append(
                Cylinder(
                    gas=mixes.get(ref or "", GasMix()),
                    size_l=_text_float(tank, "tankvolume"),
                    start_bar=None if start_pa is None else start_pa / _PASCAL_PER_BAR,
                    end_bar=None if end_pa is None else end_pa / _PASCAL_PER_BAR,
                )
            )

        return Dive(
            samples=samples,
            cylinders=tuple(cylinders),
            gas_switches=switches,
            number=(
                int(n) if (n := _text_float(before, "divenumber")) is not None else None
            ),
            when=_parse_when(dive_el),
            duration_s=_text_float(after, "diveduration"),
            max_depth_m=_text_float(after, "greatestdepth"),
            mean_depth_m=_text_float(after, "averagedepth"),
            water_temp_c=None if lowest_k is None else lowest_k - _KELVIN_OFFSET,
        )

    @classmethod
    def parse_file(cls, path: str | Path) -> DiveLog:
        path = Path(path)
        return cls.parse(path.read_text(encoding="utf-8"), source=str(path))
