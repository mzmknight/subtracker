import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // Not com.android.library — see the note in libs.versions.toml. This plugin
    // creates the android target itself, so there is no androidTarget() call
    // and no top-level android { } block.
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("SubTrackerDatabase") {
            packageName.set("io.github.mzmknight.subtracker.db")
        }
    }
}

kotlin {
    // `androidLibrary { }` is the deprecated spelling of this same block.
    android {
        namespace = "io.github.mzmknight.subtracker.shared"
        // 37 because androidx.lifecycle 2.11.0 refuses to be compiled against
        // anything older. Unrelated to targetSdk, which stays at 36.
        compileSdk = 37
        minSdk = 26
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            // The app *is* the sync server: a peer connects directly to it, so
            // there is no server anywhere in a two-device setup. CIO is pure
            // Kotlin and runs on both Android and the JVM desktop.
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.content.negotiation)
            // QR encoding on both platforms: either device can be the one holding
            // up the code, so both need to draw it.
            implementation(libs.zxing.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            // In-memory JDBC driver, so the repository is tested against a real
            // SQLite rather than a stand-in that behaves differently.
            implementation(libs.sqldelight.sqlite.driver)
        }

        val desktopTest by getting
        desktopTest.dependencies {
            // zxing's javase module reads a QR back out of an image, so a
            // generated code can be proven scannable without a camera.
            implementation(libs.zxing.javase)
        }

        // OkHttp on both: it is a well-proven engine, and it fails
        // with a clean timeout when Tailscale isn't connected.
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            // For BackHandler and the camera permission request.
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.camerax.camera2)
            implementation(libs.camerax.lifecycle)
            implementation(libs.camerax.view)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.sqldelight.sqlite.driver)
            // Android has NsdManager built in; the desktop needs its own mDNS.
            implementation(libs.jmdns)
        }
    }
}
