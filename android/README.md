# diveslate for Android

The Kotlin reimplementation. Goal: from a Subsurface-mobile export to an
Instagram story with the slate composited over your footage, in about three taps.

## Layout

```
core/   plain Kotlin/JVM — units, models, parsers, layout. No Android surface,
        so it builds and tests with nothing but a JDK.
app/    the Android application (not yet present)
```

The split is deliberate. Everything that can be verified without a device
should be, and that turns out to be most of the interesting code.

## Build

```bash
./gradlew core:test
```

Needs a JDK 21 on `JAVA_HOME`. Nothing else — the wrapper fetches its own
Gradle, and the core module does not touch the Android SDK.

## The conformance tests are the point

`core` is held to `../conformance/`, generated from the Python implementation by
`tools/export_oracle.py` and `tools/export_theme_tokens.py`. Those fixtures carry
the full parsed model for every log in `tests/data`, every derived figure, and a
table-driven spec for the unit grammar — recording input that must be *refused*
alongside input that must convert.

This is how the decisions the Python earned survive the rewrite. Sparse-sample
carry-forward, deco time measured as the hang rather than the obligation, the
refusal to invent a gradient factor a VPM-B dive never had: none of that is
recoverable from reading the code, and all of it cost real debugging.

**When a conformance test fails, fix the Kotlin.** Regenerating the fixture to
turn a test green discards the specification and keeps the bug.

## Toolchain notes

Installation on Windows was awkward and the failures were silent, so for the
record: winget's `Google.AndroidStudio` package reports `Successfully installed`
and exits 0 without installing anything — verify with `winget list`, not the
exit code. Anything needing elevation fails in this environment, so the SDK
lives under `%LOCALAPPDATA%\Android\Sdk`, installed via the command-line tools
rather than Studio. Android Studio is not required to build; it is only needed
if you want the IDE and emulator.
