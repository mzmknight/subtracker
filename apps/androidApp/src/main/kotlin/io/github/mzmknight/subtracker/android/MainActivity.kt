package io.github.mzmknight.subtracker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.mzmknight.subtracker.data.Notifications
import io.github.mzmknight.subtracker.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The database, settings store and notifier are installed in
        // SubTrackerApp, because the alarm and boot receivers need them too and
        // run in a process that may contain no Activity at all.
        //
        // The notifier is rebound to *this* Activity purely so it can raise the
        // Android 13 permission prompt, which only an Activity may do.
        Notifications.notifier = AndroidNotifier(this)
        enableEdgeToEdge()
        setContent { App() }
    }
}
