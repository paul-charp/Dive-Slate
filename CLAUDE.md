# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Dive Slate is an **Android app**. It reads a Subsurface (`.ssrf`) or UDDF
(`.uddf`) dive log and renders the profile as a compact **transparent slate** —
a badge to drop over a photo or a video frame, saved to the gallery as a PNG or
handed to the system share sheet.

**The README carries no dive-safety warning, and that is deliberate — do not
add one back.** It did while this was part of a planning stack, and the wording
was inherited from there. This app reads a log of a dive that already happened
and draws a picture of it. It plans nothing, computes no decompression, and
produces nothing anyone acts on in the water, so a "do not use for real dives"
caution described a use that does not exist.

The app is the only product. The Kotlin in `android/` is canonical and is where
behaviour is decided.

**There is a small amount of Python, and it is design-time only.** `tools/` holds
the colour maths and the scripts that bake it into `Themes.kt`. It does not ship,
it does not run on a phone, and it is not a second implementation of anything.

```
android/       the app. core/ is plain Kotlin/JVM and builds with only a JDK;
               app/ is Compose, the Canvas painter, intents, MediaStore export.
conformance/   the fixtures core/ is tested against, plus data/ — the source
               logs they describe.
tools/         Python. Palette maths and the generators for Themes.kt.
```

## Commands

```bash
cd android && ./gradlew core:test          # 52 tests, no device needed
cd android && ./gradlew :app:installDebug
cd android && ./gradlew :app:assembleRelease   # signed if keystore.properties
                                               # is present, debug key if not —
                                               # it says which, out loud
```

Needs JDK 21 on `JAVA_HOME`, and the SDK, which lives in
`%LOCALAPPDATA%\Android\Sdk` — export `ANDROID_HOME` or set `sdk.dir` in
`android/local.properties` (gitignored, so a fresh clone has neither and the app
module fails with "SDK location not found" until one is provided; `core:test`
does not need it).

Setting the toolchain up on Windows was awkward and the failures were silent, so
for the record:

- **winget's `Google.AndroidStudio` reports `Successfully installed` and exits 0
  without installing anything.** Verify with `winget list`, never the exit code.
- Anything needing elevation failed in this environment. The SDK was therefore
  installed via the command-line tools rather than Studio.
- Android Studio is **not** required to build. It is only needed for the IDE and
  the emulator GUI.
- `sdkmanager` prints a deprecation notice pointing at an `android` CLI whose
  installer fails with access-denied here. The notice can be ignored.

Python, after any palette change:

```bash
uv sync
uv run python tools/export_theme_tokens.py     # palettes + slider ranges
uv run python tools/generate_kotlin_themes.py  # -> android/.../Themes.kt
uv run pytest                                  # 5: 3 that the baked tokens match
                                               # the maths, 2 that the release
                                               # manifest matches the app
uv run ruff check . && uv run ruff format .    # line length 88
uv run mypy tools                              # strict mode is on
```

Line endings are LF, enforced via `.gitattributes`.

## Architecture

```
android/core/
  Units.kt            quantity-string parsing → canonical units; ceiling rounding
  Models.kt           Dive, Sample, GasMix, Cylinder, GasSwitch, DiveLog
  SubsurfaceParser.kt  UddfParser.kt  Detect.kt (content sniffing)
  Xml.kt              DOM wrapper; refuses any document declaring a DOCTYPE
  Themes.kt           generated — nine palettes as constants, do not hand-edit
  Slate.kt            the display list core emits
  SlateStyle.kt       the three axes: SlateLayout + LayoutMetrics, SlateStyle
  ModernStyle.kt      the default style — the drawing itself
  OverlayRenderer.kt  what the styles share: options, stats, envelope, entry point

android/app/
  MainActivity.kt     share intake, the intent handling described below
  SlatePainter.kt     paints core's display list onto a Canvas
  SlateFiles.kt       MediaStore export, FileProvider
  UpdateCheck.kt      update notice: manifest fetch, version compare, hand-off
  ui/DiveSlateApp.kt  Compose UI

tools/
  palette.py          OKLab/OKLCH maths, CVD simulation, the palette gates
  theme.py            Theme tokens; hand-built SLATE/LIGHT + seven generated
  export_theme_tokens.py, generate_kotlin_themes.py
  _console.py         UTF-8 stdout, so a summary cannot fail a generation
  test_themes.py      the three tests that guard the generated tokens
  test_release_contract.py  the workflow and UpdateCheck.kt agree on update.json

docs/RELEASING.md     the keystore, the secrets, and what each refusal protects
.github/workflows/    ci.yml on every push; release.yml on every v* tag
```

