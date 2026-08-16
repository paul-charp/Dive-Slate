// Plugin versions are declared once here and applied without a version in the
// subprojects. Declaring them per-module loads the Kotlin plugin twice, which
// Gradle warns is unsupported and may break the build.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
