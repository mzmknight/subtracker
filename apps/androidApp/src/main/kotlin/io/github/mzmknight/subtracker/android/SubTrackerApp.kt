package io.github.mzmknight.subtracker.android

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.mzmknight.subtracker.data.AppSettings
import io.github.mzmknight.subtracker.data.LocalStore
import io.github.mzmknight.subtracker.data.Notifications
import io.github.mzmknight.subtracker.data.ReminderScheduler
import io.github.mzmknight.subtracker.data.SettingsStore
import io.github.mzmknight.subtracker.db.Database
import io.github.mzmknight.subtracker.sync.Discovery
import io.github.mzmknight.subtracker.sync.NsdDiscovery

internal class PrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("subtracker", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

/**
 * Wiring, in Application rather than in MainActivity.
 *
 * The reminder alarm and the boot receiver both run in a process with no
 * Activity in it — after a reboot there may never have been one. Installing the
 * database and settings store from MainActivity meant those entry points found
 * an uninitialised app and could not read a single subscription.
 */
class SubTrackerApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        AppSettings.store = PrefsSettingsStore(this)
        Database.install(AndroidDriverFactory(this))
        Discovery.factory = { NsdDiscovery(this) }
        Notifications.notifier = AndroidNotifier(this)
    }

    /** Shared by the alarm and boot receivers, off the main thread. */
    fun refreshReminders(onDone: () -> Unit = {}) {
        scope.launch {
            try {
                ReminderScheduler.refresh(LocalStore(Database.get()))
            } finally {
                onDone()
            }
        }
    }
}

/** Entry point for both the alarm and BOOT_COMPLETED. */
object ReminderWork {
    fun runAndReschedule(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext as? SubTrackerApp
        if (app == null) {
            onDone()
            return
        }
        app.refreshReminders(onDone)
    }
}
