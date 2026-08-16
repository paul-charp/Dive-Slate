# diveslate codebase index

Auto-generated API map (regenerate: `uv run python .claude/generate-index.py`). Generated 2026-08-16.

Read this instead of source files when you only need signatures/structure. Read the actual source before *editing* anything listed here.

## `src/diveslate/__init__.py`
Render a Subsurface or UDDF dive log into a single transparent image.
`__all__ = ['AIR', 'Cylinder', 'DecoSpan', 'Dive', 'DiveLog', 'GasMix', 'GasSwitch', 'ParseError', 'Sample', '__version__', 'parse_file', 'parse_text', 'sniff']`
- `__getattr__(name: str) -> object`

## `src/diveslate/cli.py`
``diveslate`` command line interface.
`__all__ = ['main']`
- `_echo(message: str = '') -> None`  — Print, surviving consoles that cannot encode the character set.
- `_select_dive(log: DiveLog, index: int | None) -> Dive`
- `_cmd_render(args: argparse.Namespace) -> int`
- `_cmd_overlay(args: argparse.Namespace) -> int`
- `_svg_dimensions(svg: str) -> tuple[int, int]`
- `_cmd_info(args: argparse.Namespace) -> int`
- `_cmd_backends(_: argparse.Namespace) -> int`
- `build_parser() -> argparse.ArgumentParser`
- `main(argv: list[str] | None = None) -> int`

## `src/diveslate/core/__init__.py`
Format-neutral dive types and quantity parsing.
`__all__ = ['AIR', 'Cylinder', 'DecoSpan', 'Dive', 'DiveLog', 'GasMix', 'GasSwitch', 'Sample']`

## `src/diveslate/core/models.py`
The format-neutral dive model every parser produces and the renderer consumes.
`__all__ = ['AIR', 'DECO_STOP_TOLERANCE_M', 'Cylinder', 'DecoSpan', 'Dive', 'DiveLog', 'GasMix', 'GasSwitch', 'Sample']`
- const `DECO_STOP_TOLERANCE_M = 3.0`
### class `GasMix` — A breathing mix, stored as fractions of 1.
  - @property `n2(self) -> float`
  - @property `is_air(self) -> bool`
  - @property `name(self) -> str`  — Short label divers actually use: ``Air``, ``EAN32``, ``Tx18/45``, ``O2``.
  - `mod_m(self, ppo2_max: float = 1.4) -> float`  — Maximum operating depth in metres at ``ppo2_max``, in salt water.
  attrs: `o2: float = 0.21; he: float = 0.0`
- const `AIR = GasMix()`
### class `Cylinder` — A cylinder carried on the dive.
  - @property `used_bar(self) -> float | None`
  - @property `used_l(self) -> float | None`  — Free-gas volume consumed, litres at surface pressure.
  - @property `label(self) -> str`
  attrs: `gas: GasMix = AIR; description: str | None = None; size_l: float | None = None; work_pressure_bar: float | None = None; start_bar: float | None = None; end_bar: float | None = None`
### class `Sample` — One instant on the profile.
  attrs: `time_s: float; depth_m: float; temp_c: float | None = None; ndl_s: float | None = None; tts_s: float | None = None; in_deco: bool = False; stop_depth_m: float | None = None; stop_time_s: float | None = None; cns: float | None = None; pressure_bar: float | None = None`
### class `GasSwitch` — A change of breathing gas at a point in time.
  attrs: `time_s: float; gas: GasMix; cylinder_index: int | None = None`
### class `DecoSpan` — A contiguous stretch of the dive spent in decompression obligation.
  - @property `duration_s(self) -> float`
  attrs: `start_s: float; end_s: float`
