// The core module is plain Kotlin/JVM on purpose: units, models and both
// parsers are pure logic with no Android surface, so they build and test with
// nothing but a JDK. The Android app module is added alongside it later and
// depends on this one — keeping the split means the bulk of the port stays
// testable without an emulator, a device, or the SDK.

rootProject.name = "diveslate"

include(":core")
