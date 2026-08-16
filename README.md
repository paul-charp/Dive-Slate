# Dive-Slate

Turn a dive log into a transparent image of the profile — a compact slate to
drop over a photo or video, or a full chart.

Point it at a **Subsurface** (`.ssrf`) or **UDDF** (`.uddf`) log and it draws the
depth-vs-time curve with the things a diver actually wants: max depth, runtime,
decompression, gradient factors, gas. The background is transparent, so the
result drops straight onto an Instagram post, a story, a log-book page or a
video overlay.

```bash
diveslate overlay divetest.ssrf -o slate.png            # the badge
diveslate render  divetest.ssrf -o profile.png          # the full chart
```

> [!WARNING]
> **Experimental software. Do not use it for real dives.** Dive-Slate draws what
> a log file already contains — it does not plan, validate or verify anything.
> Nothing it renders should inform a decision in the water.

## Install

```bash
pip install diveslate
```

The core renders **SVG using the standard library alone** — no native
dependencies, nothing to compile. PNG output needs a rasteriser:

```bash
pip install "diveslate[png]"
```

That pulls in [resvg](https://github.com/linebender/resvg), which ships
self-contained wheels on Windows, macOS and Linux and renders SVG text properly.
If you already have a system cairo, `diveslate[cairo]` works too. To see what
your machine can actually do:

```bash
diveslate backends
```

## The two outputs

### `overlay` — a badge for a post or a story

A profile silhouette, the site name, and a few big numbers. No axes, no legend:
at a third of frame width on a phone those are unreadable noise rather than
information.

```bash
diveslate overlay log.ssrf -o slate.png                       # tight-cropped, 1080×468
diveslate overlay log.ssrf -o story.png --canvas story        # placed on a 9:16 frame
diveslate overlay log.ssrf -o post.png  --canvas portrait --position bottom-center
diveslate overlay log.ssrf -o slate.png --theme reef --max-stats 4
```

Without `--canvas` you get the slate at its own size, to position yourself in an
editor. With it, the slate is placed on a full Instagram frame ready to drop
straight on.

| Option | Default | |
|---|---|---|
| `--canvas` | *off* | `square` 1080², `portrait` 1080×1350, `story` 1080×1920, `landscape` |
| `--position` | `bottom-left` | four corners, `top-center`, `bottom-center`, `center` |
| `--layout` | auto | `wide` badge or `tall` story card — `tall` is automatic for `--canvas story` |
| `--width` | `1080` | slate width; ignored with `--canvas` |
| `--theme` | `slate` | see [Themes](#themes) |
| `--stats` | auto | see [Summary values](#summary-values) |
| `--max-stats` | `3` | how many values to show |
| `--gas` | off | mark and label gas switches on the curve |
| `--date` | off | add the dive date under the site name |
| `--no-scrim` | | drop the backdrop panel |
| `--no-site` `--no-ceiling` `--no-deco` | | drop those pieces |

A 9:16 story gets the **tall** layout automatically: the wide badge is only a
quarter of that frame's height and reads as a small band adrift in it. Tall
roughly doubles the profile height and enlarges every type size.

### `render` — the full chart

Axes, grid, legend, gas-switch markers, a stepped deco ceiling and a summary
strip. Defaults to 1600×900.

```bash
diveslate render log.ssrf -o profile.svg
diveslate render log.ssrf -o profile.png --theme abyss --width 2400 --height 1350
diveslate render log.ssrf -o bare.png --no-grid --no-legend --no-stats
```

`--no-title`, `--no-axes`, `--no-grid`, `--no-ceiling`, `--no-gas`, `--no-stats`,
`--no-legend`, `--no-deco` each drop one layer.

## Summary values

`--stats depth,time,gf,used` picks values explicitly, in order. Left to itself
the slate shows max depth, runtime, then whichever of these the log can answer:

| key | shows | needs |
|---|---|---|
| `depth` | max depth, rounded up to the metre | samples |
| `time` | runtime, rounded up to the minute | samples |
| `deco` | time spent decompressing | a recorded ceiling |
| `gf` | gradient factors, e.g. `70/80` | a deco-model label containing them |
| `used` | gas consumed, litres | cylinder size + start and end pressure |
| `temp` | minimum water temperature | temperature samples |
| `sac` | surface air consumption | the log's own SAC field |
| `cns` | CNS toxicity percentage | the log's own CNS field |
| `avg` | average depth | samples or the logged mean |
| `gas` | mixes breathed, e.g. `Air/O2` | gas-switch events |

A value the log cannot supply is skipped rather than shown blank.

### Two of these are derived, not read

**Deco time** is not a field in any log. It is computed as *from first reaching
the ceiling on the way up, until the obligation clears* — the hang. This is
deliberately not the same as the span during which deco was owed, which begins
the moment the ceiling leaves the surface, usually while you are still on the
bottom. On the sample dive those are 23 minutes and 50 minutes respectively, and
reporting the latter as "deco" would claim fifty minutes of stops that never
happened. `Dive.deco_spans()` still exposes the obligation span; `--no-deco`
drops the figure entirely.

**Gradient factors** are recovered by pattern from a free-text deco-model label
(`GF 70/80`, `ZHL16C GF30/85`, `Buhlmann ZH-L16C + GF 30/85`). Anything that is
not a valid pair of percentages yields nothing rather than a guess — a VPM-B dive
has no gradient factors and must not appear to.

## Themes

Nine, generated and machine-checked rather than chosen:

| for dark footage | for pale backgrounds |
|---|---|
| `slate` `reef` `lagoon` `abyss` `twilight` `orchid` | `light` `paper` `ink` |

Each is derived from one base hue: that becomes the depth curve, and the
gas-switch accent is then found by searching the hue circle for the colour that
separates best from both the curve and the deco-ceiling red. Every palette is
validated for colour-vision-deficiency separation, chroma, lightness and contrast
before it ships (`diveslate.render.palette`).

Build your own:

```python
from diveslate.render.theme import build_theme, validate_theme

theme = build_theme("house", "#1f6fb2", mode="dark")
print(validate_theme(theme))
```

`build_theme` **refuses** a base colour that cannot make a valid palette. In
practice only hues from roughly **180° to 330°** work — cyan through blue, violet
and magenta. Warm and green bases collide with the fixed red ceiling: a green
curve looks maximally different from red to normal vision (ΔE 24) but measures
2.2 under protanopia. Pass `strict=False` to override, and read the report.

## Library

```python
from diveslate import parse_file
from diveslate.render import render_svg, render_png, render_overlay, render_overlay_png

dive = parse_file("log.ssrf").only()

print(dive.computed_max_depth_m, dive.deco_time_s(), dive.gradient_factors)
print(dive.gas_used_by_cylinder)

open("profile.svg", "w").write(render_svg(dive))
render_png(dive, "profile.png", width=2400, height=1350)
render_overlay_png(dive, "story.png", canvas="story", position="center")
```

## Formats

| Format | Extensions | Notes |
| --- | --- | --- |
| Subsurface XML | `.ssrf`, `.xml` | sparse sample attributes are carried forward, as Subsurface itself does |
| UDDF 3.x | `.uddf`, `.xml` | SI units, namespace-agnostic; mandatory deco stops only |

Detection reads file content, not the extension, so a renamed log still works.
New formats plug in through the `diveslate.parsers` entry-point group.

```bash
diveslate info log.ssrf     # what's in the file
```

## Related

- [Dive-Plan](https://github.com/paul-charp/Dive-Plan) — the planning and
  decompression half of the stack. Its `subsurface` formatter writes logs
  Dive-Slate can render, so you can draw a plan the same way you draw a dive.

## Development

```bash
uv sync
uv run pytest
uv run ruff check . && uv run ruff format .
uv run mypy src
uv run python .claude/generate-index.py   # refresh the API map
```

`.claude/codebase-index.md` is a generated map of every module and signature —
start there rather than reading source when you only need to know what exists.

## License

MIT