### class `Dive` — A single dive: metadata, gas, and the sampled profile.
  - @property `computed_max_depth_m(self) -> float`  — Deepest sampled depth, falling back to the logged summary value.
  - @property `computed_duration_s(self) -> float`  — Profile length, falling back to the logged summary value.
  - @property `computed_mean_depth_m(self) -> float | None`  — Time-weighted average depth over the sampled profile.
  - @property `temperature_range_c(self) -> tuple[float, float] | None`
  - `deco_spans(self) -> tuple[DecoSpan, ...]`  — Contiguous stretches where the computer showed a deco obligation.
  - `deco_time_s(self, tolerance_m: float = DECO_STOP_TOLERANCE_M) -> float | None`  — Time actually spent decompressing, or ``None`` on a no-stop dive.
  - @property `gradient_factors(self) -> tuple[int, int] | None`  — ``(low, high)`` gradient factors, read out of the deco model string.
  - @property `gas_used_l(self) -> float | None`  — Total free gas consumed across every cylinder, litres at the surface.
  - @property `gas_used_by_cylinder(self) -> tuple[tuple[str, float], ...]`  — ``(label, litres)`` per cylinder that recorded enough to compute it.
  - `gas_at(self, time_s: float) -> GasMix | None`  — The mix being breathed at ``time_s``, or ``None`` before the first switch.
  - @property `title(self) -> str`  — A human label for the dive, best-effort from whatever the log carries.
  attrs: `samples: tuple[Sample, ...] = (); cylinders: tuple[Cylinder, ...] = (); gas_switches: tuple[GasSwitch, ...] = (); number: int | None = None; when: datetime | None = None; site: str | None = None; buddy: str | None = None; notes: str | None = None; rating: int | None = None; duration_s: float | None = None; max_depth_m: float | None = None; mean_depth_m: float | None = None; water_temp_c: float | None = None; surface_pressure_bar: float | None = None; salinity_g_l: float | None = None; sac_l_min: float | None = None; otu: float | None = None; cns: float | None = None; computer: str | None = None; deco_model: str | None = None; tags: tuple[str, ...] = ()`
### class `DiveLog` — A parsed log file: one or more dives plus the program that wrote it.
  - `only(self) -> Dive`  — The single dive in this log, erroring if the count is not exactly one.
  - @classmethod `of(cls, dive: Dive, **kwargs) -> Self`
  attrs: `dives: tuple[Dive, ...] = (); program: str | None = None; source: str | None = None; sites: dict[str, str] = field(default_factory=dict)`
  dunders: `__getitem__, __iter__, __len__`

## `src/diveslate/core/units.py`
Parsers for the quantity strings dive logs write into XML attributes.
`__all__ = ['UnitError', 'ceil_metres', 'ceil_minutes', 'format_duration', 'format_minutes', 'parse_depth_m', 'parse_duration_s', 'parse_percent', 'parse_pressure_bar', 'parse_temperature_c', 'parse_volume_l']`
### class `UnitError` (ValueError) — Raised when a quantity string cannot be understood.
- `_split(raw: str, what: str) -> tuple[float, str]`
- `parse_depth_m(raw: str) -> float`  — Parse a depth/length into metres. Accepts ``m``, ``ft``, or no unit.
- `parse_duration_s(raw: str) -> float`  — Parse a duration into seconds.
- `parse_pressure_bar(raw: str) -> float`  — Parse a gas pressure into bar. Accepts ``bar``, ``psi``, or no unit.
- `parse_temperature_c(raw: str) -> float`  — Parse a temperature into degrees Celsius. Accepts ``C``, ``F``, ``K``.
- `parse_volume_l(raw: str) -> float`  — Parse a cylinder volume into litres. Accepts ``l``, ``cuft``, or none.
- `parse_percent(raw: str) -> float`  — Parse a percentage into a fraction of 1. ``'31%'`` becomes ``0.31``.
- `ceil_minutes(seconds: float) -> int`  — Whole minutes, always rounded up.
- `ceil_metres(metres: float) -> int`  — Whole metres, always rounded up. 44.4 m becomes 45 m.
- `format_minutes(seconds: float) -> tuple[str, str]`  — ``(value, unit)`` for a duration rounded up to the minute.
- `format_duration(seconds: float) -> str`  — Render seconds as ``m:ss``, or ``h:mm:ss`` past an hour — for labels.

