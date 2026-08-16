"""Freeze the Python implementation as a conformance oracle for ports.

The renderer is being reimplemented in Kotlin for Android. The valuable thing in
this repo is not its code but the behaviour the tests pin down — sparse-sample
carry-forward, deco time as the hang, the unit grammar, the refusal to guess a
derived figure. A rewrite that reproduces the code but not those decisions
reintroduces bugs that were expensive to find the first time.

So this script writes that behaviour out as data. For every log in ``tests/data``
it dumps the fully parsed model and every derived figure; alongside it, a
table-driven spec for the pure functions, recording rejected input as
deliberately as accepted input, because a port must refuse the same strings.

Run it from the repo root::

    uv run python tools/export_oracle.py

Regenerate whenever behaviour changes on purpose, and never to make a failing
port go green — the fixtures are the specification, not a snapshot.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "src"))

from diveslate.core import units  # noqa: E402
from diveslate.core.models import (  # noqa: E402
    Cylinder,
    Dive,
    DiveLog,
    GasMix,
    GasSwitch,
    Sample,
)
from diveslate.parsers import detect  # noqa: E402

DATA = ROOT / "tests" / "data"
OUT = ROOT / "conformance"

#: Floats are rounded before serialising so the fixtures diff cleanly and a port
#: is not held to Python's last-bit arithmetic. Six places is far finer than any
#: quantity a dive computer records.
PLACES = 6

SCHEMA = 1


def num(value: float | int | None) -> float | None:
    """Round for serialisation, collapsing -0.0 so it compares equal to 0.0."""
    if value is None:
        return None
    return round(float(value), PLACES) + 0.0


# ---- model encoders -------------------------------------------------------
# Written out longhand rather than via dataclasses.asdict: the JSON shape is a
# contract a port has to match, so it should be chosen rather than inherited.


def gas_json(gas: GasMix) -> dict[str, Any]:
    return {
        "o2": num(gas.o2),
        "he": num(gas.he),
        "n2": num(gas.n2),
        "is_air": gas.is_air,
        "name": gas.name,
        "mod_m_ppo2_1_4": num(gas.mod_m()),
        "mod_m_ppo2_1_6": num(gas.mod_m(1.6)),
    }


def cylinder_json(cylinder: Cylinder) -> dict[str, Any]:
    return {
        "gas": gas_json(cylinder.gas),
        "description": cylinder.description,
        "size_l": num(cylinder.size_l),
        "work_pressure_bar": num(cylinder.work_pressure_bar),
        "start_bar": num(cylinder.start_bar),
        "end_bar": num(cylinder.end_bar),
        "used_bar": num(cylinder.used_bar),
        "used_l": num(cylinder.used_l),
        "label": cylinder.label,
    }


def sample_json(sample: Sample) -> dict[str, Any]:
    return {
        "time_s": num(sample.time_s),
        "depth_m": num(sample.depth_m),
        "temp_c": num(sample.temp_c),
        "ndl_s": num(sample.ndl_s),
        "tts_s": num(sample.tts_s),
        "in_deco": sample.in_deco,
        "stop_depth_m": num(sample.stop_depth_m),
        "stop_time_s": num(sample.stop_time_s),
        "cns": num(sample.cns),
        "pressure_bar": num(sample.pressure_bar),
    }


def switch_json(switch: GasSwitch) -> dict[str, Any]:
    return {
        "time_s": num(switch.time_s),
        "gas": gas_json(switch.gas),
        "cylinder_index": switch.cylinder_index,
    }


def derived_json(dive: Dive) -> dict[str, Any]:
    """Every figure the renderer reads off a dive rather than out of the log."""
    temps = dive.temperature_range_c
    duration = dive.computed_duration_s
    max_depth = dive.computed_max_depth_m

    # gas_at is a function of time, so probe it: at each switch, on either side
    # of each switch, and across the profile. Before the first switch it is
    # None, and a port that defaults it to air instead will diverge here.
    probes = {0.0, duration}
    for fraction in (0.25, 0.5, 0.75):
        probes.add(round(duration * fraction, PLACES))
    for switch in dive.gas_switches:
        probes.update({switch.time_s - 1.0, switch.time_s, switch.time_s + 1.0})

    gradient = dive.gradient_factors

    return {
        "computed_max_depth_m": num(max_depth),
        "computed_duration_s": num(duration),
        "computed_mean_depth_m": num(dive.computed_mean_depth_m),
        "temperature_range_c": None if temps is None else [num(temps[0]), num(temps[1])],
        "deco_spans": [
            {
                "start_s": num(span.start_s),
                "end_s": num(span.end_s),
                "duration_s": num(span.duration_s),
            }
            for span in dive.deco_spans()
        ],
        "deco_time_s": num(dive.deco_time_s()),
        "deco_time_s_tolerance_0": num(dive.deco_time_s(tolerance_m=0.0)),
        "gradient_factors": None if gradient is None else [gradient[0], gradient[1]],
        "gas_used_l": num(dive.gas_used_l),
        "gas_used_by_cylinder": [
            {"label": label, "litres": num(litres)}
            for label, litres in dive.gas_used_by_cylinder
        ],
        "gas_at": [
            {
                "time_s": num(t),
                "mix": None if (mix := dive.gas_at(t)) is None else mix.name,
            }
            for t in sorted(probes)
        ],
        "title": dive.title,
        # The slate's headline figures, after the round-up rules. These are the
        # numbers a reader actually sees, and the epsilon in ceil_minutes means
        # an exactly-round duration must not tip into the next minute.
        "slate": {
            "max_depth_ceil_m": units.ceil_metres(max_depth),
            "duration_ceil_min": units.ceil_minutes(duration),
            "duration_formatted": list(units.format_minutes(duration)),
            "duration_clock": units.format_duration(duration),
        },
    }


def dive_json(dive: Dive) -> dict[str, Any]:
    return {
        "number": dive.number,
        "when": None if dive.when is None else dive.when.isoformat(),
        "site": dive.site,
        "buddy": dive.buddy,
        "notes": dive.notes,
        "rating": dive.rating,
        "duration_s": num(dive.duration_s),
        "max_depth_m": num(dive.max_depth_m),
        "mean_depth_m": num(dive.mean_depth_m),
        "water_temp_c": num(dive.water_temp_c),
        "surface_pressure_bar": num(dive.surface_pressure_bar),
        "salinity_g_l": num(dive.salinity_g_l),
        "sac_l_min": num(dive.sac_l_min),
        "otu": num(dive.otu),
        "cns": num(dive.cns),
        "computer": dive.computer,
        "deco_model": dive.deco_model,
        "tags": list(dive.tags),
        "cylinders": [cylinder_json(c) for c in dive.cylinders],
        "gas_switches": [switch_json(s) for s in dive.gas_switches],
        "sample_count": len(dive.samples),
        # The whole series, expanded. For Subsurface this is the carry-forward
        # proof: the log writes an attribute only when it changes, so a port
        # that treats an absent attribute as unknown produces nulls here.
        "samples": [sample_json(s) for s in dive.samples],
        "derived": derived_json(dive),
    }


def log_json(log: DiveLog, source: str) -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "source": source,
        "program": log.program,
        "dive_count": len(log.dives),
        "sites": dict(sorted(log.sites.items())),
        "dives": [dive_json(d) for d in log.dives],
    }


# ---- table-driven specs for the pure functions ----------------------------


def probe(fn: Callable[[str], float], raw: str) -> dict[str, Any]:
    """Record what ``fn`` does with ``raw`` — including refusing it.

    Rejection is as much a part of the contract as conversion. The unit parsers
    refuse input they do not recognise rather than assuming metric, so a port
    that silently accepts ``'30 fathoms'`` as 30 m is wrong in a way no
    accepted-input fixture would catch.
    """
    try:
        return {"in": raw, "out": num(fn(raw))}
    except units.UnitError:
        return {"in": raw, "error": "UnitError"}


UNIT_CASES: dict[str, tuple[Callable[[str], float], tuple[str, ...]]] = {
    "parse_depth_m": (
        units.parse_depth_m,
        ("44.4 m", "44.4m", "44.4", "0", "-1.2", "+3", ".5", "30 metres",
         "100 ft", "100ft", "100 feet", "1 foot", "30 fathoms", "", "abc"),
    ),
    "parse_duration_s": (
        units.parse_duration_s,
        # The colon form is mm:ss, so '44:20 min' is 44 min 20 s, not 44.2 min —
        # the trailing label names the leading field and is not a scale factor.
        ("62:18 min", "62:18", "1:02:03", "0:00", "44:20 min", "90 s", "90 sec",
         "3 min", "45", "1.5 h", "2 hours", "1:2:3:4", "5 fortnights", ""),
    ),
    "parse_pressure_bar": (
        units.parse_pressure_bar,
        ("200.0 bar", "200", "0", "2900 psi", "2900psi", "200 atm", ""),
    ),
    "parse_temperature_c": (
        units.parse_temperature_c,
        ("28 C", "28", "0", "-2.5", "82.4 F", "301.15 K", "28 °C",
         "28 celsius", "28 R", ""),
    ),
    "parse_volume_l": (
        units.parse_volume_l,
        ("12 l", "12", "11.1 litres", "80 cuft", "80 cf", "80 ft3", "12 gal", ""),
    ),
    "parse_percent": (
        units.parse_percent,
        ("31%", "31", "0%", "100%", "21.0%", "31 pct", ""),
    ),
}

#: Seconds chosen around the boundaries where the round-up rule bites: an
#: exactly-round hour must stay 60 min, and 64:20 must read as 65.
ROUNDING_SECONDS = (0.0, 1.0, 59.0, 60.0, 61.0, 600.0, 3540.0, 3599.0, 3600.0,
                    3601.0, 3860.0, 7200.0)

ROUNDING_METRES = (0.0, 0.1, 1.0, 29.999999, 30.0, 44.0, 44.4, 45.0)

#: Gradient factors are recovered from a free-text label by pattern, then
#: validated. The invalid cases matter most: a VPM-B dive has none, and a
#: version string or date must not be mistaken for a pair of percentages.
DECO_MODELS = (
    "GF 70/80",
    "ZHL16C GF30/85",
    "Buhlmann ZH-L16C + GF 30/85",
    "GF30/85",
    "GF 100/100",
    "VPM-B",
    "VPM-B/E",
    "ZHL16C",
    "GF 0/85",
    "GF 90/30",
    "GF 101/110",
    "revision 12/2020",
    "",
)


def specs_json() -> dict[str, Any]:
    return {
        "schema": SCHEMA,
        "units": {
            name: [probe(fn, raw) for raw in cases]
            for name, (fn, cases) in UNIT_CASES.items()
        },
        "ceil_minutes": [
            {"in": num(s), "out": units.ceil_minutes(s)} for s in ROUNDING_SECONDS
        ],
        "ceil_metres": [
            {"in": num(m), "out": units.ceil_metres(m)} for m in ROUNDING_METRES
        ],
        "format_minutes": [
            {"in": num(s), "out": list(units.format_minutes(s))}
            for s in ROUNDING_SECONDS
        ],
        "format_duration": [
            {"in": num(s), "out": units.format_duration(s)} for s in ROUNDING_SECONDS
        ],
        "gradient_factors": [
            {
                "deco_model": model,
                "out": (
                    None
                    if (gf := Dive(deco_model=model or None).gradient_factors) is None
                    else list(gf)
                ),
            }
            for model in DECO_MODELS
        ],
        "gas_names": [
            {"o2": num(o2), "he": num(he), "name": GasMix(o2=o2, he=he).name}
            for o2, he in (
                (0.21, 0.0), (0.209, 0.0), (0.32, 0.0), (0.36, 0.0),
                (1.0, 0.0), (0.99, 0.0), (0.18, 0.45), (0.1, 0.7), (0.5, 0.2),
            )
        ],
    }


def write(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
    path.write_text(text, encoding="utf-8", newline="\n")
    print(f"  {path.relative_to(ROOT)}  ({len(text):,} bytes)")


def main() -> int:
    logs = sorted(p for p in DATA.iterdir() if p.suffix in {".ssrf", ".uddf"})
    if not logs:
        print(f"no logs found in {DATA}", file=sys.stderr)
        return 1

    print(f"oracle -> {OUT.relative_to(ROOT)}")
    for path in logs:
        log = detect.parse_file(path)
        write(OUT / "logs" / f"{path.stem}.{path.suffix.lstrip('.')}.json",
              log_json(log, path.name))

    write(OUT / "specs.json", specs_json())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
