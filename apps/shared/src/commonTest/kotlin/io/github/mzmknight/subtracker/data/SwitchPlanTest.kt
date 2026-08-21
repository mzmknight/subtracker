package io.github.mzmknight.subtracker.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.core.PlainDate
import io.github.mzmknight.subtracker.db.SubTrackerDatabase
import io.github.mzmknight.subtracker.sync.ClockStore
import io.github.mzmknight.subtracker.sync.DeviceInfo
import io.github.mzmknight.subtracker.sync.Hlc
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * Switching a subscription from one billing cycle to another.
 *
 * The bug this exists to prevent is not a crash. `cycle_unit` is a column on the
 * subscription rather than a versioned table like `price_periods`, so the
 * projection recomputes *every* occurrence on whatever cycle is current. Editing
 * a monthly plan to yearly therefore reprices charges already paid and drops the
 * months between — a history that is wrong and looks entirely ordinary.
 *
 * So a switch is two records, and these assert that the old one keeps exactly
 * what it charged.
 */
class SwitchPlanTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()

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

    private var clock = 1_786_000_000_000L

    private fun newStore(): LocalStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SubTrackerDatabase.Schema.create(driver)
        drivers += driver
        val identity = TestIdentity("devicexxxxxxxxxx", "Device")
        return LocalStore(SubTrackerDatabase(driver), identity, identity) { clock++ }
    }

    /** F1TV: monthly at 10.99 from 21 July. */
    private fun monthlyF1(store: LocalStore) = store.createSubscription(
        SubscriptionRecord(
            id = "", updatedAt = "", name = "F1TV", currency = "GBP",
            anchorDate = "2026-07-21", cycleUnit = "month", cycleCount = 1,
            status = "active", category = "entertainment", colour = "#E10600",
            icon = "logo-bytes", notifyMode = "off",
        ),
        openingAmountMinor = 1099,
    )

    @Test
    fun theOldPlanKeepsEveryChargeItActuallyMade() {
        val store = newStore()
        val monthly = monthlyF1(store)
        val today = PlainDate.parse("2026-12-01")

        store.switchPlan(
            monthly, PlainDate.parse("2026-11-21"), "year", 1, amountMinor = 8999,
        )

        val old = store.chargesFor(monthly.id, today).filter { it.isSettled(today) }
        assertEquals(
            listOf("2026-07-21", "2026-08-21", "2026-09-21", "2026-10-21"),
            old.map { it.due.iso },
            "the four months actually paid, and no more",
        )
        assertTrue(old.all { it.effectiveAmountMinor == 1099L }, "still priced at what they cost")
        assertEquals(4 * 1099L, old.sumOf { it.effectiveAmountMinor })
    }

    @Test
    fun theOldPlanStopsTheDayBeforeSoNeitherBillsTwice() {
        val store = newStore()
        val monthly = monthlyF1(store)
        val switchDate = PlainDate.parse("2026-11-21")

        val yearly = store.switchPlan(monthly, switchDate, "year", 1, amountMinor = 8999)

        // endDate is the last date a subscription can still bill on, so ending
        // the old plan on the switch date itself would charge both that day.
        assertEquals("2026-11-20", store.subscription(monthly.id)?.endDate)
        assertEquals("2026-11-21", yearly.anchorDate)

        val today = PlainDate.parse("2027-01-01")
        val oldDues = store.chargesFor(monthly.id, today).map { it.due.iso }
        val newDues = store.chargesFor(yearly.id, today).map { it.due.iso }
        assertTrue(oldDues.intersect(newDues.toSet()).isEmpty(), "no day is billed by both plans")
    }

    @Test
    fun theNewPlanBillsOnTheNewCycleFromTheSwitchDate() {
        val store = newStore()
        val yearly = store.switchPlan(
            monthlyF1(store), PlainDate.parse("2026-11-21"), "year", 1, amountMinor = 8999,
        )

        // Asserted as a prefix, not an exact list: the projection runs a forward
        // window, so how many future years it returns is a property of the
        // window rather than of the switch.
        val charges = store.chargesFor(yearly.id, PlainDate.parse("2027-12-01"))
        assertEquals(listOf("2026-11-21", "2027-11-21"), charges.map { it.due.iso }.take(2))
        assertTrue(charges.all { it.effectiveAmountMinor == 8999L })
        assertTrue(charges.none { it.due.iso < "2026-11-21" }, "nothing before the switch date")
    }

    @Test
    fun theTwoRecordsAreLinkedAndTheIdentityCarriesOver() {
        val store = newStore()
        val monthly = monthlyF1(store)
        val yearly = store.switchPlan(
            monthly, PlainDate.parse("2026-11-21"), "year", 1, amountMinor = 8999,
        )

        assertNotEquals(monthly.id, yearly.id)
        assertEquals(monthly.id, yearly.replaces)
        // Same subscription to the user, so its appearance and rules come along —
        // making them rebuild all this would make the feature not worth using.
        assertEquals("F1TV", yearly.name)
        assertEquals("logo-bytes", yearly.icon)
        assertEquals("#E10600", yearly.colour)
        assertEquals("off", yearly.notifyMode)
        assertEquals(listOf(monthly.id), store.planHistory(yearly.id).map { it.id })
    }

    @Test
    fun switchingTwiceChainsRatherThanForgetting() {
        val store = newStore()
        val monthly = monthlyF1(store)
        val yearly = store.switchPlan(
            monthly, PlainDate.parse("2026-11-21"), "year", 1, amountMinor = 8999,
        )
        val biennial = store.switchPlan(
            yearly, PlainDate.parse("2027-11-21"), "year", 2, amountMinor = 15999,
        )

        assertEquals(
            listOf(monthly.id, yearly.id),
            store.planHistory(biennial.id).map { it.id },
            "oldest first, both earlier plans still reachable",
        )
    }

    @Test
    fun aSwitchLeavesNothingHalfApplied() {
        val store = newStore()
        val monthly = monthlyF1(store)
        store.switchPlan(monthly, PlainDate.parse("2026-11-21"), "year", 1, amountMinor = 8999)

        // Exactly one live plan afterwards. Two would double-charge; none would
        // read as the app having lost the subscription.
        val live = store.subscriptions().filter { it.isLive }
        assertEquals(1, live.size)
        assertEquals("year", live.single().cycleUnit)
    }

    @Test
    fun editingTheCycleInPlaceIsStillTheWrongThingAndThisProvesWhy() {
        // Guards the reason switchPlan exists. If someone later "simplifies" it
        // into a plain edit, this fails and says why.
        val store = newStore()
        val monthly = monthlyF1(store)
        val today = PlainDate.parse("2026-12-01")

        val before = store.chargesFor(monthly.id, today).filter { it.isSettled(today) }
        assertEquals(5, before.size, "Jul through Nov on the monthly plan")

        store.updateSubscription(monthly.copy(cycleUnit = "year", cycleCount = 1))

        val after = store.chargesFor(monthly.id, today).filter { it.isSettled(today) }
        assertEquals(
            listOf("2026-07-21"),
            after.map { it.due.iso },
            "a plain edit silently discards four months that were really paid",
        )
    }
}