## `src/diveslate/parsers/__init__.py`
Dive log readers, and format detection across them.
`__all__ = ['ParseError', 'Parser', 'SubsurfaceParser', 'UddfParser', 'parse_file', 'parse_text', 'sniff']`

## `src/diveslate/parsers/base.py`
The contract every log parser implements.
`__all__ = ['ParseError', 'Parser']`
### class `ParseError` (ValueError) — Raised when a file is the right format but its content is unusable.
### class `Parser` (Protocol) — A dive log reader.
  - @classmethod `sniff(cls, text: str) -> bool`  — Whether ``text`` (the head of a file) looks like this format.
  - @classmethod `parse(cls, text: str, *, source: str | None = None) -> DiveLog`  — Parse whole-file ``text`` into a :class:`DiveLog`.
  - @classmethod `parse_file(cls, path: str | Path) -> DiveLog`  — Parse the file at ``path``.
  attrs: `extensions: ClassVar[tuple[str, ...]]; format_name: ClassVar[str]`

## `src/diveslate/parsers/detect.py`
Pick the right parser for a file.
`__all__ = ['parse_file', 'parse_text', 'sniff']`
- `sniff(text: str, *, hint: str | None = None) -> Any`  — Return the parser class that claims ``text``.
- `parse_text(text: str, *, hint: str | None = None, source: str | None = None) -> DiveLog`  — Detect the format of ``text`` and parse it.
- `parse_file(path: str | Path) -> DiveLog`  — Detect the format of the file at ``path`` and parse it.

## `src/diveslate/parsers/subsurface.py`
Reader for Subsurface's native log format (``.ssrf``, sometimes ``.xml``).
`__all__ = ['SubsurfaceParser']`
- `_opt(element: ET.Element, name: str, convert: object) -> object`  — Apply ``convert`` to attribute ``name``, or return ``None`` if absent.
- `_opt_float(element: ET.Element, name: str, convert: object) -> float | None`
- `_opt_int(element: ET.Element, name: str) -> int | None`
- `_gas_from(element: ET.Element) -> GasMix`  — Read ``o2``/``he`` percentage attributes, defaulting to air.
- `_parse_cylinders(dive_el: ET.Element) -> tuple[Cylinder, ...]`
- `_parse_gas_switches(computer_el: ET.Element, cylinders: tuple[Cylinder, ...]) -> tuple[GasSwitch, ...]`  — Collect ``gaschange`` events into an ordered switch list.
- `_parse_samples(computer_el: ET.Element) -> tuple[Sample, ...]`  — Expand Subsurface's sparse sample lines into a fully populated series.
- `_parse_when(dive_el: ET.Element) -> datetime | None`
- `_pick_computer(dive_el: ET.Element) -> ET.Element | None`  — Choose the divecomputer to plot.
### class `SubsurfaceParser` — Parses Subsurface's ``<divelog>`` XML.
  - @classmethod `sniff(cls, text: str) -> bool`
  - @classmethod `parse(cls, text: str, *, source: str | None = None) -> DiveLog`
  - @classmethod `_parse_dive(cls, dive_el: ET.Element, sites: dict[str, str]) -> Dive`
  - @classmethod `parse_file(cls, path: str | Path) -> DiveLog`
  attrs: `extensions: ClassVar[tuple[str, ...]] = ('.ssrf', '.xml'); format_name: ClassVar[str] = 'Subsurface XML'`

