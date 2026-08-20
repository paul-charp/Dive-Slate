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
cd android && ./gradlew core:test              # 87 tests, no device needed
cd android && ./gradlew :app:testDebugUnitTest # 42 more, also no device
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

- **`compileSdk = 37` is not in the stable SDK channel.** The stable repository
  stops at `platforms;android-36`, and the new naming means the package is
  `platforms;android-37.0` — `platforms;android-37` matches nothing and
  `sdkmanager` reports only "Failed to find package". Install it with
  `--channel=3`, alongside `build-tools;37.0.0`. Worth knowing before concluding
  the SDK is broken; and worth remembering if CI is ever pinned to stable.
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
uv run pytest                                  # 9: 7 that the baked tokens match
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
  Themes.kt           generated — 24 palettes as constants, do not hand-edit
  Slate.kt            the display list core emits
  SlateStyle.kt       the three axes: SlateLayout + LayoutMetrics, SlateStyle
  StyleKit.kt         what the styles share *underneath* the drawing: the frame,
                      the profile, the stepped ceiling, figure typesetting
  ModernStyle.kt      the default style — the drawing itself
  WrappedStyle.kt StickerStyle.kt MagazineStyle.kt FrostedStyle.kt
  HoloStyle.kt RetroStyle.kt TopoStyle.kt
                      seven more, one file each
  OverlayRenderer.kt  what the styles share: options, stats, envelope, entry point

android/app/
  MainActivity.kt     share intake, the intent handling described below,
                      and the batch export off the main thread
  SessionState.kt     the ViewModel: open logs, current settings, export and
                      update progress — everything a rotation must not take
  SlateSettings.kt    the chosen look as one object, its normalisation rules,
                      and the SharedPreferences store behind "save as default"
  SlatePainter.kt     paints core's display list onto a Canvas
  SlateFiles.kt       MediaStore export, FileProvider, export naming
  LogCache.kt         the app's own copies of the logs it has been given
  RecentDives.kt      the dives taken into the editor, the look each was given,
                      and what that means for what is kept on disk
  UpdateCheck.kt      self-update: manifest, download, checksum, installer
  ui/DiveSlateApp.kt  Compose UI
  src/test/           JVM tests for the three above that need no framework:
                      the settings round trip, the export names, the manifest,
                      dive identity and the retention rules

tools/
  palette.py          OKLab/OKLCH maths, CVD simulation, the palette gates and
                      the three profiles they come in
  theme.py            Theme tokens; hand-built SLATE/LIGHT + seven generated,
                      then STYLE_THEMES — one palette family per style
  export_theme_tokens.py, generate_kotlin_themes.py
  _console.py         UTF-8 stdout, so a summary cannot fail a generation
  test_themes.py      the seven tests that guard the generated tokens
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

A style with its own palettes needs its own generated list — `Themes.kt` carries
one per style alongside `SLATE_THEMES`, all from `tools/`. Do not let a style
borrow another's because the names happen to be there; if its marks differ enough
to be a different style, they differ enough to move the ΔE measurements that
justified those colours. `test_themes.py` fails if two styles share a palette.

Every style also ships **at least one dark and one light palette**, and that is
structural rather than tidy. `adopt` keeps the mode across a style change because
the mode is a statement about the footage; a style with no light palette would
quietly drop that statement on the way in.

## The eight styles, and what a style may trade

Seven styles were added to Modern from a set of mockups. (There were eight: a
Material 3 one, a tonal card with its figures in chips, removed at the user's
request along with its eight seeded palettes and the `figureScale` lever it
introduced.) They differ in ornament and palette far more than in arrangement —
the page is still a heading, a profile and some figures, which is why they are
styles rather than layouts.

```
modern    flat and geometric, the default            chromatic  9 palettes
wrapped   one loud opaque card, no ornament          expressive 2
sticker   rounded, ringed, a ramped line             expressive 2
magazine  masthead rules, condensed figures, no card monochrome 2
frosted   two-stop glass with a lit edge             monochrome 2
holo      cut-corner panel, dot field, lit trace     expressive 2
retro     bezel, segment screen, stepped trace       monochrome 3
topo      grained paper, labelled grid, hachures     expressive 2
```

What a style is free to change, and what it is not, is the part worth keeping:

