plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(kotlin("test"))
    // Tree-parsing only, to read the conformance fixtures. No @Serializable
    // classes, so the serialization compiler plugin is not needed — and the
    // fixtures are deliberately read as untyped JSON, since a typed mirror of
    // them would drift alongside the code it is supposed to be checking.
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    // 21 for the JVM tests. The app module sets its own Android target, so this
    // does not constrain what the phone runs.
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