## `src/diveslate/parsers/uddf.py`
Reader for UDDF (Universal Dive Data Format) 3.x logs.
`__all__ = ['UddfParser']`
- `_local(tag: str) -> str`  — The tag name without its ``{namespace}`` prefix.
- `_find(element: ET.Element, *path) -> ET.Element | None`  — Namespace-agnostic descent through direct children.
- `_findall(element: ET.Element, name: str) -> list[ET.Element]`
- `_iterfind(element: ET.Element, name: str) -> list[ET.Element]`  — All descendants with local tag ``name``, at any depth.
- `_text_float(element: ET.Element | None, *path) -> float | None`
- `_fraction(value: float | None) -> float | None`  — Normalise a gas fraction that may have been written as a percentage.
- `_parse_mixes(root: ET.Element) -> dict[str, GasMix]`  — Build the ``id`` → mix table that ``<switchmix ref=...>`` points into.
- `_parse_waypoints(samples_el: ET.Element, mixes: dict[str, GasMix]) -> tuple[tuple[Sample, ...], tuple[GasSwitch, ...]]`
- `_parse_when(dive_el: ET.Element) -> datetime | None`
### class `UddfParser` — Parses UDDF 3.x ``<uddf>`` documents.
  - @classmethod `sniff(cls, text: str) -> bool`
  - @classmethod `parse(cls, text: str, *, source: str | None = None) -> DiveLog`
  - @classmethod `_parse_dive(cls, dive_el: ET.Element, mixes: dict[str, GasMix]) -> Dive`
  - @classmethod `parse_file(cls, path: str | Path) -> DiveLog`
  attrs: `extensions: ClassVar[tuple[str, ...]] = ('.uddf', '.xml'); format_name: ClassVar[str] = 'UDDF'`

## `src/diveslate/registry.py`
Entry-point discovery for parsers and themes.
`__all__ = ['PARSER_GROUP', 'THEME_GROUP', 'available_parsers', 'available_themes', 'load_parser', 'load_theme']`
- const `PARSER_GROUP = 'diveslate.parsers'`
- const `THEME_GROUP = 'diveslate.themes'`
- `_entries(group: str) -> dict[str, EntryPoint]`
- `available_parsers() -> tuple[str, ...]`  — Names of every registered parser, whether or not it imports cleanly.
- `available_themes() -> tuple[str, ...]`
- `_load(group: str, name: str, what: str) -> Any`
- `load_parser(name: str) -> Any`  — Load one parser by entry-point name.
- `load_theme(name: str) -> Any`  — Load one theme by entry-point name.
- `iter_parsers() -> list[Any]`  — Every parser that imports successfully, broken plugins skipped.

## `src/diveslate/render/__init__.py`
Rendering: SVG generation and optional PNG rasterisation.
`__all__ = ['CANVAS_SIZES', 'LIGHT', 'SLATE', 'THEMES', 'OverlayOptions', 'RasterError', 'RenderOptions', 'Theme', 'available_backends', 'get_theme', 'render_overlay', 'render_overlay_canvas', 'render_overlay_png', 'render_png', 'render_svg', 'svg_to_png', 'write_png']`
- `render_png(dive: Dive, path: str | Path, *, options: RenderOptions | None = None, backend: str | None = None, **overrides) -> Path`  — Render ``dive`` straight to a transparent PNG at ``path``.
- `render_overlay_png(dive: Dive, path: str | Path, *, canvas: str | None = None, position: str = 'bottom-left', options: OverlayOptions | None = None, backend: str | None = None, **overrides) -> Path`  — Render the compact overlay slate to a transparent PNG at ``path``.
- `_svg_dimensions(svg: str) -> tuple[int, int]`

## `src/diveslate/render/layout.py`
Plot geometry: margins, scales, and tick selection.
`__all__ = ['Layout', 'Margins', 'Scale', 'nice_step']`
- `nice_step(span: float, target_ticks: int, steps: tuple[float, ...]) -> float`  — Pick the step from ``steps`` giving a tick count closest to ``target_ticks``.
### class `Margins`
  attrs: `top: float; right: float; bottom: float; left: float`
### class `Scale` — A linear mapping from data units to pixels.
  - `ticks(self, step: float) -> list[float]`  — Tick values at multiples of ``step`` across the domain.
  attrs: `domain_min: float; domain_max: float; range_min: float; range_max: float`
  dunders: `__call__`