* **The ceiling may be re-coloured. The hatch and the dash may not.** See
  [The colour palettes are computed](#3-the-colour-palettes-are-computed-not-chosen).
* **A gas switch always prints its mix name**, whatever the art direction.
  `SlateStyleTest` checks every style for it. The new styles deviate from the
  designs in *where* and in *what colour*, and both took two attempts:

  - **Where.** A dot on the profile, a dashed leader, and the mix in a tab
    *straddling the surface line* — not the name set beside the dot. Beside the
    dot it lands wherever the diver happened to be: on the curve, inside the
    hatch, off the right edge late in a dive. On an opaque card there is no halo
    to save it either, since the halo is dropped as pointless on a known
    background. A label that is mandatory cannot also be the one placed by luck.

    Hanging the tab a fixed distance *below* the surface was the first attempt
    and it failed at shallow depths for the same reason: the leader shrinks to
    nothing and the marker rises into the tab, so a switch at three metres drew
    a disc through its own pill. On the surface line the tab is in the same
    place whatever the depth, and the leader is drawn only when there is a run
    to draw. Tabs that would collide step down a row.

    **Paint order settles the rest: marker, leader, then tab.** The tab carries
    the only text by which this mark may be identified at all, so nothing is
    permitted to land on top of it — where the marker and the tab overlap, the
    tab wins. That is a one-line rule and it replaced a fiddlier one that tried
    to suppress the marker when it fell inside the tab.

    This is the one thing the newer styles gave back to [ModernStyle], which had
    kept its own beside-the-dot label and was the only style still placing a
    mandatory label by luck.
  - **What colour.** The tab is filled with the *panel* colour and outlined in
    the accent, rather than filled with the accent. Filling it with the accent
    made the label's legibility depend on a colour chosen to separate from other
    **marks**, which is a different question: on the wrapped card the accent was
    the same pink as the water under it, and `O2` in two characters had nothing
    to be read against. Ink on panel is the one pairing every palette has already
    had measured. `accent_over_panel` in `tools/theme.py` now holds the accent to
    3:1 against that panel — the check that would have caught it, where every
    separation gate passed, because none of the marks they compared was the one
    behind.
* **Ornament comes from the log or not at all.** The mockups put
  `DEPTH TELEMETRY // LIVE` in one corner and a battery gauge in another. Both
  are instrument dressing that a reader cannot tell from a reading, on a badge
  about a dive that ended hours ago — the same objection as a derived figure
  degrading to a guess. Where a corner wants a line, it gets the dive number,
  and where the log has none it gets nothing.
* **A style sizes everything through `metrics.px()`,** including ornament, so a
  legend box or a microcopy line shrinks with the badge instead of swallowing
  it. Anything the style places against the right edge measures what is already
  there rather than reserving a fraction of the width — a fraction is a guess
  that survives Wide and collides at Watch.
* **A style whose ornament does not fit shrinks the ornament, not the figures.**
  `SlateFrame.of` used to take a `figureScale` so a style drawing each figure in
  a container could charge the padding to the figure. Both are gone with the
  style that used them, and the lever is not being kept for the next one,
  because it was the wrong one: the layout's sizes are a promise — the watch
  badge sets 88px numerals *because* it stacks them — and a style that scales
  them down to pay for its own decoration breaks that promise silently. It did:
  a badge a third of Wide's width ended up with numerals smaller than Wide's,
  and smaller than Compact's on a *larger* badge. The smallest slate set the
  smallest numbers.

  **Guard emitted marks, not quoted metrics.** Every metric-level test passed
  through all of that, because no metric had moved — the guard asked
  `SlateLayout` what it quoted, not the style what it drew. `SlateStyleTest` now
  renders the cross-product and compares the largest text each style actually
  emits. Both tests are kept; they answer different questions, and only the new
  one can see a style undercutting its layout.

* **A box is sized with `boxedAdvance`, and its text is centred in it.** Core
  estimates text width from character count and never measures, so the estimate
  is sometimes short. Short for a bare label costs a few pixels nobody notices;
  short for a label with a background puts the text outside its own background,
  which reads as a bug rather than a layout. Boxes therefore take the wider of
  the face's two averages plus a margin, and centre what is inside — which turns
  an overestimate into air at both ends instead of a word hanging off one.
* **A border is inset by half its stroke.** A stroke on the slate's own edge is
  centred there, so half of it falls outside the image and is cropped, leaving a
  hairline on three sides and half a hairline on the fourth.
* **Panels go behind `showScrim`.** A style that paints its own card puts it
  under the same switch as the plain scrim, or the control means something
  different depending on what is selected. Tested.
* **Type comes from Android's own families** — `sans-serif`, `-medium`,
  `-black`, `-condensed`, `monospace`, `serif`. The mockups named Windows faces
  (Haettenschweiler, Bahnschrift, Arial Rounded), none of which exist on a
  phone, and bundling licensed equivalents would add binaries to the APK for a
  difference visible at a glance and not at export scale. What does matter is
  `SlateFont.advance`: core has no font and estimates text width from character
  count, so each face carries its own average — and **letter spacing is part of
  that estimate**, since these headings are tracked out to a third of an em and
  ignoring it leaves the fitting short by half the string. Each face also carries
  a **separate digit width**, because the slate's figures are nearly all digits
  and a mean pulled down by letters they never contain places the unit inside
  the number: in the condensed face the average is 0.46 and a digit is 0.52, so
  the `m` after a depth landed six pixels into it — worse the larger the figure,
  which is why it showed first on the style that sets figures biggest.

**Every style but the segment screen draws a smoothed curve, and the curve may
not overshoot.** `OverlayOptions.smoothProfile` is **on by default** — a dive
profile is a curve in the water and the polyline is the sampling artefact, so
the curve is the truer of the two pictures. It stays a control because a reader
after every tooth of a sawtooth bottom wants the teeth, not a line through them.
Retro is the one style that cannot honour it: the segment screen quantises to
one minute and one metre, and a curve through a staircase is a staircase with
rounded corners. The UI reads `SlateStyle.supportsSmooth` rather than naming the
style, and *hides* the chip instead of greying it — a control that does nothing
when pressed is worse than one never offered, the same rule the dive list
applies to a row it cannot draw.

**Retro has to *declare* that, and for a while it only did it in a comment.** It
resamples for its own reasons and never calls `profileTrace`, so it ignored the
flag rather than overriding it — which meant `supportsSmooth` stayed true, the
chip was drawn, and pressing it did nothing. Ignoring a flag is not the same as
answering it. Found by a test asking the question the UI asks.

The smoothed profile is drawn through a coarser series — a spline through points a pixel apart is a
polyline with extra arithmetic — using flat tangents, so each segment leaves one
point horizontally and arrives at the next horizontally and the line stays inside
the two depths it joins. A nicer-looking spline (Catmull-Rom) overshoots, which
here would draw the profile deeper than the deepest sample and put the picture at
odds with the figure beside it. Smoothing may change how the line travels between
two depths; it may not invent a third. The coarsening keeps each bucket's
shallowest *and* deepest sample for the same reason, and a test holds it.

Coarsening and smoothing go together, which is why `profileTrace` does both:
coarsening without smoothing is just a worse polyline, and smoothing without
coarsening is arithmetic nobody can see. `ModernStyle` maps its samples with
closures of its own and so reduces by hand — but at the shared `SMOOTH_STEP_PX`,
because a second copy of that number is how the default style ends up smoothing
differently from the other seven.

**`SMOOTH_STEP_PX` is free to be tuned for looks, and that is a property of
`coarsened` rather than a licence.** It went 34 → 64 when 34 turned out to be
merely enough to make the line legal as a curve rather than enough to make it
look like one. Moving it is safe *only* because every bucket keeps both
extremes: a coarser step changes how much wobble survives and never where the
deepest point is, so the profile cannot drift away from the figure printed
beside it. Were the reduction an average or a first-sample rule, this constant
would be a depth error waiting for someone to tune it — which is exactly the
trap the segment screen's resampling fell into, where a coarser grid *did* move
the deepest drawn point while the figure still read 45 m.

Two places where a style touches the data, both deliberate and both narrow:

* **The segment screen resamples the profile to one minute and one metre.** The
  mockup used two minutes and three, which flattened the reference dive's
  sawtooth bottom into a staircase and moved its deepest drawn point by nearly
  two metres while the figure beside it still read 45 m. Nothing was misstated,
  but the picture had stopped agreeing with the number.
* **The survey's gridlines come from the dive**, at an interval picked from the
  depth actually reached, and every line is labelled. The mockup's fixed 15/30/45
  ruler draws nothing on a 12-metre reef and stops two thirds of the way down a
  60-metre dive, silently in both cases. Its hachures are the opposite decision:
  they are texture, so they carry no numbers, sit at a fraction of the profile's
  weight, and are drawn in the paper's brown rather than the survey blue. If
  anyone ever asks what interval they are at, delete them rather than inventing
  one.

## The look is remembered, and nothing else about the app is

Every control on the editor screen is one `SlateSettings` — style, layout,
palette, units, opacity, elements, figures — and it lives in `SessionState`, a
ViewModel, rather than in the composition that draws it.

**That was a rotation bug before it was a feature.** All of it was `remember`ed
inside the editor and the open dive log was a field on the activity, so turning
the phone over destroyed both: a log opened from the picker went back to the
start screen, and a look chosen over five minutes was gone. A share survived by
accident — the intent is still attached to the recreated activity and got read
again, which also meant the shared file was parsed a second time and every dive
listed twice. `SessionState.handledIntent` is what stops the second read; the
ViewModel is what stops the first loss.

**The export and update coroutines run on `viewModelScope`, not
`lifecycleScope`.** Once the state survives a rotation, work that does not is
worse than before, not better: a cancelled batch would leave the progress bar
stuck at "Saving 7 of 20" forever, because the state it is drawn from now
outlives the coroutine that was writing it. Two consequences worth keeping:
the file work takes `applicationContext`, and the chooser and the installer are
parked in `SessionState.pendingLaunch` for whichever activity is on screen to
start — an intent started on a destroyed activity is a share that silently never
appears.

**Saving is explicit, and there are three actions rather than one.** Persisting
every slider drag would promote the last thing tried to the thing you always
get, which is how a remembered default becomes a surprise; so the editor is a
scratchpad and *Save as default* is a deliberate act. *Restore default* puts
back what was saved and is enabled only when there is something to go back to
and the current look differs from it. *Factory reset* goes back to what the app
shipped with **and forgets the saved default** — one that left the saved look in
place would put it back on the next launch, which is the state someone reaching
for it is trying to leave — and it confirms first, being the one control that
discards something the user made.

**Where a control lives is a claim about how often it is touched.** The editor
is one long scroll — roughly two phone screens — so a heading and a row is a
real cost, and three controls do not earn one:

* **The defaults go in the top bar's overflow menu**, in stock `DropdownMenu` /
  `DropdownMenuItem` / `AlertDialog`, so they take the wallpaper scheme with the
  rest of the chrome. They were a heading, a button row and a line of prose
  permanently on screen for three actions taken a few times a year. The caption
  the page used to carry moved to the foot of the menu, because "default" means
  nothing until you know whether you have one.
* **The units ride on the figures heading line.** They are one-of-two, so they
  stay a `SingleChoiceSegmentedButtonRow` matching Layout rather than becoming
  chips — but they sit beside the figures because printing those figures is the
  only thing they do.
* **The checkerboard toggle is drawn on the preview itself.** It is the one
  control on the screen that never reaches the exported PNG, so it does not
  belong among the switches deciding what the image says. Its glyph is hand-drawn
  for the reason already recorded on `SelectionDot`: the APK carries Material's
  40-icon core set, which has no checkerboard, and the extended set is thousands
  of vectors for one mark. Its disc has to read on *both* states it toggles
  between — the mid-grey checkerboard and the app surface underneath it — or the
  control is unusable in exactly one direction.

Together those are about 234dp off a 1360dp page, and none of it is a control
that changes the exported image.

**`SlateSettings.normalised()` is where a stored string stops being dangerous.**
It is the only input in the app that can hold a state the UI cannot produce: a
palette belonging to another style, four figures on a badge with room for two, a
style id from a build since uninstalled. `renderOverlay` **refuses** a foreign
palette, so a stored one would blank the preview on every launch with no way
back except clearing the app's data. Normalisation adopts the palette (keeping
the dark/light choice), clamps to the measured opacity floor, trims the figures
to the layout's budget and drops keys this build does not know — and a test
renders the full cross-product of what `decode` can return, because "anything
storable is drawable" is the whole claim.

