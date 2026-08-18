package io.github.mzmknight.subtracker.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.core.PlainDate
import io.github.mzmknight.subtracker.db.SubTrackerDatabase
import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.ClockStore
import io.github.mzmknight.subtracker.sync.DeviceInfo
import io.github.mzmknight.subtracker.sync.Hlc
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * Exercised against a real in-memory SQLite rather than a fake, so the schema,
 * the upserts and the transactions are all genuinely under test.
 */
class LocalStoreTest {

    private val today = PlainDate.parse("2026-08-09")
    private lateinit var drivers: MutableList<JdbcSqliteDriver>

    @BeforeTest
    fun setUp() {
        drivers = mutableListOf()
        AppSettings.store = InMemorySettingsStore()
    }

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
    }

    /**
     * A device's own identity and clock state. Each simulated device needs its
     * own, or they share the HLC tiebreak and clock and stop being independent.
     */
    private class TestIdentity(override val id: String, override val name: String) :
        DeviceInfo, ClockStore {
        private var state: Hlc? = null
        override fun loadClockState(): Hlc? = state
        override fun saveClockState(state: Hlc) {
            this.state = state
        }
    }

    private var deviceCounter = 0

    /** A fresh, isolated database and identity — one per simulated device. */
    private fun newStore(clock: () -> Long = { 1_786_000_000_000 }): LocalStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SubTrackerDatabase.Schema.create(driver)
        drivers += driver
        // Fixed width: this is the HLC's last component and sorting depends on it.
        val identity = TestIdentity("device${deviceCounter++}".padEnd(16, 'x'), "Device")
        return LocalStore(SubTrackerDatabase(driver), identity, identity, clock)
    }

    private fun draft(name: String, anchor: String = "2026-01-31") = SubscriptionRecord(
        id = "",
        updatedAt = "",
        name = name,
        anchorDate = anchor,
        cycleUnit = "month",
        cycleCount = 1,
        status = "active",
        currency = "GBP",
        category = "entertainment",
    )

    @Test
    fun aSubscriptionCanBeCreatedWithNoServerAndImmediatelyHasASchedule() {
        val store = newStore()
        assertTrue(store.isEmpty())

        val created = store.createSubscription(draft("Netflix"), openingAmountMinor = 1099)

        assertTrue(created.id.isNotBlank(), "an id is minted on the device")
        assertTrue(created.updatedAt.isNotBlank(), "and stamped with the clock")
        assertEquals(1, store.subscriptions().size)

        // The whole point: the schedule exists without anything having synced.
        val charges = store.chargesFor(created.id, today)
        assertTrue(charges.size > 30, "a projection was computed locally")
        assertTrue(charges.any { it.due.iso == "2026-02-28" }, "month-end clamping applied")
        assertTrue(charges.any { it.due.iso == "2026-03-31" }, "and did not become permanent")
        assertEquals(1099, charges.first().amountMinor)
    }

    @Test
    fun figuresAreComputedFromLocalDataAlone() {
        val store = newStore()
        store.createSubscription(draft("Netflix", "2026-01-31"), 1099)
        store.createSubscription(draft("Spotify", "2026-03-15"), 1199)

        val figures = store.figures(today)
        assertEquals(2298, figures.monthlyRunRateMinor, "1099 + 1199")
        assertEquals(2, figures.activeCount)
        assertTrue(figures.monthlySeries.isNotEmpty())
    }

    @Test
    fun aDeletedSubscriptionLeavesATombstoneRatherThanVanishing() {
        val store = newStore()
        val created = store.createSubscription(draft("Netflix"), 1099)

        store.deleteSubscription(created.id)

        assertEquals(0, store.subscriptions().size, "gone from the UI")
        assertNull(store.subscription(created.id))
        val tombstone = store.allSubscriptions().single { it.id == created.id }
        assertTrue(tombstone.deleted, "but still present for the merge, or a peer would revive it")
    }

    @Test
    fun overridesReplaceRatherThanAccumulate() {
        val store = newStore()
        val created = store.createSubscription(draft("Netflix"), 1099)

        store.setOverride(created.id, "2026-08-31", ChargeOverrideRecord.KIND_SKIPPED)
        store.setOverride(created.id, "2026-08-31", ChargeOverrideRecord.KIND_AMOUNT, 500)

        val live = store.allOverrides().filterNot { it.deleted }
        assertEquals(1, live.size, "the derived id means one record, not two rivals")
        assertEquals(ChargeOverrideRecord.KIND_AMOUNT, live.single().kind)

        val august = store.chargesFor(created.id, today).single { it.due.iso == "2026-08-31" }
        assertEquals(500, august.effectiveAmountMinor)

        store.clearOverride(created.id, "2026-08-31")
        val cleared = store.chargesFor(created.id, today).single { it.due.iso == "2026-08-31" }
        assertEquals(1099, cleared.effectiveAmountMinor, "back to the generated amount")
    }

    @Test
    fun aPriceRiseRepricesOnlyLaterCharges() {
        val store = newStore()
        val created = store.createSubscription(draft("Spotify", "2026-03-15"), 1199)
        store.addPrice(created.id, 1299, "2026-07-01")

        val charges = store.chargesFor(created.id, today).associateBy { it.due.iso }
        assertEquals(1199, charges.getValue("2026-06-15").amountMinor)
        assertEquals(1299, charges.getValue("2026-07-15").amountMinor)
    }

    @Test
    fun twoDevicesConvergeWithNoServerInvolved() {
        // The scenario the whole feature exists for: a friend's phone and laptop,
        // or your own devices while the server is down.
        var phoneTime = 1_786_000_000_000L
        var laptopTime = 1_786_000_000_000L
        val phone = newStore { phoneTime }
        val laptop = newStore { laptopTime }

        val netflix = phone.createSubscription(draft("Netflix", "2026-01-31"), 1099)
        laptopTime += 1000
        val spotify = laptop.createSubscription(draft("Spotify", "2026-03-15"), 1199)

        // A full exchange in both directions.
        val fromPhone = phone.exportAll()
        val laptopReport = laptop.applyIncoming(fromPhone)
        val fromLaptop = laptop.exportAll()
        val phoneReport = phone.applyIncoming(fromLaptop)

        assertTrue(laptopReport.changed)
        assertTrue(phoneReport.changed)

        val phoneNames = phone.subscriptions().map { it.name }.sorted()
        val laptopNames = laptop.subscriptions().map { it.name }.sorted()
        assertEquals(listOf("Netflix", "Spotify"), phoneNames)
        assertEquals(phoneNames, laptopNames)

        // And both agree on the money, having each recomputed it independently.
        assertEquals(
            phone.figures(today).monthlyRunRateMinor,
            laptop.figures(today).monthlyRunRateMinor,
        )
        assertEquals(2298, phone.figures(today).monthlyRunRateMinor)
        assertNotNull(phone.subscription(spotify.id))
        assertNotNull(laptop.subscription(netflix.id))
    }

    @Test
    fun aDeleteOnOneDeviceSurvivesSyncingWithAStaleOne() {
        var phoneTime = 1_786_000_000_000L
        val phone = newStore { phoneTime }
        val laptop = newStore { 1_786_000_000_000L }

        val created = phone.createSubscription(draft("Netflix"), 1099)
        laptop.applyIncoming(phone.exportAll())
        assertEquals(1, laptop.subscriptions().size)

        phoneTime += 5000
        phone.deleteSubscription(created.id)

        // The laptop still holds the live copy and syncs both ways.
        laptop.applyIncoming(phone.exportAll())
        phone.applyIncoming(laptop.exportAll())

        assertEquals(0, laptop.subscriptions().size, "the delete propagated")
        assertEquals(0, phone.subscriptions().size, "and was not resurrected by the stale copy")
    }

    @Test
    fun theLaterEditWinsWhenBothDevicesChangeTheSameSubscription() {
        var phoneTime = 1_786_000_000_000L
        var laptopTime = 1_786_000_000_000L
        val phone = newStore { phoneTime }
        val laptop = newStore { laptopTime }

        val created = phone.createSubscription(draft("Netflix"), 1099)
        laptop.applyIncoming(phone.exportAll())

        phoneTime += 1000
        phone.updateSubscription(phone.subscription(created.id)!!.copy(name = "Netflix Standard"))
        laptopTime += 9000
        laptop.updateSubscription(laptop.subscription(created.id)!!.copy(name = "Netflix Premium"))

        laptop.applyIncoming(phone.exportAll())
        phone.applyIncoming(laptop.exportAll())

        assertEquals("Netflix Premium", phone.subscription(created.id)?.name)
        assertEquals("Netflix Premium", laptop.subscription(created.id)?.name)
    }

    @Test
    fun syncingTwiceChangesNothingTheSecondTime() {
        val phone = newStore()
        val laptop = newStore()
        phone.createSubscription(draft("Netflix"), 1099)

        val first = laptop.applyIncoming(phone.exportAll())
        val second = laptop.applyIncoming(phone.exportAll())

        assertTrue(first.changed)
        assertTrue(!second.changed, "a repeat sync must be a no-op")
        assertEquals(0, second.received)
    }

    @Test
    fun changesForReportsOnlyWhatThePeerIsMissing() {
        val phone = newStore()
        val laptop = newStore()
        phone.createSubscription(draft("Netflix"), 1099)

        val everything = phone.changesFor(SyncPayloadEmpty)
        assertEquals(2, everything.recordCount, "a subscription and its opening price")

        laptop.applyIncoming(phone.exportAll())
        val nothingLeft = phone.changesFor(laptop.exportAll())
        assertEquals(0, nothingLeft.recordCount)
    }

    private val SyncPayloadEmpty get() = io.github.mzmknight.subtracker.sync.SyncPayload()
}
