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

**There are now two implementations.** The Python in `src/` remains canonical
and is where behaviour is decided. `android/` holds a Kotlin reimplementation
for a phone app, held to the Python by generated fixtures rather than by
discipline — see [The Kotlin port](#the-kotlin-port). Change behaviour in Python
first, regenerate, then follow it in Kotlin.

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
uv run pytest                               # full suite (195 tests)
uv run pytest tests/test_subsurface.py      # one file
uv run ruff check . && uv run ruff format . # lint + format (line length 88)
uv run mypy src                             # strict mode is on
uv run diveslate backends                   # what PNG rasterisers work here
```

Regenerate the fixtures the Kotlin port is held to, after any behaviour change:

```bash
uv run python tools/export_oracle.py         # parsed models, derived figures, unit spec
uv run python tools/export_theme_tokens.py   # palettes + slider ranges
uv run python tools/generate_kotlin_themes.py  # -> android/.../Themes.kt
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

Outside `src/`:

```
tools/          exporters that freeze this implementation as fixtures
conformance/    the generated fixtures — the contract the Kotlin port meets
android/        the Kotlin reimplementation and the phone app
```

`profile.py` and `overlay.py` are **siblings, not a base and a variant**. One
draws a chart you read values off; the other draws a badge. Sharing their layout
code was tried and abandoned — the constraints genuinely differ, and the
duplication is smaller than the abstraction would be.

## The Kotlin port

`android/` is an Android app that turns a Subsurface-mobile export into an
Instagram story in about three taps. Kotlin because the app shell has to be
Kotlin regardless — share intents, `FileProvider`, `ADD_TO_STORY` — and every
other choice would have meant Kotlin *plus* something.

```
android/core/   plain Kotlin/JVM: units, models, both parsers, detection,
                palettes, and the slate layout. No Android surface, so it
                builds and tests with only a JDK.
android/app/    Compose UI, the Canvas painter, intents, MediaStore export.
```

```bash
cd android && ./gradlew core:test        # 40 tests, no device needed
cd android && ./gradlew :app:installDebug
```

Needs JDK 21 on `JAVA_HOME`. The SDK lives in `%LOCALAPPDATA%\Android\Sdk`;
Android Studio is **not** required to build. See `android/README.md` for the
toolchain notes, including that winget's Android Studio package reports success
without installing anything.

### The fixtures are the contract

`conformance/` is generated from the Python and is what keeps the two honest:
full parsed models and every derived figure for each log in `tests/data`, a
table-driven spec for the unit grammar recording **rejected** input as
deliberately as accepted, the palettes as flat tokens, and synthetic deco
profiles. `tests/test_conformance.py` re-derives all of it so Python cannot
drift away unnoticed; the Kotlin tests read the same files.

**When a conformance test fails, fix the Kotlin.** Regenerating to turn a test
green discards the specification and keeps the bug.

One lesson worth keeping, because it nearly cost the deco fix: **fixtures
generated from real logs only cover what real logs happen to contain.** Every
dive in `tests/data` has a single deco span, so none of them can distinguish a
correct `deco_time_s` from one pairing the first ceiling arrival with the last
span's end. That case exists only in synthetic profiles, which is why
`specs.json` carries a `deco_cases` section. Verified by reintroducing the
defect: the synthetic case failed and every real-log test passed.

### What did not come across

The full chart (`profile.py`), SVG output, canvas placement modes, and the
entry-point plugin system. The chart is a desktop analysis artifact and the
sibling split meant the overlay could leave without it.

### Deliberate divergences — do not "restore parity"

- **The XML reader refuses any document declaring a DOCTYPE.** The desktop CLI
  reads files the user chose; the app is handed documents by other apps. The
  check is a text scan performed before any parser sees the document, because
  Android is not Xerces: the Apache hardening feature names *throw* there rather
  than harden, and `setXIncludeAware` throws outright. A feature flag would have
  been silently inert on the only platform that needs it.
- **UDDF deco stops with a missing depth yield null, not NaN.** Python produces
  NaN there, which survives its own zero-check because NaN is truthy and then
  poisons every downstream ceiling comparison. Filed separately against Python.
- **The mix figure is labelled "Gases" and joined with ", "** rather than "Gas"
  and "/". A slash reads as a ratio beside `GF 70/80`, and `Tx18/45` already
  contains one.
- **`minSdk` is 29**, for MediaStore scoped storage.

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