Smoothing is deliberately *not* normalised away for the style that cannot
honour it. `toOptions()` masks it instead, so what is drawn is right and what is
remembered is what the user asked for — clearing the flag would mean one look at
the segment screen silently threw the preference away for every style after it,
which is the same reasoning that carries the dark/light choice across a style
change.

## Recent dives, and the only storage the app takes

Every incoming log is copied to `filesDir/logs/`, because the permission on a
`content://` URI dies with the activity that received it — stashing the URI to
open later is a guaranteed bug. `LogCache` is that directory and nothing else;
`RecentDives` is the list drawn from it and decides what is worth keeping.

**Dives, not files.** The first version of this listed logbooks — `reference.ssrf,
6 dives` — which left the finding to the reader when the thing they came back
for is *that dive*. Listing dives makes the start screen a list of the slates
that have been made, which is what the app is for. It also brings back the
provenance problem the dive list is arranged around: dive numbers are
per-logbook and collide, so the source file is on every row, or two rows read
identically and neither can be told from the other.

**Reaching the editor is what makes a dive recent**, not opening the file it is
in — a 200-dive logbook would otherwise contribute 200 rows nobody chose.
Opening the editor is a statement about one dive, or about each dive in a batch,
and all of a batch is recorded. It is recorded again as the editor closes, so
what is stored is the look the dive was *left* in.

