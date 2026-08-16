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

    // The conformance fixtures and the logs they describe live outside this
    // project, so Gradle does not see them as inputs and will report the tests
    // up to date after they change. That is exactly backwards for a suite whose
    // whole job is to notice when the fixtures and the code disagree — it once
    // reported success against a fixture that had just been regenerated to
    // expose a bug. Declaring them makes a fixture change invalidate the task.
    inputs.dir(rootProject.file("../conformance")).withPropertyName("conformance")
    inputs.dir(rootProject.file("../tests/data")).withPropertyName("logs")

    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
