package io.github.mzmknight.subtracker.data

import io.github.mzmknight.subtracker.core.Rates
import io.github.mzmknight.subtracker.core.ReminderRule
import io.github.mzmknight.subtracker.core.Reminders

/**
 * Tiny key/value persistence. Both targets are JVM but their idiomatic stores
 * differ (SharedPreferences vs java.util.prefs), so each app module supplies an
 * implementation at startup rather than the shared module guessing.
 */
interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/** Used until a platform store is installed, and by tests. */
class InMemorySettingsStore : SettingsStore {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) {
        values[key] = value
    }
}

/**
 * The half of the settings that follow the user between devices.
 *
 * Backed by the database rather than by [SettingsStore], because a synced value
 * needs the same envelope every other synced record has — a hybrid logical clock
 * stamp and a tombstone — and SharedPreferences has nowhere to put one.
 * Implemented by `LocalStore`.
 */
interface SyncedSettingsStore {
    fun setting(key: String): String?
    fun putSetting(key: String, value: String)
}

object AppSettings {
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_EMAIL = "email"

    var store: SettingsStore = InMemorySettingsStore()

    /**
     * Installed once the database is open. Until then — and in tests that never
     * install one — every setting falls back to the device-local [store], which
     * is exactly the behaviour this app had before settings synced.
     */
    private var synced: SyncedSettingsStore? = null

    /**
     * Point the synced settings at the database, carrying across anything this
     * device had already chosen.
     *
     * The carry-across is the whole reason this is a function rather than a
     * plain setter. On upgrade the table is empty while the device may have had
     * a home currency and a reminder rule set for months; without seeding, those
     * would read fine locally but never travel, so a second device would sit on
     * the defaults until the user happened to change something.
     */
    fun useSyncedStore(target: SyncedSettingsStore) {
        for (key in SYNCED_KEYS) {
            if (target.setting(key) == null) {
                store.get(key)?.takeIf { it.isNotBlank() }?.let { target.putSetting(key, it) }
            }
        }
        synced = target
    }

    /** For tests, and for a sign-out that puts the device back to a clean slate. */
    fun forgetSyncedStore() {
        synced = null
    }

    private fun syncedGet(key: String): String? = synced?.setting(key) ?: store.get(key)

    private fun syncedPut(key: String, value: String) {
        val target = synced
        if (target == null) store.put(key, value) else target.putSetting(key, value)
    }

    /** e.g. http://100.x.y.z:8090 — the Tailscale address of the LXC. */
    var baseUrl: String
        get() = store.get(KEY_BASE_URL).orEmpty()
        set(value) = store.put(KEY_BASE_URL, value.trim().trimEnd('/'))

    /**
     * PocketBase auth tokens are long-lived, so caching one means the app opens
     * straight into the dashboard instead of a login form every time.
     */
    var token: String
        get() = store.get(KEY_TOKEN).orEmpty()
        set(value) = store.put(KEY_TOKEN, value)

    var email: String
        get() = store.get(KEY_EMAIL).orEmpty()
        set(value) = store.put(KEY_EMAIL, value)

    /**
     * The device works with no server, so this is only used when talking to one.
     */
    val hasServer: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()

    private const val KEY_HOME_CURRENCY = "home_currency"
    private const val KEY_RATES = "currency_rates"
    private const val KEY_RATES_FETCHED = "currency_rates_fetched"
    private const val KEY_RATES_FETCHED_AT = "currency_rates_fetched_at"

    var homeCurrency: String
        get() = syncedGet(KEY_HOME_CURRENCY).orEmpty().ifBlank { "GBP" }
        set(value) = syncedPut(KEY_HOME_CURRENCY, value.trim().uppercase())

    /**
     * Conversion rates, as `USD=0.79` entries.
     *
     * Hand-entered rates sync; downloaded ones do not. A rate you typed in is
     * work you should not have to repeat on the next device. A downloaded set is
     * reproducible by any device with a network and carries its own fetch time,
     * so syncing it would only add a way for one device's stale download to
     * overwrite another's fresh one — each key is a single last-write-wins
     * contest, and the loser's value is gone.
     */
    var rates: Rates
        get() = Rates(
            home = homeCurrency,
            manual = readRates(syncedGet(KEY_RATES)),
            fetched = readRates(store.get(KEY_RATES_FETCHED)),
            fetchedAt = store.get(KEY_RATES_FETCHED_AT)?.toLongOrNull() ?: 0L,
        )
        set(value) {
            // Stored apart so a refresh can replace the downloaded set without
            // touching anything typed in by hand.
            syncedPut(KEY_RATES, writeRates(value.manualRates))
            store.put(KEY_RATES_FETCHED, writeRates(value.fetchedRates))
            store.put(KEY_RATES_FETCHED_AT, value.fetchedAt.toString())
        }

