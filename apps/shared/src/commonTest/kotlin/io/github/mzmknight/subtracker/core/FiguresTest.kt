package io.github.mzmknight.subtracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * Reproduces, from local records alone, the figures a live PocketBase v0.39.7
 * instance produced for the same five subscriptions.
 *
 * This is the test that licenses computing the dashboard on-device. If it ever
 * fails, a device with no server would start disagreeing with one that has one.
 */
class FiguresTest {

    private val today = PlainDate.parse("2026-08-09")

    private fun subscription(
        id: String,
        name: String,
        anchor: String,
        unit: String = "month",
        count: Int = 1,
        status: String = "active",
        endDate: String? = null,
        category: String = "entertainment",
    ) = SubscriptionRecord(
        id = id,
        updatedAt = "0000000000001000-00000-aaaaaaaaaaaaaaaa",
        name = name,
        anchorDate = anchor,
        cycleUnit = unit,
        cycleCount = count,
        status = status,
        endDate = endDate,
        category = category,
        currency = "GBP",
    )

    private fun price(id: String, subscriptionId: String, amount: Long, from: String) =
        PricePeriodRecord(
            id = id,
            updatedAt = "0000000000001000-00000-aaaaaaaaaaaaaaaa",
            subscriptionId = subscriptionId,
            amountMinor = amount,
            effectiveFrom = from,
        )

    // The exact dataset seeded into the live instance during backend verification.
    private val subscriptions = listOf(
        subscription("s1", "Netflix", "2026-01-31"),
        subscription("s2", "Amazon Prime", "2024-02-29", unit = "year"),
        subscription("s3", "Spotify", "2026-03-15"),
        subscription("s4", "Domains", "2026-01-31", count = 3, category = "software"),
        subscription("s5", "Gym", "2025-06-10", status = "cancelled", endDate = "2026-02-01", category = "health"),
    )

    private val prices = listOf(
        price("p1", "s1", 1099, "2026-01-31"),
        price("p2", "s2", 9500, "2024-02-29"),
        price("p3", "s3", 1199, "2026-03-15"),
        price("p4", "s3", 1299, "2026-07-01"),
        price("p5", "s4", 3600, "2026-01-31"),
        price("p6", "s5", 4500, "2025-06-10"),
    )

    private fun figures(overrides: List<ChargeOverrideRecord> = emptyList()) =
        Figures.compute(subscriptions, prices, overrides, today)

    @Test
    fun matchesTheFiguresTheLiveServerProduced() {
        val result = figures()

        assertEquals(2398, result.thisMonthMinor, "this month")
        assertEquals(5998, result.lastMonthMinor, "last month")
        assertEquals(40986, result.yearToDateMinor, "year to date")
        assertEquals(4390, result.monthlyRunRateMinor, "monthly run rate")
        assertEquals(52680, result.annualRunRateMinor, "annual run rate")
        assertEquals(4, result.activeCount, "the cancelled gym must not count as active")
        assertEquals(2, result.upcoming30dCount)
        assertEquals(2398, result.upcoming30dMinor)
        assertEquals(
            mapOf("entertainment" to 3190L, "software" to 1200L),
            result.runRateByCategory,
        )
    }

    @Test
    fun monthlySeriesMatchesTheServerViewMonthForMonth() {
        val byMonth = figures().monthlySeries.associateBy { it.month }

        // Verified against v_monthly_totals on the live instance.
        assertEquals(2298, byMonth.getValue("2026-06").totalMinor)
        assertEquals(5998, byMonth.getValue("2026-07").totalMinor)
        assertEquals(2398, byMonth.getValue("2026-08").totalMinor)
        assertEquals(2398, byMonth.getValue("2026-09").totalMinor)
        assertEquals(5998, byMonth.getValue("2026-10").totalMinor)

        assertEquals(2, byMonth.getValue("2026-06").chargeCount)
        assertEquals(2, byMonth.getValue("2026-06").settledCount, "June is in the past")
        assertEquals(0, byMonth.getValue("2026-08").settledCount, "August is not settled yet")
        assertTrue(byMonth.getValue("2026-09").isFuture)
        assertTrue(!byMonth.getValue("2026-08").isFuture, "the current month is not the future")
    }

    @Test
    fun theHardScheduleCasesSurviveIntoTheCharges() {
        val charges = Figures.computeCharges(subscriptions, prices, emptyList(), today)

        val netflix = charges.filter { it.subscriptionId == "s1" }.map { it.due.iso }.sorted()
        assertTrue("2026-02-28" in netflix, "31st anchor clamps in February")
        assertTrue("2026-03-31" in netflix, "and returns to the 31st in March")

        val prime = charges.filter { it.subscriptionId == "s2" }.map { it.due.iso }.sorted()
        assertTrue("2028-02-29" in prime, "leap-day anchor returns to the 29th")
        assertTrue("2026-02-28" in prime)

        // Spotify's price rise took effect 1 July.
        val spotify = charges.filter { it.subscriptionId == "s3" }.associateBy { it.due.iso }
        assertEquals(1199, spotify.getValue("2026-06-15").amountMinor)
        assertEquals(1299, spotify.getValue("2026-07-15").amountMinor)

        // The gym was cancelled from 1 February; its last charge is 10 January.
        val gym = charges.filter { it.subscriptionId == "s5" }.map { it.due.iso }.sorted()
        assertEquals("2026-01-10", gym.last())
    }

    @Test
    fun overridesChangeTheTotals() {
        val augustNetflix = ChargeOverrideRecord(
            id = ChargeOverrideRecord.idFor("s1", "2026-08-31"),
            updatedAt = "0000000000002000-00000-aaaaaaaaaaaaaaaa",
            subscriptionId = "s1",
            dueDate = "2026-08-31",
            kind = ChargeOverrideRecord.KIND_SKIPPED,
        )

        val before = figures()
        val after = figures(listOf(augustNetflix))

        assertEquals(2398, before.thisMonthMinor)
        assertEquals(1299, after.thisMonthMinor, "skipping the £10.99 Netflix charge")
        assertEquals(1, after.upcoming30dCount, "a skipped charge is not upcoming")

        val corrected = ChargeOverrideRecord(
            id = ChargeOverrideRecord.idFor("s1", "2026-08-31"),
            updatedAt = "0000000000002000-00000-aaaaaaaaaaaaaaaa",
            subscriptionId = "s1",
            dueDate = "2026-08-31",
            kind = ChargeOverrideRecord.KIND_AMOUNT,
            amountMinor = 500,
        )
        assertEquals(1799, figures(listOf(corrected)).thisMonthMinor, "1299 + the corrected 500")
    }

    @Test
    fun aDeletedSubscriptionLeavesNoTrace() {
        val withoutNetflix = subscriptions.map {
            if (it.id == "s1") it.copy(deleted = true) else it
        }
        val result = Figures.compute(withoutNetflix, prices, emptyList(), today)

        assertEquals(1299, result.thisMonthMinor, "only Spotify remains in August")
        assertEquals(3, result.activeCount)
        assertTrue(
            Figures.computeCharges(withoutNetflix, prices, emptyList(), today)
                .none { it.subscriptionId == "s1" },
        )
    }

    @Test
    fun aSubscriptionWithNoPriceIsSkippedRatherThanCrashing() {
        val orphan = subscription("s9", "Mystery", "2026-01-01")
        val result = Figures.compute(subscriptions + orphan, prices, emptyList(), today)

        assertEquals(2398, result.thisMonthMinor, "the priceless subscription contributes nothing")
        assertEquals(4, result.activeCount, "and is not counted as active")
    }
}