### class `Layout` — Everything the drawing code needs to place a mark.
  - @property `plot_left(self) -> float`
  - @property `plot_right(self) -> float`
  - @property `plot_top(self) -> float`
  - @property `plot_bottom(self) -> float`
  - @property `plot_width(self) -> float`
  - @property `plot_height(self) -> float`
  - @classmethod `build(cls, *, width: float, height: float, duration_s: float, max_depth_m: float, margins: Margins, target_time_ticks: int = 8, target_depth_ticks: int = 6) -> Layout`
  attrs: `width: float; height: float; margins: Margins; x: Scale; y: Scale; time_step: float; depth_step: float`

## `src/diveslate/render/overlay.py`
The compact slate: a badge to drop over a photo or a video frame.
`__all__ = ['CANVAS_SIZES', 'OverlayOptions', 'Position', 'render_overlay', 'render_overlay_canvas']`
- const `Position = Literal['top-left', 'top-right', 'bottom-left', 'bottom-right', 'top-center', 'bottom-center', 'center']`
### class `OverlayOptions` — Shape and content of the compact slate.
  - `resolved_theme(self) -> Theme`
  attrs: `width: float = 1080.0; theme: Theme | str = 'slate'; show_scrim: bool = True; show_site: bool = True; show_date: bool = False; show_ceiling: bool = True; show_gas: bool = False; show_deco: bool = True; stats: tuple[str, ...] | None = None; max_stats: int = 3; layout: Literal['wide', 'tall'] | None = None; corner_radius: float = 30.0`
- `_envelope(points: list[tuple[float, float]], target_width: float) -> list[tuple[float, float]]`  — Reduce the sample series to about two points per horizontal pixel.
- `_auto_stats(dive: Dive, limit: int, *, allow_deco: bool = True) -> list[tuple[str, str, str]]`  — (label, value, unit) triples, most headline-worthy first.
- const `StatBuilder = Callable[[Dive], tuple[str, str, str] | None]`
- const `STAT_KEYS = tuple(_STAT_BUILDERS)`
- `_named_stats(dive: Dive, keys: tuple[str, ...]) -> list[tuple[str, str, str]]`
- `render_overlay(dive: Dive, options: OverlayOptions | None = None, **overrides) -> str`  — Render the compact slate to a transparent SVG document string.
- `_draw_ceiling(canvas: Canvas, dive: Dive, sx: Callable[[float], float], sy: Callable[[float], float], plot_top: float, theme: Theme, scale: float) -> None`  — Stepped deco ceiling, hatched — same reading as the full chart's.
- `_draw_gas(canvas: Canvas, dive: Dive, sx: Callable[[float], float], sy: Callable[[float], float], theme: Theme, scale: float) -> None`
- `render_overlay_canvas(dive: Dive, *, canvas: str = 'portrait', position: Position = 'bottom-left', margin: float = 48.0, slate_scale: float = 0.86, options: OverlayOptions | None = None, **overrides) -> str`  — Place the slate on a full Instagram canvas, ready to drop straight on.
- `_svg_size(svg: str) -> tuple[float, float]`