    /**
     * Written with ';' but read with either separator: the newline form is what
     * this app wrote before rates synced, and an upgrading device has a store
     * full of it.
     */
    private fun readRates(raw: String?): Map<String, Double> =
        raw.orEmpty()
            .split(';', '\n')
            .mapNotNull { line ->
                val parts = line.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val rate = Rates.parseRate(parts[1]) ?: return@mapNotNull null
                parts[0].trim().uppercase() to rate
            }
            .toMap()

    // ';' rather than a newline, so the value survives a spreadsheet round trip
    // as one ordinary cell of the CSV backup instead of a multi-line one.
    private fun writeRates(rates: Map<String, Double>): String =
        rates.entries.joinToString(";") { "${it.key}=${Rates.formatRate(it.value)}" }

    // ---------------------------------------------------------- reminders
    //
    // Split deliberately, along the line between the user and the machine.
    //
    // The *rule* — how many days ahead, at what time — describes how you like to
    // be reminded, so it syncs and a new device inherits it instead of starting
    // on the defaults. The on/off *switch* stays device-local, because wanting
    // your phone to nag and your laptop to stay quiet is entirely reasonable,
    // and a synced switch would make that impossible to express.
    //
    // Per-subscription rules live on the subscription itself and have always
    // synced: "never remind me about this one" is a fact about the subscription,
    // not about the device you happened to say it on.

    private const val KEY_REMINDERS_ON = "reminders_on"
    private const val KEY_REMINDER_DAYS = "reminder_days"
    private const val KEY_REMINDER_MINUTE = "reminder_minute"

    /** Off until asked for: an app that starts notifying uninvited is a bad guest. */
    var remindersEnabled: Boolean
        get() = store.get(KEY_REMINDERS_ON) == "1"
        set(value) = store.put(KEY_REMINDERS_ON, if (value) "1" else "0")

    var reminderDaysBefore: Int
        get() = syncedGet(KEY_REMINDER_DAYS)?.toIntOrNull() ?: Reminders.DEFAULT.daysBefore
        set(value) =
            syncedPut(KEY_REMINDER_DAYS, value.coerceIn(0, ReminderRule.MAX_DAYS_BEFORE).toString())

    /** Minutes since local midnight. */
    var reminderMinute: Int
        get() = syncedGet(KEY_REMINDER_MINUTE)?.toIntOrNull() ?: Reminders.DEFAULT.minute
        set(value) = syncedPut(KEY_REMINDER_MINUTE, value.coerceIn(0, 24 * 60 - 1).toString())

    val reminderRule: ReminderRule
        get() = ReminderRule.of(reminderDaysBefore, reminderMinute)

    /**
     * Reminders already announced, so an alarm that fires twice — on relaunch,
     * after a reboot — does not say the same thing again.
     *
     * Trimmed rather than grown forever: only the most recent matter, since a
     * charge that has passed can never come round again with the same key.
     */
    private const val KEY_ANNOUNCED = "reminders_announced"
    private const val MAX_ANNOUNCED = 100

    var announcedReminders: Set<String>
        get() = store.get(KEY_ANNOUNCED).orEmpty().split('\n').filter { it.isNotBlank() }.toSet()
        set(value) = store.put(KEY_ANNOUNCED, value.toList().takeLast(MAX_ANNOUNCED).joinToString("\n"))

    fun markAnnounced(keys: Collection<String>) {
        if (keys.isEmpty()) return
        announcedReminders = announcedReminders + keys
    }

    /**
     * Exactly the settings that travel between devices.
     *
     * Everything else in this object is about *this* device — whether it may
     * notify, which server it talks to, which rates it last downloaded, which
     * reminders it has already announced — and stays where it is.
     */
    private val SYNCED_KEYS = listOf(
        KEY_HOME_CURRENCY, KEY_RATES, KEY_REMINDER_DAYS, KEY_REMINDER_MINUTE,
    )

    fun signOut() {
        store.put(KEY_TOKEN, "")
    }
}
