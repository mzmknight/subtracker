package io.github.mzmknight.subtracker.desktop

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.mzmknight.subtracker.data.AppSettings
import io.github.mzmknight.subtracker.data.Notifications
import io.github.mzmknight.subtracker.db.Database
import io.github.mzmknight.subtracker.sync.Discovery
import io.github.mzmknight.subtracker.sync.JmdnsDiscovery
import io.github.mzmknight.subtracker.ui.App

/**
 * Where a packaged build reports why it failed to start.
 *
 * jpackage produces a windowed executable with no console, so anything thrown
 * during startup vanishes and the app simply never appears — which is exactly
 * what happened the first time this was packaged (a jlink runtime missing
 * java.sql). Writing it to a file makes a silent failure diagnosable.
 */
private fun recordFailure(stage: String, error: Throwable) {
    runCatching {
        AppPaths.startupErrorFile.writeText(
            buildString {
                appendLine("SubTracker failed during: $stage")
                appendLine(java.time.LocalDateTime.now().toString())
                appendLine("Data location: ${AppPaths.describe()}")
                appendLine()
                appendLine(error.stackTraceToString())
            }
        )
    }
}

fun main() {
    Thread.setDefaultUncaughtExceptionHandler { _, error -> recordFailure("running", error) }

    try {
        // Settings live in a file beside the database, not the Windows registry:
        // a portable copy must leave nothing behind on the machine it ran on.
        AppSettings.store = FileSettingsStore(AppPaths.settingsFile)
        Database.install(DesktopDriverFactory())
        Discovery.factory = { JmdnsDiscovery() }
        Notifications.notifier = DesktopNotifier()
        println("[subtracker] data: ${AppPaths.describe()}")
    } catch (error: Throwable) {
        recordFailure("startup", error)
        throw error
    }

    application {
        val state = rememberWindowState(size = DpSize(1120.dp, 780.dp))
        Window(
            onCloseRequest = ::exitApplication,
            state = state,
            title = if (AppPaths.isPortable) "SubTracker (portable)" else "SubTracker",
            // The taskbar button of a *running* app shows its window icon, not
            // the executable's — and the JVM's default for that is the Java
            // coffee cup. The .ico set in build.gradle.kts covers the exe, the
            // shortcut and the Start menu; this covers the window.
            icon = painterResource("subtracker-icon.png"),
        ) {
            App()
        }
    }
}
