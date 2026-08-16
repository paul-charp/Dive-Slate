// AGP 9 carries Kotlin support itself and refuses the standalone
// org.jetbrains.kotlin.android plugin alongside it.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.paulcharp.diveslate"
    // 37 is what the AndroidX libraries in use demand; targetSdk stays at the
    // behaviour level actually tested against.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.paulcharp.diveslate"
        // 29 for scoped storage: saving to the gallery via MediaStore needs
        // RELATIVE_PATH and IS_PENDING, and the alternative below that is a
        // runtime permission plus deprecated file APIs. Android 10 is a fair
        // floor for a phone that also runs Subsurface-mobile and Instagram.
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