`core` emits the slate as a **display list**, not as pixels, and `app` merely
paints it. That split is what makes the interesting code testable without a
device — including all of the geometry.

## Style, layout, theme are three axes, not one setting

The look is chosen along three independent controls, and they are separated
because they answer different questions:

* **Layout** — where things go and how big they are. `SlateLayout` is a row of
  proportions quoted at 1080px, and `metrics(canvasWidth)` scales them. It
  contains no drawing. Two of those proportions are placements rather than
  sizes: `naturalWidth`, since a corner badge is narrower than the frame it sits
  in and the canvas is a bound rather than a target; `figuresLead`, since which
  of the profile and the figures is read first is the same marks in a different
  order; and `figuresStacked`, since giving each figure a row instead of a
  column is what lets the 400px `WATCH` badge set larger numerals than the
  1080px `WIDE` one — the mechanism behind a small slate being readable at all,
  and held by a test that fails if the numerals stop growing.

  `maxFigures` is the one thing a layout may say about *content*, and it is
  narrow on purpose. The figures share the width, so a corner badge asked for
  three types each one smaller than it can carry — the constraint is real and
  only the resolution is in question. The budgets were set by rendering, not by
  arithmetic: four for the full-width pair (Tall binds first, since its 86px
  numerals run out of column before Wide's 56px ones do) and two for the badges.
  `OverlayOptions.maxStats` is a further ceiling for a caller who wants fewer;
  it must stay unlimited by default, because a default of three once held every
  layout to three regardless of what its geometry could carry. It is resolved in
  the picker, which states the budget and greys out what is left, so a slate
  carrying fewer figures is something the user watched happen. Resolving it at
  render time instead is the thing worth refusing: a figure silently missing
  looks exactly like a log that never recorded it, which is the same reasoning
  as derived figures degrading to nothing rather than to a guess.
* **Style** — how the marks are drawn. The art direction. `ModernStyle` is the
  only one so far, and it is the whole of the current renderer.
* **Theme** — what colour it is. A palette that cleared the gates in
  `tools/palette.py`.

Two rules make that structure worth having rather than just more indirection,
and both are held by `SlateStyleTest`:

**Every layout works for every style.** A style must not decide its own
proportions — it reads `LayoutMetrics` and sizes its details through
`metrics.px()`. The moment a style hard-codes a padding, a layout added later
silently stops applying to it, and the test that renders the full cross-product
cannot see it because the slate still draws.

**A style carries its themes.** A palette is validated against the *marks it
will be painted as* — the curve, the ceiling and the accent, measured as a set —
so it belongs to the style that paints them, not to the app. `renderOverlay`
**refuses** a palette outside `style.themes` rather than substituting one:
substituting would hide precisely the mismatch the rule exists to catch. The UI
reconciles deliberately, through `SlateStyle.adopt`, which keeps the dark/light
choice because that is a statement about the footage the slate will land on and
the incoming style knows nothing about it.

A second style with its own palettes needs its own generated list — `Themes.kt`
would grow one alongside `SLATE_THEMES`, from `tools/`. Do not let a new style
borrow `SLATE_THEMES` because the names happen to be there; if its marks differ
enough to be a different style, they differ enough to move the ΔE measurements
that justified those colours.

## The fixtures are the contract

`conformance/` is what `core:test` is held to: full parsed models and every
derived figure for each log in `conformance/data`, a table-driven spec for the
unit grammar recording **rejected** input as deliberately as accepted, the
palettes as flat tokens, and synthetic deco profiles.

These files were **generated from a Python implementation that no longer
exists** (see [History](#history-worth-knowing)). Nothing regenerates them now —
they are maintained by hand. That changes what a failure means:

**When a conformance test fails, fix the code.** Editing a fixture is a decision
about what the behaviour should be, and it should be made as deliberately as
that sounds. It is never a way to turn a red test green.

The exception is `conformance/themes.json`, which *is* still generated — by
`tools/export_theme_tokens.py`, and `tools/test_themes.py` fails until you
regenerate after a palette change.

**A green Kotlin run is only meaningful if the task actually ran.** The fixtures
live outside the Gradle project, so for a while changing them left `core:test`
reported UP-TO-DATE — the suite whose whole job is noticing when fixtures and
code disagree was skipping itself precisely when they had just been made to
disagree. `conformance/` is declared as a test input now. If that declaration is
ever removed, a stale pass looks identical to a real one; `--rerun-tasks` is the
way to check when a result seems too good.

One lesson worth keeping, because it nearly cost the deco fix: **fixtures
generated from real logs only cover what real logs happen to contain.** Every
dive in `conformance/data` has a single deco span, so none of them can
distinguish a correct `decoTimeSeconds` from one pairing the first ceiling
arrival with the last span's end. That case exists only in synthetic profiles,
which is why `specs.json` carries a `deco_cases` section. Verified by
reintroducing the defect: the synthetic case failed and every real-log test
passed.

## How a dive log actually arrives

Established by installing Subsurface-mobile in an emulator and watching what its
export fires, because none of it is documented and every guess was wrong:

```
act=android.intent.action.SEND_MULTIPLE  typ=text/plain
clip={text/plain {U(content)}}
```

Three things there each broke the app once:

- **`SEND_MULTIPLE`, not `SEND`.** A filter for `SEND` alone never appears in
  the chooser.
- **`text/plain`, matched by a `text/*` wildcard.** Enumerating `text/xml` and
  `text/plain` was not enough to be offered — Submersion's filters, which do
  appear, use the wildcard. The type it stamps is not guaranteed.
- **The URI is in `ClipData`, not `EXTRA_STREAM`.** Reading only the stream
  extra found nothing and returned silently, so picking Dive Slate did nothing
  at all.

`handleIntent` therefore tries the data URI, the stream extra, the stream-extra
list and every ClipData item, then falls back to text inlined in `EXTRA_TEXT` —
and **never returns without setting state**. A share that produces no visible
result is indistinguishable from a crash and impossible to report; an unusable
intent now ends on a screen naming what arrived.

**Subsurface's database cannot be read directly.** `/data/data/
org.subsurfacedivelog.mobile` is denied to other apps, and the only provider
Subsurface declares is a `FileProvider` for files it chooses to share. The
export is not a workaround, it is the only route Android permits. (Its desktop
cloud cache *is* readable, at `%APPDATA%\Subsurface\cloudstorage` — a git
working tree of per-dive text files. That is how the format was inspected; it
has no bearing on the phone.)

## Shipping, and the app updating itself

There is no Play Store here. The GitHub release assets **are** the distribution
channel and the app updates itself from them, which makes the pipeline
load-bearing in a way a store would otherwise be. `docs/RELEASING.md` is the
procedure; this is what cost time.

- **Never believe the build config about signing — read the certificate back off
  the APK.** With no keystore configured `assembleRelease` falls back to the
  shared debug key and *succeeds*. A debug-signed release installs cleanly, so
  nothing looks wrong until an update signed with the real key is refused by
  every phone that took it. The workflow runs `apksigner verify` and fails on
  `CN=Android Debug`.
- **The signing key cannot be replaced.** Android identifies an app by its
  signature, so losing it means no installed copy can ever be updated. It is
  generated outside this repo and `*.jks`, `*.p12`, `*.keystore` are gitignored
  so a stray copy cannot be committed. Locally the four values live in
  `keystore.properties`, not `local.properties` — Android Studio owns the latter
  and rewrites it.
- **The release APK is v3-signed only, and that is correct.** v3 is verified from
  API 28 and `minSdk` is 29, so the signer drops the redundant v2 block even
  though the build asks for both. A release check asserting "v2 is true" fails
  every good build; the workflow made that mistake and it was caught by reading a
  real APK rather than by reasoning about it.
- **A half-configured signing block fails the build.** Three of the four names
  would otherwise fall through to the debug key and look like success — the same
  reasoning as the derived figures degrading to nothing rather than to a guess.
- **`versionCode` is what the updater compares**, numerically. A tag disagreeing
  with `versionName`, or a code that does not beat the published one, fails the
  release rather than publishing an update nobody is ever offered.
- **`update.json` is fetched from `/releases/latest/download/`**, a path GitHub
  resolves to whichever release is newest. That is why the app needs no API call,
  no token and no tag names — and why marking a release as a prerelease is how to
  publish a build without offering it to every installed copy.
- The updater is **framework-only** — `org.json` and `HttpURLConnection` — so it
  adds no dependency to the APK and needs no R8 keep rule.
- **The app does not install its own updates any more, and must not start
  again.** It downloaded the APK, verified it against the manifest checksum and
  handed it to the package installer until 0.4.0. Downloading a binary and
  asking to install it is, as *behaviour*, what a dropper does — Play Protect
  scores behaviour, not intent, and a certificate it has never seen on a build
  with a handful of installs has nothing on the other side of the scale. A
  correctly signed release was refused with "Unsafe app blocked". Worse is the
  variant nobody can tap through: Google's enhanced fraud protection blocks
  sideloading outright for apps declaring `REQUEST_INSTALL_PACKAGES`, and a
  phone that cannot install is a phone that can never be updated again.
  `UpdateCheck` now opens the release page and the browser does the rest, which
  makes the *browser* the unknown source Android asks about. The cost is real
  and was accepted deliberately: two more taps, and checksum verification
  demoted from enforced to displayed. Adding the permission back to recover
  either would buy back the block. (It was already Play-restricted, so it would
  have had to go if this ever reached the Play Store.)
- **`InputStream.readNBytes` is API 33 on Android**, and `minSdk` is 29. It
  compiles without a murmur and throws `NoSuchMethodError` on Android 10–12.
  Framework methods that look like ordinary Java still need their API level
  checked.
- Two CI traps, neither of them visible from Windows: **`gradlew` committed
  `100644`** fails a Linux runner with exit 126, which reads as a Gradle failure
  rather than a permission one; and **`astral-sh/setup-uv` publishes no moving
  major tag** — its major tags stop at v7 while releases are past v10, so `@v10`
  resolves to nothing at all.

## Six things that are easy to break

### 1. Subsurface samples are sparse

Subsurface writes a sample attribute **only when it changes**. A line carrying
just a time and depth means "everything else is as it was", not "everything else
is unknown". `parseSamples` carries every optional field forward, exactly as
Subsurface's own reader does. Break this and a 50-minute deco dive parses as one
deco sample followed by nothing. `conformance/data/reference.ssrf` is the proof:
1930 samples, `in_deco` written twice, 1503 samples carrying an obligation —
`ParserConformanceTest` asserts exactly that.

UDDF is the opposite: waypoints are self-contained and nothing carries forward
except the breathing mix.

### 2. Deco time is the hang, not the obligation

`Dive.decoTimeSeconds()` measures from first reaching the ceiling on the way up
until the obligation clears. `Dive.decoSpans()` measures when deco was *owed*,
which starts while the diver is still on the bottom. On the reference dive these
are 23:20 and 50:06. Reporting the second as "deco" claims fifty minutes of stops
that never happened — that bug shipped once and was caught by the user.

A dive that clears deco and re-incurs it served **two** hangs. Each span is
measured against the end of its own obligation and the hangs summed, so the
cleared interval between them is never counted. A span whose ceiling was never
reached contributes nothing rather than voiding the hangs that were served —
surfacing in deco after an earlier stop still reports that stop.

### 3. The colour palettes are computed, not chosen

`tools/palette.py` implements the data-viz gates — OKLab ΔE, Machado CVD
simulation at severity 1.0, chroma floor, lightness band, WCAG contrast — and
`build_theme` **raises** rather than returning a palette that fails them.

This runs at design time and the app ships the answers. `Themes.kt` is
**generated** — hand-editing it puts the shipped colours out of step with the
maths that justified them, and `tools/test_themes.py` will not catch it, because
that test compares the maths to `themes.json`, not to the Kotlin.

Two findings that are not obvious and cost real debugging:

- **Only base hues ≈180–330° work.** Warm and green bases collide with the fixed
  deco-ceiling red under CVD. Green measures ΔE 24 against red to normal vision
  and **2.2** under protanopia.
- **The sRGB gamut is not a cylinder.** Asking for a fixed chroma across hues
  silently desaturates cyan below the chroma floor. `best_in_band` sweeps for the
  lightness where a hue holds the most chroma.

The ceiling red (`CEILING_ARGB`) is deliberately **not** themed: a hazard colour
that shifts with the palette stops reading as a hazard.

Where a mark sits below 3:1 contrast, it is legal **only** because a text label
carries the identity — gas switches always print the mix name. Don't drop those
labels to reduce clutter.

### 4. Transparency is the product

No background is ever painted. Because the backdrop is unknown at render time,
all text is drawn twice — a halo stroke under the fill — and the slate adds a
scrim panel, since halos alone are not enough over video where the frame behind
a label changes constantly.

The opacity control moves the scrim and nothing else, clamped to a per-theme
floor computed from ink contrast against the worst possible backdrop. Fading the
marks would void the contrast the gates enforce and turn the hazard red into a
pink suggestion. Two tests hold that line.

### 5. Derived figures must degrade to nothing, never to a guess

`gradientFactors` matches a pattern in a free-text label and validates the
result (1–100, low ≤ high) — a VPM-B dive has no GFs and must not appear to have
them. `gasUsedLitres` needs size *and* both pressures, and drops a cylinder that
came back fuller rather than subtracting it. `decoTimeSeconds` returns null when
the ceiling was never reached.

### 6. Subsurface nests dives inside trips

A dive is a child of `<dives>` **or** of a `<trip>` inside it, and one log mixes
both. Matching `dives/dive` finds only the ungrouped ones, so a logbook where
every dive belongs to a trip parses perfectly and yields nothing — which
surfaces as "this log contains no dives" against a file that is plainly full of
them. Both parsers search at any depth under `<dives>`;
`conformance/data/trips.ssrf` guards it.

This shipped because every fixture was ungrouped: the reference dive was
exported alone, and dives added by hand in an emulator have no trip. A corpus
assembled from convenient exports had a shape no real logbook has. Worth
remembering when adding fixtures — real data covers what real data happens to
contain, which is the same lesson the synthetic deco profiles teach.

## Deliberate divergences — do not "restore parity"

Choices that departed from the original Python implementation. It is gone, but
the fixtures still encode its behaviour, so these remain worth recording.

- **The XML reader refuses any document declaring a DOCTYPE.** The desktop tool
  read files the user chose; the app is handed documents by other apps. The
  check is a text scan performed before any parser sees the document, because
  Android is not Xerces: the Apache hardening feature names *throw* there rather
  than harden, and `setXIncludeAware` throws outright. A feature flag would have
  been silently inert on the only platform that needs it.
- **UDDF deco stops with a missing depth yield null, not NaN.** The Python
  produced NaN there, which survived its own zero-check because NaN is truthy
  and then poisoned every downstream ceiling comparison.
- **The mix figure is labelled "Gases" and joined with ", "** rather than "Gas"
  and "/". A slash reads as a ratio beside `GF 70/80`, and `Tx18/45` already
  contains one.
- **`minSdk` is 29**, for MediaStore scoped storage.

## Conventions

- Optional log fields are null, never a sentinel zero — a dive with no recorded
  temperature must not render as a dive at 0 °C.
- **Never add a second y-axis.** Temperature, pressure and consumption go in the
  summary; depth is the only thing mapped to vertical space.
- The depth axis always starts at the surface.
- Slate figures round **up** — 44.4 m is a 45 m dive (`ceilMetres`,
  `ceilMinutes`).

## History worth knowing

**This began as a Python desktop CLI** that rendered SVG and PNG charts, and for
a while the Kotlin was a port held to it. The Python was removed once the app
became the only intended product. What it earned survives as `conformance/` —
the fixtures are the record of its behaviour, which is why they are treated as a
specification rather than as a snapshot.

Removed with it, and **not** worth reviving unless the desktop returns: the full
chart (axes, grid, legend, summary strip), SVG output, canvas placement modes,
the rasteriser backends, and an entry-point plugin system for parsers and themes.
The chart was a desktop analysis artifact; the slate is a badge. Sharing their
layout code was tried and abandoned once already — the constraints genuinely
differ.

One trap from that era, recorded in case PNG rasterisation is ever wanted again:
**skia-python installs cleanly, is fast, and renders none of the SVG text.** Its
`SVGDOM` exposes no font-manager hook, so the output was a clean-looking chart
silently missing its title, labels and stats.

A **gas ribbon** (a segmented bar under the profile showing which mix was
breathed when) was built and then removed at the user's request. If it is ever
wanted back, the design that worked was a lightness ramp of one hue ordered by O2
fraction, with every segment labelled — categorical hues are not available,
because the usable band is too narrow to hold several that clear the gates
against the curve, the ceiling and each other.

There was also a **generated codebase index** — a map of every module and
signature, with a section here telling you to read it before opening source. It
covered the Python that no longer exists, and it is not coming back: the file
map under [Architecture](#architecture) is the navigation aid now. Don't add
another one.
