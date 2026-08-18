import java.util.Properties

plugins {
    // No kotlin.android plugin here on purpose: AGP 9 provides Kotlin support
    // itself, and the standalone org.jetbrains.kotlin.android plugin is not
    // compatible with it (it still expects the removed BaseExtension).
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing, read from ../../signing/keystore.properties.
 *
 * Kept out of this file so the password is not in source. Absent on a machine
 * that has not got the key — in which case the release build stays unsigned
 * rather than failing, so `assembleDebug` and the desktop build still work for
 * anyone who only wants to run the thing.
 */
val signingProps = rootProject.file("../signing/keystore.properties").let { file ->
    if (file.exists()) Properties().apply { file.inputStream().use { load(it) } } else null
}

android {
    namespace = "io.github.mzmknight.subtracker"
    // 37 because androidx.lifecycle 2.11.0 requires it. Compiling against newer
    // APIs is independent of targetSdk, which stays at 36 so runtime behaviour
    // doesn't change.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.mzmknight.subtracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = rootProject.file("../signing/${signingProps.getProperty("storeFile")}")
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately. The app is sideloaded, so nothing is gained
            // from a smaller APK, and turning R8 on without keep rules for
            // SQLDelight, Ktor and kotlinx.serialization is a good way to ship a
            // build that only fails at runtime.
            optimization {
                enable = false
            }
            // Unsigned when the key is absent, which fails to install with
            // INSTALL_PARSE_FAILED_NO_CERTIFICATES rather than anything helpful —
            // so it is worth being explicit that the key is the thing missing.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    // The shared module declares these as `implementation`, so they are not
    // visible here — the app module supplies its own driver.
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.runtime)
}