**A dive is identified by its number and its time, never by its position.** The
list is sorted newest-first for display, so a stored index would move the moment
a logbook gained a dive, and a remembered slate would quietly become a different
dive's — which does not throw and does not look wrong. Both fields are optional
in the model, so the raw position in the file is the last resort. A dive that
cannot be found at all is dropped and said so, rather than resolved to a
neighbour.

**The look is stored per dive, and restoring it is scoped to that dive.** A
reopened slate ought to reproduce the slate, so the recorded look is applied on
the way in — but as `SessionState.editorSettings`, never as `settings`. Letting
it become the session look would mean that opening an old dive silently changed
what every dive after it opened with, which is precisely the failure the
scratchpad model exists to prevent. It is cleared by a `LaunchedEffect` keyed on
whether an editor is showing, **not** by the editor's disposal: a rotation
disposes the composition too, and clearing there dropped the restored look every
time the phone turned over.

**Retention hangs off the dive list, so there is one authority rather than
two.** Twenty dives; a cached log is kept exactly while some remembered dive
still needs it, and deleted when none does. `MAX_BYTES` is a second and blunter
bound — twenty dives across twenty large logbooks would satisfy the count and
still cost hundreds of megabytes — and it is applied by *shortening the list*,
oldest first, rather than by deleting files behind the list's back. A log is
charged once however many dives point at it. **The newest entry is never
dropped**: a single logbook larger than the whole budget is a real thing to
open, and evicting the dive just worked on to satisfy a limit would empty the
list at the moment it was most useful.

