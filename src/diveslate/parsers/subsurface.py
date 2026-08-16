"""Reader for Subsurface's native log format (``.ssrf``, sometimes ``.xml``).

The one thing worth knowing before reading this file: **Subsurface writes a
sample attribute only when its value changes.** A sample line carrying just a
time and a depth does not mean the temperature or the ceiling are unknown there
— it means they are whatever the previous sample said. Subsurface's own reader
copies the previous sample wholesale and then applies the attributes present on
the current line, and :func:`_parse_samples` reproduces that exactly. Get this
wrong and a 50-minute deco dive renders as one deco sample followed by nothing.
"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import ClassVar
from xml.etree import ElementTree as ET

from diveslate.core.models import (
    AIR,
    Cylinder,
    Dive,
    DiveLog,
    GasMix,
    GasSwitch,
    Sample,
)
from diveslate.core.units import (
    UnitError,
    parse_depth_m,
    parse_duration_s,
    parse_percent,
    parse_pressure_bar,
    parse_temperature_c,
    parse_volume_l,
)
from diveslate.parsers.base import ParseError

__all__ = ["SubsurfaceParser"]


def _opt(element: ET.Element, name: str, convert: object) -> object:
    """Apply ``convert`` to attribute ``name``, or return ``None`` if absent.

    Unparseable values are dropped rather than fatal: a stray unit from an
    unusual computer should cost one field, not the whole dive.
    """
    raw = element.get(name)
    if raw is None or not raw.strip():
        return None
    try:
        return convert(raw)  # type: ignore[operator]
    except UnitError:
        return None


def _opt_float(element: ET.Element, name: str, convert: object) -> float | None:
    value = _opt(element, name, convert)
    return value if isinstance(value, float) else None


def _opt_int(element: ET.Element, name: str) -> int | None:
    raw = element.get(name)
    if raw is None or not raw.strip():
        return None
    try:
        return int(float(raw.strip()))
    except ValueError:
        return None


def _gas_from(element: ET.Element) -> GasMix:
    """Read ``o2``/``he`` percentage attributes, defaulting to air.

    An absent or zero ``o2`` means air: Subsurface omits the attribute for air
    cylinders, and some computers write ``o2='0.0%'`` to mean the same thing.
    """
    o2 = _opt_float(element, "o2", parse_percent)
    he = _opt_float(element, "he", parse_percent) or 0.0
    if o2 is None or o2 <= 0.0:
        return GasMix(o2=AIR.o2, he=he) if he else AIR
    return GasMix(o2=o2, he=he)


def _parse_cylinders(dive_el: ET.Element) -> tuple[Cylinder, ...]:
    cylinders: list[Cylinder] = []
    for el in dive_el.findall("cylinder"):
        cylinders.append(
            Cylinder(
                gas=_gas_from(el),
                description=el.get("description") or None,
                size_l=_opt_float(el, "size", parse_volume_l),
                work_pressure_bar=_opt_float(el, "workpressure", parse_pressure_bar),
                start_bar=_opt_float(el, "start", parse_pressure_bar),
                end_bar=_opt_float(el, "end", parse_pressure_bar),
            )
        )
    return tuple(cylinders)


def _parse_gas_switches(
    computer_el: ET.Element, cylinders: tuple[Cylinder, ...]
) -> tuple[GasSwitch, ...]:
    """Collect ``gaschange`` events into an ordered switch list.

    The mix comes from the event's own ``o2``/``he`` when present and from the
    referenced cylinder otherwise — computers are inconsistent about which they
    write, and the cylinder index is the more reliable of the two.
    """
    switches: list[GasSwitch] = []
    for el in computer_el.findall("event"):
        if el.get("name") != "gaschange":
            continue
        time_s = _opt_float(el, "time", parse_duration_s)
        if time_s is None:
            continue

        index = _opt_int(el, "cylinder")
        gas: GasMix | None = None
        if el.get("o2") is not None:
            gas = _gas_from(el)
        elif index is not None and 0 <= index < len(cylinders):
            gas = cylinders[index].gas
        if gas is None:
            continue

        switches.append(GasSwitch(time_s=time_s, gas=gas, cylinder_index=index))

    switches.sort(key=lambda s: s.time_s)

    # Collapse repeats: a computer may re-announce the current mix (on ascent, or
    # after a bookmark) and each of those would otherwise draw its own marker.
    deduped: list[GasSwitch] = []
    for switch in switches:
        if deduped and deduped[-1].gas == switch.gas:
            continue
        deduped.append(switch)
    return tuple(deduped)


def _parse_samples(computer_el: ET.Element) -> tuple[Sample, ...]:
    """Expand Subsurface's sparse sample lines into a fully populated series.

    Every optional field inherits from the previous sample unless this line
    restates it — see the module docstring.
    """
    samples: list[Sample] = []

    # Carried state, updated in place as attributes appear.
    temp_c: float | None = None
    ndl_s: float | None = None
    tts_s: float | None = None
    in_deco = False
    stop_depth_m: float | None = None
    stop_time_s: float | None = None
    cns: float | None = None
    pressure_bar: float | None = None

    for el in computer_el.findall("sample"):
        time_s = _opt_float(el, "time", parse_duration_s)
        depth_m = _opt_float(el, "depth", parse_depth_m)
        if time_s is None or depth_m is None:
            # A sample without a time or a depth places no point on the curve.
            continue

        if (value := _opt_float(el, "temp", parse_temperature_c)) is not None:
            temp_c = value
        if (value := _opt_float(el, "ndl", parse_duration_s)) is not None:
            ndl_s = value
        if (value := _opt_float(el, "tts", parse_duration_s)) is not None:
            tts_s = value
        if (value := _opt_float(el, "stopdepth", parse_depth_m)) is not None:
            stop_depth_m = value
        if (value := _opt_float(el, "stoptime", parse_duration_s)) is not None:
            stop_time_s = value
        if (value := _opt_float(el, "cns", parse_percent)) is not None:
            cns = value
        if (value := _opt_float(el, "pressure", parse_pressure_bar)) is not None:
            pressure_bar = value
        if (raw := el.get("in_deco")) is not None:
            in_deco = raw.strip() == "1"

        samples.append(
            Sample(
                time_s=time_s,
                depth_m=depth_m,
                temp_c=temp_c,
                ndl_s=ndl_s,
                tts_s=tts_s,
                in_deco=in_deco,
                # A zero ceiling is "no ceiling", which reads better as None than
                # as a stop at the surface.
                stop_depth_m=stop_depth_m if stop_depth_m else None,
                stop_time_s=stop_time_s,
                cns=cns,
                pressure_bar=pressure_bar,
            )
        )

    return tuple(samples)


def _parse_when(dive_el: ET.Element) -> datetime | None:
    date, time = dive_el.get("date"), dive_el.get("time")
    if not date:
        return None
    try:
        return datetime.fromisoformat(f"{date}T{time or '00:00:00'}")
    except ValueError:
        return None


def _pick_computer(dive_el: ET.Element) -> ET.Element | None:
    """Choose the divecomputer to plot.

    A dive may carry several (a primary plus a backup, or an imported duplicate).
    Prefer the one with the most samples, which is the richest profile; fall back
    to the first so that a dive logged without samples still yields metadata.
    """
    computers = dive_el.findall("divecomputer")
    if not computers:
        return None
    return max(computers, key=lambda el: len(el.findall("sample")))


class SubsurfaceParser:
    """Parses Subsurface's ``<divelog>`` XML."""

    extensions: ClassVar[tuple[str, ...]] = (".ssrf", ".xml")
    format_name: ClassVar[str] = "Subsurface XML"

    @classmethod
    def sniff(cls, text: str) -> bool:
        head = text[:4096]
        return "<divelog" in head

    @classmethod
    def parse(cls, text: str, *, source: str | None = None) -> DiveLog:
        try:
            root = ET.fromstring(text)
        except ET.ParseError as exc:
            raise ParseError(f"malformed Subsurface XML: {exc}") from exc

        if root.tag != "divelog":
            raise ParseError(f"expected a <divelog> root, found <{root.tag}>")

        sites = {
            uuid: name
            for site in root.iterfind("divesites/site")
            if (uuid := site.get("uuid")) and (name := site.get("name"))
        }

        # Dives sit either directly under <dives> or inside a <trip>, and a log
        # can mix both. Matching only the direct children finds nothing at all
        # in a logbook where every dive belongs to a trip — which is the normal
        # case for anyone who groups their dives, and produced a log that
        # "parsed fine" with zero dives in it.
        dives_el = root.find("dives")
        found = dives_el.iterfind(".//dive") if dives_el is not None else ()
        dives = tuple(cls._parse_dive(el, sites) for el in found)
        return DiveLog(
            dives=dives,
            program=root.get("program"),
            source=source,
            sites=sites,
        )

    @classmethod
    def _parse_dive(cls, dive_el: ET.Element, sites: dict[str, str]) -> Dive:
        cylinders = _parse_cylinders(dive_el)
        computer_el = _pick_computer(dive_el)

        samples: tuple[Sample, ...] = ()
        switches: tuple[GasSwitch, ...] = ()
        computer = deco_model = None
        max_depth_m = mean_depth_m = water_temp_c = None
        surface_pressure_bar = salinity_g_l = None

        if computer_el is not None:
            samples = _parse_samples(computer_el)
            switches = _parse_gas_switches(computer_el, cylinders)
            computer = computer_el.get("model") or None

            if (depth_el := computer_el.find("depth")) is not None:
                max_depth_m = _opt_float(depth_el, "max", parse_depth_m)
                mean_depth_m = _opt_float(depth_el, "mean", parse_depth_m)
            if (temp_el := computer_el.find("temperature")) is not None:
                water_temp_c = _opt_float(temp_el, "water", parse_temperature_c)
            if (surface_el := computer_el.find("surface")) is not None:
                surface_pressure_bar = _opt_float(
                    surface_el, "pressure", parse_pressure_bar
                )
            if (water_el := computer_el.find("water")) is not None:
                salinity_g_l = _opt_float(
                    water_el, "salinity", lambda s: float(s.replace("g/l", "").strip())
                )

            for extra in computer_el.findall("extradata"):
                if extra.get("key") == "Deco model":
                    deco_model = extra.get("value") or None

        site_id = dive_el.get("divesiteid")
        tags = dive_el.get("tags") or ""

        return Dive(
            samples=samples,
            cylinders=cylinders,
            gas_switches=switches,
            number=_opt_int(dive_el, "number"),
            when=_parse_when(dive_el),
            site=sites.get(site_id or "", None),
            buddy=(dive_el.findtext("buddy") or "").strip() or None,
            notes=(dive_el.findtext("notes") or "").strip() or None,
            rating=_opt_int(dive_el, "rating"),
            duration_s=_opt_float(dive_el, "duration", parse_duration_s),
            max_depth_m=max_depth_m,
            mean_depth_m=mean_depth_m,
            water_temp_c=water_temp_c,
            surface_pressure_bar=surface_pressure_bar,
            salinity_g_l=salinity_g_l,
            sac_l_min=_opt_float(
                dive_el, "sac", lambda s: float(s.replace("l/min", "").strip())
            ),
            otu=_opt_float(dive_el, "otu", float),
            cns=_opt_float(dive_el, "cns", parse_percent),
            computer=computer,
            deco_model=deco_model,
            tags=tuple(t.strip() for t in tags.split(",") if t.strip()),
        )

    @classmethod
    def parse_file(cls, path: str | Path) -> DiveLog:
        path = Path(path)
        return cls.parse(path.read_text(encoding="utf-8"), source=str(path))
