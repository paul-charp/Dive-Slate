plugins {
    kotlin("jvm") version "2.2.20"
}

repositories {
    mavenCentral()
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
    // 21 matches the JDK installed for this project. Android's toolchain is
    // configured separately in the app module, so this does not constrain it.
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
