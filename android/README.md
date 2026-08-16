# diveslate for Android

The Kotlin reimplementation, and the app around it. Goal: from a
Subsurface-mobile export to an Instagram story with the slate over your footage,
in about three taps.

## Layout

```
core/   plain Kotlin/JVM — units, models, both parsers, content sniffing,
        palettes, and the slate layout. No Android surface, so it builds and
        tests with nothing but a JDK.
app/    Compose UI, the Canvas painter, share intake, MediaStore export.
```

The split is deliberate. Everything verifiable without a device should be, and
that turns out to be most of the interesting code — including the slate's
geometry, which `core` emits as a display list that `app` merely paints.

## Build

```bash
./gradlew core:test          # 40 tests, no device
./gradlew :app:installDebug  # onto a running emulator or device
./gradlew :app:assembleRelease
```

Needs JDK 21 on `JAVA_HOME`. The wrapper fetches its own Gradle; the core module
does not touch the Android SDK at all.

The release APK builds **unsigned**. Signing needs a keystore you generate
yourself (`keytool -genkeypair`) plus a `signingConfigs` block reading from
`local.properties`, which is gitignored.

## What the app does

Receives a `.ssrf` or `.uddf` through a share intent (or opens the bundled
sample), previews the slate over a checkerboard, and exports it either to
Instagram as a story sticker or to the gallery as a transparent PNG.

Adjustable: palette (nine, grouped by the surface each was validated against),
wide or tall layout, which elements appear, which figures are shown, and the
scrim panel's opacity.

**The opacity control moves the panel and nothing else.** Ink is never faded,
and the slider is clamped to a per-theme floor computed from ink contrast
against the worst possible backdrop. Fading the marks would void the contrast
the palette gates enforce and turn the deliberately-unthemed hazard red into a
pink suggestion. Two tests hold that line.

## The conformance tests are the point

`core` is held to `../conformance/`, generated from the Python implementation by
the exporters in `../tools/`. Those fixtures carry the full parsed model for
every log in `tests/data`, every derived figure, a table-driven spec for the
unit grammar — recording input that must be *refused* alongside input that must
convert — and synthetic deco profiles that no real log in the corpus exercises.

This is how the decisions the Python earned survive the rewrite. Sparse-sample
carry-forward, deco time measured as the hang rather than the obligation, the
refusal to invent a gradient factor a VPM-B dive never had: none of it is
recoverable from reading the code, and all of it cost real debugging.

**When a conformance test fails, fix the Kotlin.** Regenerating the fixture to
turn a test green discards the specification and keeps the bug.

## Deliberate divergences from the Python

Recorded so nobody "fixes" them back. The reasoning is in CLAUDE.md.

| | |
|---|---|
| DOCTYPE declarations refused outright | the app is handed files by other apps |
| UDDF deco stop without a depth → null, not NaN | NaN is truthy and poisons ceiling comparisons |
| Mix figure labelled "Gases", comma-joined | a slash reads as a ratio beside `GF 70/80` |

## Toolchain notes

Setting this up on Windows was awkward and the failures were silent, so for the
record:

- **winget's `Google.AndroidStudio` reports `Successfully installed` and exits 0
  without installing anything.** Verify with `winget list`, never the exit code.
- Anything needing elevation failed in this environment. The SDK therefore lives
  under `%LOCALAPPDATA%\Android\Sdk`, installed via the command-line tools rather
  than Studio.
- Android Studio is not required to build. It is only needed for the IDE and the
  emulator GUI.
- `sdkmanager` prints a deprecation notice pointing at an `android` CLI whose
  installer fails with access-denied here. The notice can be ignored.

## Known gaps

- Settings do not persist across launches.
- `ADD_TO_STORY` is untested end to end; only the fallback share path has run,
  because Instagram is not installed on the emulator.
- No background-media picker, so the palette cannot yet be judged against your
  own footage.
