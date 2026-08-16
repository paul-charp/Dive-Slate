import java.util.Properties

// AGP 9 carries Kotlin support itself and refuses the standalone
// org.jetbrains.kotlin.android plugin alongside it.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing, read from local.properties (gitignored) when present.
 *
 * Absent, the release build falls back to the debug key so `assembleRelease`
 * still yields something installable. That is fine for sideloading onto your
 * own phone and useless for distribution: every SDK install shares the same
 * debug key, so an app signed with it cannot be updated by one signed with a
 * real key later, and Play will not accept it.
 *
 * To sign properly, generate a keystore yourself — the password stays with you,
 * never in the repo:
 *
 *   keytool -genkeypair -v -keystore diveslate.jks -keyalg RSA -keysize 2048 \
 *           -validity 10000 -alias diveslate
 *
 * then add to local.properties:
 *
 *   storeFile=/absolute/path/diveslate.jks
 *   storePassword=...
 *   keyAlias=diveslate
 *   keyPassword=...
 */
val releaseKeystore: Properties? =
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
        ?.takeIf { it.getProperty("storeFile") != null }

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

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore.getProperty("storeFile"))
                storePassword = releaseKeystore.getProperty("storePassword")
                keyAlias = releaseKeystore.getProperty("keyAlias")
                keyPassword = releaseKeystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")

            // R8 on. The app is small and reflection-free — kotlinx-serialization
            // is test-only and never reaches the APK — so there is nothing here
            // for shrinking to break. Verified by installing the shrunk build and
            // exercising it, not by assuming.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