**The sample dive is offered only while the recent list is empty**, and the
line of prose above the buttons changes with it. The sample answers "what does
this app do" for someone with no log in front of them; once there are real dives
to go back to, it is a standing offer to look at someone else's dive instead of
your own. A fixed caption naming a button that is not there would send half the
readers looking for it.

**Only what parsed is kept**, so the copy is taken *after* the parse. It used to
be taken before, which was harmless for exactly as long as nothing read the
directory back: a share that turns out to be a zip would otherwise hold a row
and fail again on every attempt.

**Everything a row draws is stored, never computed.** Parsing at list time would
make the start screen cost whatever the largest cached logbook costs, on every
launch. The figures stored are the *computed* ones — `computedMaxDepthMetres`,
not the optional logged field — because that is what the slate prints, and a row
quoting the logged field would read blank for exactly the dives whose slate
shows a depth. The figures are also printed in the units of that dive's own
look, since the row describes a slate that exists.

**The index is one line per dive, pipe-separated, with the encoded settings
last.** Same reasoning as `SlateSettings.encode`: the app carries no reflection
and the release build is shrunk with R8, so a serialisation library would be a
dependency and a keep rule for a dozen scalars. The pipe is the separator
precisely because `LogCache.sanitise` removes it and `encode()` never emits it —
and a site name is free text from a logbook, so pipes and newlines are stripped
from it or one dive becomes two rows.

**A display name is the one input in this app that can name a path.** It arrives
from another app via `OpenableColumns.DISPLAY_NAME` and nothing obliges it to be
a legal file name; `LogCache.sanitise` reduces it, and a test writes
`../../evil.ssrf` and checks where it lands. Which cached file a parsed log came
from is *derived* by putting `DiveLog.source` through the same reduction, rather
than stored a second time where the two could drift.

## Add to story has now been refused three times — read this first

`ADD_TO_STORY` was this app's *only* export once, and `d75cf2b` removed it
because being the only route made the button a bet on which app the user wanted,
and on a phone without Instagram it degraded silently into the chooser it was
supposed to be an alternative to. It was rebuilt in the shape that answers both
objections — shown only when the package manager confirms Instagram is there,
only for a single slate, always beside the chooser — and taken back out again,
because those were never the binding constraints. These are:

- **Meta requires a registered Facebook App ID.** Since January 2023 the intent
  must carry one in `source_application`, or Instagram refuses with "The app you
  shared from doesn't currently support sharing to Stories." No amount of
  correct intent-building substitutes for it, and this project has no Meta app.
- **A sticker with no background does not land on the user's footage.** It is
  permitted — Meta's docs say a background asset, a sticker asset, or both — but
  the story background then comes from `top_background_color` /
  `bottom_background_color`, solid or gradient. The 2024 implementation passed
  `top_background_color`, which is the evidence it had already met this. The
  result is the slate on a coloured card, and there is no documented way for the
  user to swap their own video in once the composer has opened.

**So the feature is the background-media picker, not the intent.** A slate over
your own footage requires the app to pass `background_video` or
`background_image` itself, which means holding the media — the same gap the
README lists under Known gaps. Done that way round the intent finally earns its
place: background is the user's footage, `interactive_asset_uri` is the slate,
and Instagram composites the two with the slate still draggable. Attempted in
either order, that is the order.

Two implementation details worth not rediscovering, if it is ever revived:

