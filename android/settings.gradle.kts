// The core module is plain Kotlin/JVM on purpose: units, models, parsers and
// the slate layout are pure logic with no Android surface, so they build and
// test with nothing but a JDK. The app module depends on it and adds only the
// things that genuinely need a device — painting, intents, storage.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "diveslate"

include(":core")
include(":app")
