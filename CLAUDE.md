# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`diveslate` reads a Subsurface (`.ssrf`) or UDDF (`.uddf`) dive log and renders
the profile as a **transparent image** — SVG always, PNG through an optional
rasteriser. Two outputs: a compact *overlay slate* for Instagram posts and
stories, and a full *chart*. It is the rendering half of the user's diving stack;
[Dive-Plan](https://github.com/paul-charp/Dive-Plan) is the planning half and its
`subsurface` formatter emits logs this renders. Experimental software; never
soften the README's "do not use for real dives" caution.

Python ≥ 3.14, [uv](https://docs.astral.sh/uv/), `src/` layout — same house style
as Dive-Plan.

## Token-efficient navigation

`.claude/codebase-index.md` is a generated map of every module with class/function
signatures and docstring one-liners. **Read it first instead of source files**
when you need to know what exists or where it lives. Only read actual source
before editing it. Regenerate after changing public APIs:

```bash
uv run python .claude/generate-index.py
```

## Commands

```bash
uv sync                                     # venv + dev deps
uv run pytest                               # full suite (185 tests)
uv run pytest tests/test_subsurface.py      # one file
uv run ruff check . && uv run ruff format . # lint + format (line length 88)
uv run mypy src                             # strict mode is on
uv run diveslate backends                   # what PNG rasterisers work here
```

Line endings are LF, enforced via `.gitattributes`.

## Architecture

Imports flow strictly downward; `core/` never imports from upper layers.

```
core/units.py      quantity-string parsing → canonical units; ceiling rounding
core/models.py     Dive, Sample, GasMix, Cylinder, GasSwitch, DiveLog (frozen, slots)
parsers/           subsurface.py, uddf.py, detect.py (content sniffing), base.py
render/palette.py  OKLab/OKLCH maths, CVD simulation, the palette gates
render/theme.py    Theme tokens; hand-built SLATE/LIGHT + seven generated presets
render/layout.py   Scale, Margins, Layout, tick selection        ─┐ chart only
render/profile.py  the full chart                                ─┘
render/overlay.py  the compact slate (wide + tall layouts)
render/svg.py      dependency-free SVG writer
render/raster.py   pluggable SVG→PNG backends
registry.py        entry-point discovery (diveslate.parsers, diveslate.themes)
cli.py             argparse CLI: render, overlay, info, backends
```

`profile.py` and `overlay.py` are **siblings, not a base and a variant**. One
draws a chart you read values off; the other draws a badge. Sharing their layout
code was tried and abandoned — the constraints genuinely differ, and the
duplication is smaller than the abstraction would be.

## Six things that are easy to break

### 1. Subsurface samples are sparse

Subsurface writes a sample attribute **only when it changes**. A line carrying
just a time and depth means "everything else is as it was", not "everything else
is unknown". `_parse_samples` carries every optional field forward, exactly as
Subsurface's own reader does. Break this and a 50-minute deco dive parses as one
deco sample followed by nothing. `tests/test_subsurface.py::TestCarryForward`
guards it — those tests are the specification, not incidental coverage.

UDDF is the opposite: waypoints are self-contained and nothing carries forward
except the breathing mix.

### 2. Deco time is the hang, not the obligation

`Dive.deco_time_s()` measures from first reaching the ceiling on the way up until
the obligation clears. `Dive.deco_spans()` measures when deco was *owed*, which
starts while the diver is still on the bottom. On the reference dive these are
23:20 and 50:06. Reporting the second as "deco" claims fifty minutes of stops
that never happened — that bug shipped once and was caught by the user.

A dive that clears deco and re-incurs it served **two** hangs. Each span is
measured against the end of its own obligation and the hangs summed, so the
cleared interval between them is never counted. A span whose ceiling was never
reached contributes nothing rather than voiding the hangs that were served —
surfacing in deco after an earlier stop still reports that stop.

### 3. The colour palettes are computed, not chosen

`render/palette.py` implements the data-viz gates — OKLab ΔE, Machado CVD
simulation at severity 1.0, chroma floor, lightness band, WCAG contrast — and
`build_theme` **raises** rather than returning a palette that fails them.

Two findings that are not obvious and cost real debugging:

- **Only base hues ≈180–330° work.** Warm and green bases collide with the fixed
  deco-ceiling red under CVD. Green measures ΔE 24 against red to normal vision
  and **2.2** under protanopia.
- **The sRGB gamut is not a cylinder.** Asking for a fixed chroma across hues
  silently desaturates cyan below the chroma floor. `best_in_band` sweeps for the
  lightness where a hue holds the most chroma.

The ceiling red (`theme.CEILING`) is deliberately **not** themed: a hazard colour
that shifts with the palette stops reading as a hazard.

Where a mark sits below 3:1 contrast, it is legal **only** because a text label
carries the identity — gas switches always print the mix name. Don't drop those
labels to reduce clutter.

### 4. Transparency is the product

No background rect is ever emitted, and rasteriser backends are configured for a
transparent canvas explicitly. Because the backdrop is unknown at render time,
all text is painted twice — a halo stroke under the fill (`render/svg.py:text`) —
and the overlay adds a scrim panel, since halos alone are not enough over video
where the frame behind a label changes constantly.

### 5. skia is not a rasteriser backend

`skia-python` installs cleanly, is fast, and renders **none** of the SVG text —
its `SVGDOM` exposes no font-manager hook, so the output is a clean-looking chart
silently missing its title, labels and stats. It was removed deliberately and
must never be added to the fallback chain. `available_backends()` proves each
candidate on a real render rather than trusting `find_spec`, because cairosvg
imports fine and then fails on a missing shared library.

### 6. Derived figures must degrade to nothing, never to a guess

`gradient_factors` matches a pattern in a free-text label and validates the
result (1–100, low ≤ high) — a VPM-B dive has no GFs and must not appear to have
them. `gas_used_l` needs size *and* both pressures, and drops a cylinder that
came back fuller rather than subtracting it. `deco_time_s` returns `None` when
the ceiling was never reached.

## Conventions

- Optional log fields are `None`, never a sentinel zero — a dive with no recorded
  temperature must not render as a dive at 0 °C.
- **Never add a second y-axis.** Temperature, pressure and consumption go in the
  summary; depth is the only thing mapped to vertical space.
- The depth axis always starts at the surface.
- Slate figures round **up** — 44.4 m is a 45 m dive (`ceil_metres`,
  `ceil_minutes`). The chart keeps decimals, because its axis would contradict a
  rounded headline.
- New log formats plug in via the `diveslate.parsers` entry-point group;
  detection is by content, not file extension.

## History worth knowing

A **gas ribbon** (a segmented bar under the profile showing which mix was
breathed when) was built and then removed at the user's request. If it is ever
wanted back, the design that worked was a lightness ramp of one hue ordered by O2
fraction, with every segment labelled — categorical hues are not available,
because the usable band is too narrow to hold several that clear the gates
against the curve, the ceiling and each other.