- **The asset URI travels in an extra, so `addFlags` does not reach it.** The
  automatic grant walks the intent's data and its ClipData and nothing else. For
  multi-image sharing the fix was ClipData; here it cannot be, because Instagram
  reads `interactive_asset_uri` and not the clip. `grantUriPermission` to the
  package by name, or Instagram opens on an empty story — which looks exactly
  like this app having produced nothing.
- **A `<queries>` entry for `com.instagram.android` is what makes the "is it
  installed" question answerable at all** from Android 11. Without it
  `resolveActivity` returns null on every modern phone, and the button silently
  never appears. If it returns, it stays one named package and never a wildcard:
  everything else here is deliberately blind to what is on the phone.

## Metric or imperial is a property of the reader

The parsers already normalise `ft`, `psi` and `F` away — canonical units are
metres, seconds, bar, Celsius, litres, whatever the exporting computer was set
to — so `SlateUnits` is a presentation choice made at the far end, in
`OverlayOptions`. A log written in feet and one written in metres produce the
same slate; which units that slate *speaks* is the diver's choice, not the
machine's.

- **Convert first, round second.** 44.4 m is 146 ft. Rounding up to 45 m and
  converting gives 148, which overstates the dive by nearly a metre — the badge
  would be claiming a depth nobody reached. `ceilFeet` rounds the converted
  value, and a test holds the ordering.
- **Both systems or neither, never per figure.** A slate mixing feet with litres
  is a set of numbers nobody's training pairs.
- **The units decide how a figure is printed, never whether it exists.**
  `availableStats` answers in metric for both, and the batch warning is built
  from it.
- **The survey's gridlines are chosen in the system they are labelled in.** A
  metric ladder converted to feet labels the map 33, 66, 98 — numbers no diver
  reads a depth as. `depthGridlines` takes the units and picks from a ladder per
  system; `metresOfDepth` converts back for placement, so the geometry stays
  canonical.
- **`cf`, not `cu ft` or `ft³`.** It is what a shop means by an 80, it is two
  characters in a column a corner badge gives 130px to, and the superscript is
  the one glyph `SlateFont`'s character-count estimate could not be trusted for.
  Consumption gets two decimals in cf/min against one in L/min, because the
  imperial figure is around 0.6 and one decimal rounds too many real dives
  together.
- **A three-digit depth is exactly the change that fits Wide and collides at
  Watch.** The figures share the width in equal columns, so the imperial
  cross-product is rendered in `SlateUnitsTest` and the largest emitted text
  measured — the same guard, and for the same reason, as the one that caught a
  style undercutting its layout.

## Exporting several dives at once

One selection, one settings object, one slate per dive. The parts worth knowing
are the ones that exist to stop a batch hiding something.

**The app holds `List<DiveLog>`, not a merged dive list.** Opening several files
makes provenance load-bearing: two exports overlapping is an ordinary accident,
and merged they become rows that look identical and cannot be told apart. There
is no honest key to de-duplicate on either — dive numbers are per-logbook and
collide across files — so nothing is de-duplicated and the list groups by file
instead. `DiveRef(log, dive)` is how a dive is addressed from there on.

**Everything the batch could hide is said before it happens**, because a slate
that is quietly absent, or quietly carrying fewer figures than the one on
screen, is indistinguishable from a log that never recorded the dive. That is
the same rule as derived figures degrading to nothing rather than to a guess,
applied to a screen instead of a number:

* A dive with no samples cannot be rendered, so **the list refuses it** with the
  reason on the row rather than letting it be selected and dropped at export.
  `Dive.blockedReason()` is asked before selection, never after a failure.
* Hand-picked figures the other dives lack are counted in the editor, from
  `availableStats(dive)` in core — "GF is missing on 3 of 6". Automatic figures
  need no warning: they already adapt per dive.
* A partial run reports `Saved 11 of 12` and the first reason, never a bare
  "done".

**Export runs on `Dispatchers.IO`, one bitmap at a time, and this is not
optional.** A slate at `EXPORT_SCALE` is roughly 3240×2436 of ARGB_8888 — some
thirty megabytes — and compressing it takes a visible moment. It used to run
inline on the click, which was a stutter for one dive and would be an ANR for
twenty. Rendering happens there too: `ExportRequest` carries *dives*, not
slates, so building the display lists lands on the background thread rather than
blocking the click that started it. Parallelising is the thing to refuse — four
threads means four live bitmaps, and the fourth is an OutOfMemoryError.