## `src/diveslate/render/palette.py`
Colour maths, so themes can be generated and checked instead of eyeballed.
`__all__ = ['BAND', 'CheckResult', 'PaletteReport', 'best_in_band', 'contrast', 'delta_e', 'hex_to_oklch', 'max_chroma', 'oklch_to_hex', 'pick_accent', 'simulate', 'validate']`
- const `CHROMA_FLOOR = 0.1`
- const `NORMAL_FLOOR = 15.0`
- const `CONTRAST_MIN = 3.0`
- const `DEFAULT_SURFACE = {'light': '#fcfcfb', 'dark': '#1a1a19'}`
- `_hex_to_srgb(value: str) -> tuple[float, float, float]`
- `_s2lin(c: float) -> float`
- `_lin2s(c: float) -> float`
- `_linear(value: str) -> tuple[float, float, float]`
- `_oklab_from_linear(rgb: tuple[float, float, float]) -> tuple[float, float, float]`
- `_linear_from_oklab(lab: tuple[float, float, float]) -> tuple[float, float, float]`
- `hex_to_oklch(value: str) -> tuple[float, float, float]`  — ``(L, C, H°)`` for a hex colour.
- `oklch_to_hex(big_l: float, chroma: float, hue_deg: float) -> str`  — Hex for an OKLCH triple, desaturating as needed to land in sRGB.
- `_relative_luminance(value: str) -> float`
- `contrast(a: str, b: str) -> float`  — WCAG contrast ratio between two colours.
- `simulate(value: str, kind: str) -> tuple[float, float, float]`  — Linear-RGB of ``value`` as seen with ``protan``/``deutan``/``tritan``.
- `delta_e(a: str, b: str, kind: str | None = None) -> float`  — OKLab ΔE ×100 between two colours, optionally under simulated CVD.
### class `CheckResult`
  - @property `ok(self) -> bool`
  attrs: `name: str; state: str; detail: str`
### class `PaletteReport`
  - @property `ok(self) -> bool`
  - @property `warnings(self) -> tuple[CheckResult, ...]`
  - `summary(self) -> str`
  attrs: `checks: tuple[CheckResult, ...]; worst_cvd: float; worst_normal: float`
  dunders: `__str__`
- `validate(colours: list[str], *, mode: str = 'dark', surface: str | None = None, pairs: str = 'all') -> PaletteReport`  — Run the six checks over the colour-bearing marks of a palette.
- `_in_gamut(big_l: float, chroma: float, hue_deg: float) -> bool`
- `max_chroma(big_l: float, hue_deg: float, limit: float = 0.4) -> float`  — Greatest chroma sRGB can hold at this lightness and hue.
- `best_in_band(hue_deg: float, mode: str, ceiling: float = 0.16) -> tuple[float, float]`  — ``(lightness, chroma)`` for the most saturated usable version of a hue.
- `snap_to_band(value: str, mode: str) -> str`  — Move a colour into the mode's lightness band, keeping its hue usable.
- `pick_accent(curve: str, ceiling: str, *, mode: str = 'dark', surface: str | None = None, step_deg: int = 6) -> str`  — Choose the gas-switch accent by search rather than by eye.

## `src/diveslate/render/profile.py`
Draws the slate: depth curve, ceiling, gas switches, axes, stats.
`__all__ = ['RenderOptions', 'render_svg']`
### class `RenderOptions` — What to draw and how big.
  - `resolved_theme(self) -> Theme`
  attrs: `width: float = 1600.0; height: float = 900.0; theme: Theme | str = 'slate'; show_title: bool = True; show_axes: bool = True; show_grid: bool = True; show_ceiling: bool = True; show_gas: bool = True; show_stats: bool = True; show_legend: bool = True; show_deco: bool = True; padding: float = 8.0`
### class `_Stat`
  attrs: `label: str; value: str; unit: str = ''`
### class `_Legend`
  attrs: `entries: list[tuple[str, str, str]] = field(default_factory=list)`
