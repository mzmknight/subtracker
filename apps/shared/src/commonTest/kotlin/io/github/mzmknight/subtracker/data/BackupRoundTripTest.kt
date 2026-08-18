package io.github.mzmknight.subtracker.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.core.PlainDate
import io.github.mzmknight.subtracker.core.PriceWatch
import io.github.mzmknight.subtracker.db.SubTrackerDatabase
import io.github.mzmknight.subtracker.sync.ClockStore
import io.github.mzmknight.subtracker.sync.DeviceInfo
import io.github.mzmknight.subtracker.sync.Hlc
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * Backup against a real database, because the interesting behaviour is in the
 * collision between an imported record and what is already stored — which a
 * pure parse test cannot reach.
 */
class BackupRoundTripTest {

    private val today = PlainDate.parse("2026-08-10")
    private val drivers = mutableListOf<JdbcSqliteDriver>()

    @AfterTest
    fun tearDown() {
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

    private fun newStore(name: String = "device0"): LocalStore {
        val identity = TestIdentity(name.padEnd(16, 'x'), name)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SubTrackerDatabase.Schema.create(driver)
        drivers += driver
        return LocalStore(SubTrackerDatabase(driver), identity, identity) { clock }
    }

    private fun draft(name: String, anchor: String = "2026-01-15") = SubscriptionRecord(
        id = "", updatedAt = "", name = name, anchorDate = anchor,
        cycleUnit = "month", cycleCount = 1, status = "active", currency = "GBP",
    )

    private fun seeded(): LocalStore = newStore().apply {
        createSubscription(draft("Netflix"), 1099)
        createSubscription(draft("Spotify", "2025-11-01"), 1199)
    }

    @Test
    fun aBackupRestoresOntoAnEmptyDevice() {
        // The case that actually matters: phone lost, new phone, one file.
        val csv = seeded().exportCsv()

        val fresh = newStore("device1")
        val report = fresh.importCsv(csv)

        assertEquals(2, fresh.subscriptions().size)
        assertEquals(listOf("Netflix", "Spotify"), fresh.subscriptions().map { it.name }.sorted())
        assertEquals(2, report.merged.subscriptionsChanged)
        assertTrue(report.problems.isEmpty())

        // The money has to survive too, not just the names.
        assertEquals(2298, fresh.figures(today).monthlyRunRateMinor)
    }

    @Test
    fun importingTheSameFileTwiceChangesNothingTheSecondTime() {
        val csv = seeded().exportCsv()
        val fresh = newStore("device1")

        fresh.importCsv(csv)
        val second = fresh.importCsv(csv)

        assertEquals(0, second.merged.subscriptionsChanged, "a repeat import must be a no-op")
        assertEquals(0, second.merged.pricesChanged)
        assertEquals(2, fresh.subscriptions().size, "and must not duplicate anything")
    }

    @Test
    fun importDoesNotResurrectSomethingDeletedAfterTheBackup() {
        // Not a bug — the delete is newer, so last-write-wins keeps it. This is
        // pinned because it is surprising, and it is why restore exists.
        val store = seeded()
        val csv = store.exportCsv()

        clock += 60_000
        store.deleteSubscription(store.subscriptions().first { it.name == "Netflix" }.id)
        assertEquals(1, store.subscriptions().size)

        store.importCsv(csv)

        assertEquals(1, store.subscriptions().size, "merge must not undo a newer decision")
        assertEquals("Spotify", store.subscriptions().single().name)
    }

    @Test
    fun restoreBringsBackSomethingDeletedAfterTheBackup() {
        val store = seeded()
        val csv = store.exportCsv()

        clock += 60_000
        store.deleteSubscription(store.subscriptions().first { it.name == "Netflix" }.id)
        assertEquals(1, store.subscriptions().size)

        clock += 60_000
        store.restoreCsv(csv)

        assertEquals(2, store.subscriptions().size, "restore is the one that recovers a deletion")
        assertEquals(listOf("Netflix", "Spotify"), store.subscriptions().map { it.name }.sorted())
        assertEquals(2298, store.figures(today).monthlyRunRateMinor)
    }

    @Test
    fun restoreLeavesThingsAddedSinceTheBackupAlone() {
        val store = seeded()
        val csv = store.exportCsv()

        clock += 60_000
        store.createSubscription(draft("Disney+", "2026-06-01"), 899)

        clock += 60_000
        store.restoreCsv(csv)

        assertEquals(
            listOf("Disney+", "Netflix", "Spotify"),
            store.subscriptions().map { it.name }.sorted(),
            "restore is not a rollback of everything since",
        )
    }

    @Test
    fun aRestoredRecordWinsOnLaterSyncsRatherThanBeingRevertedByAPeer() {
        // Restored records are restamped, so a paired device that still holds the
        // delete adopts the restore instead of undoing it on the next sync.
        val store = seeded()
        val netflixId = store.subscriptions().first { it.name == "Netflix" }.id
        val csv = store.exportCsv()

        clock += 60_000
        store.deleteSubscription(netflixId)
        val tombstone = store.allSubscriptions().first { it.id == netflixId }

        clock += 60_000
        store.restoreCsv(csv)
        val restored = store.allSubscriptions().first { it.id == netflixId }

        assertTrue(
            restored.updatedAt > tombstone.updatedAt,
            "restored ${restored.updatedAt} must beat tombstone ${tombstone.updatedAt}",
        )
    }

    @Test
    fun restoringDoesNotWipeALogoThatIsNotInTheFile() {
        // Logos are excluded from the CSV. Without carrying the local one
        // forward, every restore would silently blank them.
        val store = seeded()
        val netflix = store.subscriptions().first { it.name == "Netflix" }
        clock += 1_000
        store.updateSubscription(netflix.copy(icon = "a-logo-as-base64"))

        val csv = store.exportCsv()
        assertTrue(!csv.contains("a-logo-as-base64"), "the logo must not bloat the file")

        clock += 60_000
        store.restoreCsv(csv)

        assertEquals(
            "a-logo-as-base64",
            store.subscriptions().first { it.name == "Netflix" }.icon,
            "a restore must not blank the logos it deliberately did not export",
        )
    }

    @Test
    fun importDoesNotWipeALogoEither() {
        val store = seeded()
        val netflix = store.subscriptions().first { it.name == "Netflix" }
        clock += 1_000
        store.updateSubscription(netflix.copy(icon = "a-logo-as-base64"))
        val csv = store.exportCsv()

        // A newer file, so the imported record would otherwise win with no icon.
        val fresh = newStore("device1")
        fresh.importCsv(csv)
        clock += 60_000
        fresh.updateSubscription(
            fresh.subscriptions().first { it.name == "Netflix" }.copy(icon = "local-logo"),
        )
        clock += 60_000
        fresh.importCsv(store.exportCsv())

        assertEquals("local-logo", fresh.subscriptions().first { it.name == "Netflix" }.icon)
    }

    @Test
    fun priceHistoryIncludingARiseSurvivesTheRoundTrip() {
        val store = seeded()
        val netflixId = store.subscriptions().first { it.name == "Netflix" }.id
        clock += 1_000
        store.addPrice(netflixId, 1299, "2026-05-01")

        val fresh = newStore("device1")
        fresh.importCsv(store.exportCsv())

        val prices = fresh.pricesFor(netflixId).sortedBy { it.effectiveFrom }
        assertEquals(listOf(1099L, 1299L), prices.map { it.amountMinor })
        assertEquals(listOf("2026-01-15", "2026-05-01"), prices.map { it.effectiveFrom })

        // And the derived history agrees on both sides of the rise.
        val history = fresh.history(today)
        assertEquals(
            store.history(today).map { it.month to it.totalMinor },
            history.map { it.month to it.totalMinor },
        )
    }

    @Test
    fun correctingAPriceRewritesItRatherThanInventingARise() {
        // A mistyped price is a wrong number, not an event. Adding a period for
        // it would fabricate a price change and put it in Price Watch.
        val store = seeded()
        val netflixId = store.subscriptions().first { it.name == "Netflix" }.id

        clock += 1_000
        store.correctCurrentPrice(netflixId, 1299)

        val prices = store.pricesFor(netflixId)
        assertEquals(1, prices.size, "correcting must not add a second period")
        assertEquals(1299, prices.single().amountMinor)
        assertTrue(
            PriceWatch.changes(store.allSubscriptions(), store.allPrices(), today).isEmpty(),
            "a correction is not a price rise",
        )
    }

    @Test
    fun recordingAPriceChangeStillAddsAPeriodAndShowsAsARise() {
        val store = seeded()
        val netflixId = store.subscriptions().first { it.name == "Netflix" }.id

        clock += 1_000
        store.addPrice(netflixId, 1299, "2026-05-01")

        assertEquals(2, store.pricesFor(netflixId).size)
        val change = PriceWatch.changes(store.allSubscriptions(), store.allPrices(), today).single()
        assertEquals(1099, change.fromMinor)
        assertEquals(1299, change.toMinor)
    }

    @Test
    fun correctingToTheSamePriceChangesNothing() {
        val store = seeded()
        val netflixId = store.subscriptions().first { it.name == "Netflix" }.id
        val before = store.pricesFor(netflixId).single()

        clock += 1_000
        store.correctCurrentPrice(netflixId, 1099)

        // No restamp, so an unrelated edit does not push a pointless write to
        // every paired device.
        assertEquals(before.updatedAt, store.pricesFor(netflixId).single().updatedAt)
    }

    @Test
    fun aSkippedChargeSurvivesTheRoundTrip() {
        val store = seeded()
        val netflixId = store.subscriptions().first { it.name == "Netflix" }.id
        clock += 1_000
        store.setOverride(netflixId, "2026-03-15", "skipped")

        val fresh = newStore("device1")
        fresh.importCsv(store.exportCsv())

        val skipped = fresh.chargesFor(netflixId, today).firstOrNull { it.due.iso == "2026-03-15" }
        assertNotNull(skipped)
        assertTrue(skipped.isSkipped, "an adjustment you made by hand must survive a backup")
        assertEquals(0, skipped.effectiveAmountMinor)
    }
}