**A non-empty selection implies selection mode, and the list breaks in a
particular way when it does not.** The two are separate state, and the list
reads them in different places: the row tint follows the *selection*, while the
top bar, the dots and the action bar follow the *mode*. So clearing one without
the other does not produce a clean "nothing is selected" — it produces rows that
are visibly highlighted with no control anywhere admitting it, and a selection
that reappears intact the next time the user taps Select, seemingly from
nowhere. Opening a batch used to do exactly that. It no longer clears the mode:
opening the editor is not leaving the selection, the selection is *what the
editor is editing*, so backing out lands on the list exactly as it was left.
That also makes the back chain the three steps it always claimed to be — editor,
selection, list, start — instead of collapsing two of them on the way in.

**Names are deduplicated in `SlateFiles.exportNames`, not by MediaStore.** One
timestamp per batch, then dive number and site, so a batch sorts together in the
gallery. That puts the whole burden of uniqueness on the dive, which is not
enough on its own — the same dive can appear in two files — so repeats are
numbered here where it happens deliberately.

**Sharing several images needs every URI in `ClipData`**, not just the first.
The grant `addFlags` performs does not walk extras, so a batch where only the
leading image opens fails at the far end, where it cannot be diagnosed. Single
slates still go out as `ACTION_SEND`; plenty of receivers handle nothing else.

**A file's name comes from `OpenableColumns.DISPLAY_NAME`, never from the URI.**
`lastPathSegment` is a document id — Downloads hands over `msf:44` — which named
the list's file headings after provider numbering and threw the real extension
away before content detection ever saw it. It read correctly for exactly as long
as the test files were fresh enough to still be `raw:` paths.

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
- **`REQUEST_INSTALL_PACKAGES` is Play-restricted.** If this app ever goes to the
  Play Store, the self-updater goes with it.
- **Play Protect flags the self-updater, and that was weighed and accepted — do
  not remove it again.** Downloading a binary and asking Android to install it
  is, as *behaviour*, what a dropper does; behaviour is what gets scored, and a
  certificate with no history on a build with a handful of installs has nothing
  on the other side. 0.4.0 was refused with "Unsafe app blocked". 0.4.1 removed
  the permission and handed off to the browser instead; 0.4.3 put it back, at
  the user's preference, because two extra taps and a checksum demoted from
  enforced to displayed cost more than the warning does. The full hand-off
  implementation is in the history at `80d3473`, reverted by `git revert`, if it
  is ever wanted again.

  What the warning costs is worth knowing precisely, because the two variants
  are not the same: "Unsafe app blocked" can be tapped through with **More
  details → Install anyway**, but Google's enhanced fraud protection *blocks
  sideloading outright* for apps declaring this permission, and there is no
  override. Where that is enforced, a phone which has the app can never update
  it in place. That is the failure mode this trade accepts.
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

- **Only base hues ≈180–330° work** *for a palette built the Modern way*, which
  is one hue plus the fixed red. Warm and green bases collide with that red under
  CVD: green measures ΔE 24 to normal vision and **2.2** under protanopia. This
  still governs `build_theme` and the hue sweep; it is not a rule about colour in
  general, it is a rule about sharing a picture with a red ceiling.
- **The sRGB gamut is not a cylinder.** Asking for a fixed chroma across hues
  silently desaturates cyan below the chroma floor. `best_in_band` sweeps for the
  lightness where a hue holds the most chroma.

**The gates come in three profiles, and the profile is part of the verdict.**
"Passes" means nothing without saying which bar it cleared, so `PaletteReport`
carries the profile name and every palette records it — right through to
`SlateTheme.paletteProfile` in the app.

- `chromatic` — the original bar, unchanged, and still the default for a caller
  that does not think about it. Modern's nine.
- `expressive` — a widened lightness band, and **only** for a style that paints
  its own opaque card. The band exists because a mark on a transparent slate
  lands on footage of unknown brightness and has to survive both ends; a style
  that supplies its own background has already answered that. The separation
  floors do not move: nothing about an opaque card makes protanopia easier.
- `monochrome` — for a palette that is one ink on purpose. Every check that
  measures *difference between marks* is inapplicable rather than relaxed, so
  those report as info and the contrast floor turns fatal in exchange.

**The deco ceiling may now be re-coloured, and the argument that used to forbid
it has moved rather than been dropped.** It was fixed because a hazard colour
that shifts with the palette stops reading as a hazard. What changed is the
recognition that the colour was never carrying the hazard alone: the hatch says
*region you may not enter* and the stepped dash says *boundary*, and neither
depends on hue. So a style may substitute — and four do — but:

- **the substitute is measured against the card it lands on.** White on the
  violet card is fine; white on the yellow one measured 1.43:1 and went back to
  a red.
- **the hatch and the dash are not negotiable.** `SlateStyleTest` fails a style
  that hatches nothing, and fails a one-ink palette whose ceiling edge is solid.
  Keeping the colour freedom while dropping those marks would be taking the
  concession and throwing away what justified it.