- const `GAS_LABEL_BAND = 34.0`
- `_margins(opts: RenderOptions, *, stats_rows: int) -> Margins`
- `_curve_points(dive: Dive, layout: Layout) -> list[tuple[float, float]]`
- `_ceiling_runs(dive: Dive) -> list[list[Sample]]`  — Contiguous runs of samples that have a non-zero ceiling.
- `_draw_grid(canvas: Canvas, layout: Layout, theme: Theme) -> None`
- `_draw_ceiling(canvas: Canvas, dive: Dive, layout: Layout, theme: Theme) -> bool`  — Shade the region shallower than the ceiling. Returns whether anything drew.
- `_draw_curve(canvas: Canvas, dive: Dive, layout: Layout, theme: Theme) -> None`
- `_draw_axes(canvas: Canvas, layout: Layout, theme: Theme) -> None`
- `_draw_gas_switches(canvas: Canvas, dive: Dive, layout: Layout, theme: Theme) -> bool`  — Mark each gas change on the curve and label it with the mix name.
- `_draw_title(canvas: Canvas, dive: Dive, opts: RenderOptions, theme: Theme) -> None`
- `_draw_legend(canvas: Canvas, legend: _Legend, layout: Layout, opts: RenderOptions, theme: Theme) -> None`  — Right-aligned swatch+label row in the title band.
- `_collect_stats(dive: Dive, *, show_deco: bool = True) -> list[_Stat]`
- `_draw_stats(canvas: Canvas, stats: list[_Stat], layout: Layout, opts: RenderOptions, theme: Theme) -> None`
- `render_svg(dive: Dive, options: RenderOptions | None = None, **overrides) -> str`  — Render ``dive`` to an SVG document string with a transparent background.

## `src/diveslate/render/raster.py`
Turn the SVG into a PNG with its alpha channel intact.
`__all__ = ['RasterError', 'available_backends', 'svg_to_png']`
### class `RasterError` (RuntimeError) — Raised when no rasteriser is available, or one fails.
- `_via_cairosvg(svg: str, width: int | None, height: int | None) -> bytes`
- `_via_resvg(svg: str, width: int | None, height: int | None) -> bytes`
- `available_backends() -> list[str]`  — Names of rasteriser backends that actually work here.
- `svg_to_png(svg: str, *, width: int | None = None, height: int | None = None, backend: str | None = None) -> bytes`  — Rasterise ``svg`` to PNG bytes, preserving transparency.
- `write_png(svg: str, path: str | Path, *, width: int | None = None, height: int | None = None, backend: str | None = None) -> Path`  — Rasterise and write to ``path``.

