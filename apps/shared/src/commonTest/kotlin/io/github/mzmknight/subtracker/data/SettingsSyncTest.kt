package io.github.mzmknight.subtracker.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.core.Rates
import io.github.mzmknight.subtracker.db.SubTrackerDatabase
import io.github.mzmknight.subtracker.sync.ClockStore
import io.github.mzmknight.subtracker.sync.DeviceInfo
import io.github.mzmknight.subtracker.sync.Hlc

/**
 * Settings crossing between devices.
 *
 * The split under test is not "which settings exist" but *who each one is
 * about*. How far ahead to remind and what a euro is worth describe the user and
 * should follow them; whether this particular handset is allowed to buzz
 * describes the handset and must not. Both halves are asserted here, because a
 * regression in either direction is silent — settings that stop travelling look
 * like the user forgot to set them, and a switch that starts travelling turns a
 * quiet laptop noisy with no visible cause.
 */
class SettingsSyncTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private var deviceCounter = 0
    private var clock = 1_786_000_000_000L

    @BeforeTest
    fun setUp() {
        AppSettings.store = InMemorySettingsStore()
        AppSettings.forgetSyncedStore()
    }

    @AfterTest
    fun tearDown() {
        AppSettings.forgetSyncedStore()
        drivers.forEach { it.close() }
    }

    private class TestIdentity(override val id: String, override val name: String) :
        DeviceInfo, ClockStore {
        private var state: Hlc? = null
        override fun loadClockState(): Hlc? = state
        override fun saveClockState(state: Hlc) {
            this.state = state
        }
    }

    private fun newStore(): LocalStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SubTrackerDatabase.Schema.create(driver)
        drivers += driver
        val identity = TestIdentity("device${deviceCounter++}".padEnd(16, 'x'), "Device")
        // Each write lands on a later millisecond than the last, or two devices'
        // edits tie and the test ends up asserting the HLC tiebreak rather than
        // the behaviour it means to.
        return LocalStore(SubTrackerDatabase(driver), identity, identity) { clock++ }
    }

    // ------------------------------------------------------------ the rule

    @Test
    fun theReminderRuleFollowsTheUserToTheOtherDevice() {
        val phone = newStore()
        AppSettings.useSyncedStore(phone)
        AppSettings.reminderDaysBefore = 5
        AppSettings.reminderMinute = 8 * 60

        val laptop = newStore()
        laptop.applyIncoming(phone.exportAll())

        AppSettings.useSyncedStore(laptop)
        assertEquals(5, AppSettings.reminderDaysBefore)
        assertEquals(480, AppSettings.reminderMinute)
    }

    @Test
    fun theOnOffSwitchStaysOnTheDeviceItWasSetOn() {
        val phone = newStore()
        AppSettings.useSyncedStore(phone)
        AppSettings.remindersEnabled = true

        // Not merely outvoted at the far end — never sent at all.
        assertTrue(
            phone.exportAll().settings.none { it.id == "reminders_on" },
            "the on/off switch must not be in the sync payload",
        )
        assertNull(phone.setting("reminders_on"))
    }

    // ------------------------------------------------------------ currency

    @Test
    fun homeCurrencyAndHandEnteredRatesTravel() {
        val phone = newStore()
        AppSettings.useSyncedStore(phone)
        AppSettings.homeCurrency = "EUR"
        AppSettings.rates = Rates("EUR", mapOf("USD" to 0.92))

        val laptop = newStore()
        laptop.applyIncoming(phone.exportAll())

        AppSettings.useSyncedStore(laptop)
        assertEquals("EUR", AppSettings.homeCurrency)
        assertEquals(0.92, AppSettings.rates.manualRates["USD"])
    }

    @Test
    fun downloadedRatesDoNotTravel() {
        val phone = newStore()
        AppSettings.useSyncedStore(phone)
        AppSettings.rates = Rates(
            home = "GBP",
            manual = mapOf("USD" to 0.79),
            fetched = mapOf("SEK" to 0.075),
            fetchedAt = 1_786_000_000_000,
        )

        // A stale download travelling would give one device's old figures a way
        // to overwrite another's fresh ones on an unrelated settings edit.
        val sent = phone.exportAll().settings.map { it.id }
        assertTrue("currency_rates" in sent, "hand-entered rates travel")
        assertTrue("currency_rates_fetched" !in sent, "downloaded rates do not")
    }

    // ------------------------------------------------------------ merging

    @Test
    fun twoDevicesEditingDifferentSettingsBothKeepTheirChange() {
        // The reason settings are keyed separately rather than held as one blob:
        // under a single record last-write-wins would keep one edit and throw
        // the other away, with nothing on screen to say it had happened.
        val phone = newStore()
        AppSettings.useSyncedStore(phone)
        AppSettings.reminderDaysBefore = 7

        val laptop = newStore()
        AppSettings.useSyncedStore(laptop)
        AppSettings.homeCurrency = "USD"

        laptop.applyIncoming(phone.exportAll())
        phone.applyIncoming(laptop.exportAll())

        for (store in listOf(phone, laptop)) {
            AppSettings.useSyncedStore(store)
            assertEquals(7, AppSettings.reminderDaysBefore, "the phone's edit survived")
            assertEquals("USD", AppSettings.homeCurrency, "and so did the laptop's")
        }
    }

    @Test
    fun theLaterEditOfTheSameSettingWins() {
        val phone = newStore()
        val laptop = newStore()

        AppSettings.useSyncedStore(phone)
        AppSettings.reminderDaysBefore = 2
        AppSettings.useSyncedStore(laptop)
        AppSettings.reminderDaysBefore = 9

        phone.applyIncoming(laptop.exportAll())
        laptop.applyIncoming(phone.exportAll())

        for (store in listOf(phone, laptop)) {
            AppSettings.useSyncedStore(store)
            assertEquals(9, AppSettings.reminderDaysBefore)
        }
    }

    @Test
    fun syncingTwiceChangesNothingTheSecondTime() {
        val phone = newStore()
        AppSettings.useSyncedStore(phone)
        AppSettings.homeCurrency = "CHF"

        val laptop = newStore()
        assertEquals(1, laptop.applyIncoming(phone.exportAll()).settingsChanged)
        assertEquals(0, laptop.applyIncoming(phone.exportAll()).settingsChanged)
    }

    // ------------------------------------------------------------ upgrading

    @Test
    fun settingsChosenBeforeThisFeatureExistedStartTravelling() {
        // An upgrading device has months of preferences sitting in the local
        // store and an empty table. Without the carry-across they would read
        // fine on that device and never reach the other one.
        AppSettings.store = InMemorySettingsStore().apply {
            put("home_currency", "SEK")
            put("reminder_days", "6")
        }

        val phone = newStore()
        AppSettings.useSyncedStore(phone)

        val laptop = newStore()
        laptop.applyIncoming(phone.exportAll())

        AppSettings.store = InMemorySettingsStore()
        AppSettings.useSyncedStore(laptop)
        assertEquals("SEK", AppSettings.homeCurrency)
        assertEquals(6, AppSettings.reminderDaysBefore)
    }

    @Test
    fun aValueThatDidNotChangeIsNotRestamped() {
        // Opening the settings screen and leaving it alone must not hand this
        // device a newer stamp than one that actually changed something.
        val phone = newStore()
        AppSettings.useSyncedStore(phone)
        AppSettings.homeCurrency = "EUR"
        val first = phone.exportAll().settings.single { it.id == "home_currency" }.updatedAt

        AppSettings.homeCurrency = "EUR"
        val second = phone.exportAll().settings.single { it.id == "home_currency" }.updatedAt

        assertEquals(first, second)
    }
}