- `CEILING_ARGB` is still the red Modern uses in all nine of its palettes, and
  the value any style should reach for when it keeps a red.

A gate cannot check that two marks differ in *shape*, which is why the
monochrome profile's promise is kept in the renderer's tests instead. Waiving a
colour gate without checking the substitute would just be loosening the rule and
calling it a profile.

Where a mark sits below 3:1 contrast, it is legal **only** because a text label
carries the identity — gas switches always print the mix name, in every style,
and a test checks each one. Don't drop those labels to reduce clutter.

### 4. Transparency is the product

No background is ever painted. Because the backdrop is unknown at render time,
all text is drawn twice — a halo stroke under the fill — and the slate adds a
scrim panel, since halos alone are not enough over video where the frame behind
a label changes constantly.

The opacity control moves the scrim and nothing else, clamped to a per-theme
floor computed from ink contrast against the worst possible backdrop. Fading the
marks would void the contrast the gates enforce and turn the hazard colour into a
suggestion. Two tests hold that line.

A style that paints its own opaque card paints it as *the scrim*, so the slider
still works and the floor still binds — on the violet card that floor is 0.91,
because lime ink needs nearly all of the card to clear 4.5:1 over white footage.
The canvas itself is never given a background; an opaque card is an op inside the
bounds, which is a different thing.

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

## The chrome is Material You; the slate is not

The app's own surfaces take a dynamic colour scheme from the wallpaper on
Android 12+, with a fixed blue scheme below that, and **follow the system
light/dark setting**.

That last part was the other way round for a long time — pinned dark, on the
argument that every screen is chrome around a transparent slate and a light
shell competes with the thing being judged. What makes following the phone safe
is that **the checkerboard does not follow it.** The backdrop behind the preview
is the stand-in for the footage, so it stays mid-grey in both modes: the slate is
judged against the same surface either way, and only the frame around it moves.
Had the checkerboard gone pale on a light phone, a light palette would have
looked right there and wrong on the video, which is exactly the confusion the
pinning was protecting against. It is not protecting anything now, so the
setting is honoured.

The mode of the *app* and the dark/light mode of the *slate* are unrelated and
must stay that way. The slate's is a statement about the footage it will land
on; the phone's is a statement about the phone. Deriving one from the other
would guess at footage nobody has described.

**A wallpaper colour must never reach the drawing.** The palettes in `Themes.kt`
were admitted by measured gates — OKLab ΔE, CVD simulation, contrast — against
the marks they paint. A colour derived from someone's home screen has cleared
none of that, and letting one in would void the whole argument in
`tools/palette.py`. Dynamic colour ends at buttons, chips and text.

There was a Material 3 *style* that looked like an exception to this and was
not: it drew a tonal card with M3's roles, but from seeds fixed at design time
and measured like every other palette. It has been removed. The rule it was
tempting is worth keeping in mind if anything like it returns — regenerating a
slate's palette from the wallpaper is the obvious next step and the one to
refuse. The chrome around the slate may follow the phone; the drawing may not.

Two smaller things that were got wrong once each:

- **`enableEdgeToEdge`'s bar styles and the app's own mode are one decision.**
  Auto picks icon colour from the *system* light/dark setting, so it is right
  now and was wrong while the app was pinned dark — on a light-themed phone that
  produced a dark clock on a near-black bar. Change one and change the other.
- **The launch window background is four files, not one.** `values/themes.xml`
  and `values-night/`, plus `values-v31/` and `values-night-v31/` because from
  API 31 the splash needs `windowSplashScreenBackground` set as well and does
  not fall back to `windowBackground`. Both qualifiers have to be on one folder:
  `values-night` alone loses to `values-v31` on an Android 12+ phone in dark
  mode. Keep the four in step with `background` in the fallback schemes, or the
  cold-start flash is the wrong colour for whichever half of the users the stale
  value is wrong for.
- **Insets are taken per screen, not by the root.** A `safeDrawingPadding` on the
  outermost Box stops the contextual selection bar short of the status bar,
  which reads as a floating strip rather than as the top of the screen.

## Conventions

- Optional log fields are null, never a sentinel zero — a dive with no recorded
  temperature must not render as a dive at 0 °C.
- **Never add a second y-axis.** Temperature, pressure and consumption go in the
  summary; depth is the only thing mapped to vertical space.
- The depth axis always starts at the surface.
- Slate figures round **up** — 44.4 m is a 45 m dive (`ceilMetres`,
  `ceilMinutes`), and in feet the conversion comes first: 146 ft, not 148
  (`ceilFeet`, `ceilDepth`).

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