## `src/diveslate/render/svg.py`
A very small SVG writer.
`__all__ = ['PRECISION', 'Canvas', 'fmt', 'points_to_path', 'text']`
- const `PRECISION = 2`
- `fmt(value: float) -> str`  — Format a coordinate: rounded, and without a pointless trailing ``.0``.
- `_attrs(attrs: dict[str, object]) -> str`
- `points_to_path(points: Sequence[tuple[float, float]], *, close: bool = False) -> str`  — Build an ``M/L`` path string from points.
- `text(content: str, x: float, y: float, *, fill: str, halo: str | None = None, size: float = 13.0, family: str | None = None, anchor: str = 'start', weight: str | None = None, baseline: str | None = None, opacity: float | None = None, halo_width: float = 3.0, **extra) -> str`  — A text label, painted with a halo underneath unless ``halo`` is None.
### class `Canvas` — Accumulates SVG elements and serialises the document.
  `__slots__ = ('_body', '_defs', 'height', 'width')`
  - `__init__(self, width: float, height: float) -> None`
  - `add(self, markup: str) -> None`
  - `defs(self, markup: str) -> None`
  - `linear_gradient(self, gradient_id: str, stops: Iterable[tuple[float, str]], *, vertical: bool = True) -> str`  — Define a linear gradient and return its ``url(#id)`` reference.
  - `hatch(self, pattern_id: str, color: str, *, angle: float = 45.0, spacing: float = 7.0, width: float = 1.6, background: str | None = None) -> str`  — Define a diagonal hatch fill and return its ``url(#id)`` reference.
  - `clip_rect(self, clip_id: str, x: float, y: float, w: float, h: float) -> str`
  - `rect(self, x: float, y: float, w: float, h: float, **attrs) -> None`
  - `line(self, x1: float, y1: float, x2: float, y2: float, **attrs) -> None`
  - `path(self, d: str, **attrs) -> None`
  - `circle(self, cx: float, cy: float, r: float, **attrs) -> None`
  - `group(self, markup: str, **attrs) -> None`
  - `text(self, *args, **kwargs) -> None`
  - `to_svg(self, *, title: str | None = None, description: str | None = None) -> str`  — Serialise the document.

## `src/diveslate/render/theme.py`
Colour and type tokens for the rendered slate.
`__all__ = ['CEILING', 'GENERATED', 'LIGHT', 'SLATE', 'THEMES', 'Theme', 'build_theme', 'get_theme', 'validate_theme']`
### class `Theme` — Colour and type tokens. Colours are CSS colour strings.
  - `with_(self, **changes) -> Self`  — A copy with tokens overridden — for one-off tweaks from the CLI.
  attrs: `name: str; assumed_surface: str; ink: str; ink_secondary: str; ink_muted: str; halo: str; grid: str; axis: str; scrim: str; curve: str; curve_fill_top: str; curve_fill_bottom: str; ceiling: str; ceiling_fill: str; accent: str; font_family: str = 'Inter, "Segoe UI", system-ui, -apple-system, "Helvetica Neue", sans-serif'; font_size: float = 13.0; title_size: float = 20.0; label_size: float = 11.5`
- const `SLATE = Theme(name='slate', assumed_surface='#1a1a19', ink='#ffffff', ink_secondary='#c3c2b7', ink_muted='#898781', halo='rgba(0,0,0,0.55)', grid='rgba(255,255,255,0.10)', axis='rgba(255,255,255,0.22)', scrim='rgba(8,12,18,0.62)', curve='#3987e5', curve_fill_top='rgba(57,135,229,0.38)', curve_fill_bottom='rgba(57,135,229,0.05)', ceiling='#d03b3b', ceiling_fill='rgba(208,59,59,0.22)', accent='#c98500')`
- const `LIGHT = Theme(name='light', assumed_surface='#fcfcfb', ink='#0b0b0b', ink_secondary='#52514e', ink_muted='#898781', halo='rgba(255,255,255,0.70)', grid='rgba(11,11,11,0.10)', axis='rgba(11,11,11,0.25)', scrim='rgba(252,252,251,0.76)', curve='#2a78d6', curve_fill_top='rgba(42,120,214,0.30)', curve_fill_bottom='rgba(42,120,214,0.04)', ceiling='#d03b3b', ceiling_fill='rgba(208,59,59,0.18)', accent='#eda100')`
- const `CEILING = '#d03b3b'`
- `_rgba(value: str, alpha: float) -> str`
- `build_theme(name: str, base: str, *, mode: str = 'dark', strict: bool = True) -> Theme`  — Derive a complete, validated theme from one base colour.
- `validate_theme(theme: Theme) -> PaletteReport`  — Check a theme's three colour-bearing marks against the palette gates.
- `_base_for(hue: float, mode: str) -> str`
- `get_theme(name: str | Theme) -> Theme`  — Resolve a theme by name, checking installed plugins before failing.

# Tests (tests/)
- `conftest.py` (0 tests)
- `test_cli.py` (9 tests) — TestRenderCommand, TestInfoCommand, TestBackendsCommand
- `test_models.py` (27 tests) — TestGasMix, TestDecoSpans, TestDerivedDepth, TestDiveLog, TestTitle, TestDecoTime, TestGradientFactors, TestGasUsed
- `test_overlay.py` (34 tests) — TestHeading, TestSlate, TestScrim, TestStats, TestGas, TestCeiling, TestEnvelope, TestCanvasPlacement, TestOverlayCli, TestOptions, TestGradientFactors
- `test_raster.py` (7 tests) — TestRasterisation, TestBackendSelection
- `test_render.py` (31 tests) — TestSvgDocument, TestLayers, TestThemes, TestScalesAndLayout, TestSvgPrimitives, TestRenderOptions
- `test_subsurface.py` (18 tests) — TestMetadata, TestCarryForward, TestGas, TestDerived, TestErrors
- `test_uddf.py` (10 tests) — TestUddf
- `test_units.py` (21 tests) — TestDepth, TestDuration, TestOtherQuantities, TestFormatDuration, TestCeilingRounding
