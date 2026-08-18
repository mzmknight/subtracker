package io.github.mzmknight.subtracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

class PriceWatchTest {

    private val today = PlainDate.parse("2026-08-10")

    private fun sub(
        id: String,
        name: String,
        unit: String = "month",
        count: Int = 1,
        deleted: Boolean = false,
    ) = SubscriptionRecord(
        id = id, updatedAt = "1", deleted = deleted, name = name,
        anchorDate = "2024-01-10", cycleUnit = unit, cycleCount = count, status = "active",
    )

    private fun price(
        id: String,
        subscriptionId: String,
        minor: Long,
        from: String,
        deleted: Boolean = false,
    ) = PricePeriodRecord(
        id = id, updatedAt = "1", deleted = deleted, subscriptionId = subscriptionId,
        amountMinor = minor, effectiveFrom = from,
    )

    @Test
    fun theOpeningPriceIsNotAChange() {
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Netflix")),
            listOf(price("p1", "s1", 1099, "2025-06-01")),
            today,
        )
        assertTrue(changes.isEmpty(), "there was nothing before the first price")
    }

    @Test
    fun aRiseIsReportedWithItsPercentageAndAnnualImpact() {
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Netflix")),
            listOf(
                price("p1", "s1", 899, "2025-06-01"),
                price("p2", "s1", 1099, "2026-03-01"),
            ),
            today,
        )

        assertEquals(1, changes.size)
        val rise = changes.single()
        assertEquals(899, rise.fromMinor)
        assertEquals(1099, rise.toMinor)
        assertEquals(200, rise.deltaMinor)
        assertEquals(22, rise.percent)
        assertTrue(rise.isIncrease)
        // £2 a month, every month.
        assertEquals(2400, rise.annualImpactMinor)
        assertTrue(!rise.isScheduled)
    }

    @Test
    fun aYearlyPlansRiseIsNotCountedTwelveTimes() {
        // The bug this guards: normalising to a monthly run rate and then
        // multiplying by twelve has to give back the original yearly delta, not
        // twelve times it.
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Some annual thing", unit = "year")),
            listOf(
                price("p1", "s1", 10000, "2025-02-01"),
                price("p2", "s1", 12000, "2026-02-01"),
            ),
            today,
        )
        assertEquals(2000, changes.single().annualImpactMinor)
    }

    @Test
    fun aPriceCutIsReportedAsANegative() {
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Something cheaper now")),
            listOf(
                price("p1", "s1", 1200, "2025-06-01"),
                price("p2", "s1", 900, "2026-01-01"),
            ),
            today,
        )
        val cut = changes.single()
        assertTrue(!cut.isIncrease)
        assertEquals(-300, cut.deltaMinor)
        assertEquals(-25, cut.percent)
        assertEquals(-3600, cut.annualImpactMinor)
    }

    @Test
    fun aRiseDatedInTheFutureIsMarkedScheduledAndNotYetCounted() {
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Netflix")),
            listOf(
                price("p1", "s1", 899, "2025-06-01"),
                price("p2", "s1", 1299, "2026-12-01"),
            ),
            today,
        )
        assertTrue(changes.single().isScheduled)
        assertEquals(
            0, PriceWatch.annualCreepMinor(changes),
            "a rise that has not started has not cost anything yet",
        )
    }

    @Test
    fun creepSumsRisesAgainstCutsAcrossEverything() {
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Up"), sub("s2", "Down")),
            listOf(
                price("p1", "s1", 1000, "2025-01-01"),
                price("p2", "s1", 1200, "2026-01-01"),
                price("p3", "s2", 1000, "2025-01-01"),
                price("p4", "s2", 950, "2026-01-01"),
            ),
            today,
        )
        assertEquals(2, changes.size)
        // +£2.00/mo and -£0.50/mo, annualised.
        assertEquals(2400 - 600, PriceWatch.annualCreepMinor(changes))
    }

    @Test
    fun changesComeBackNewestFirst() {
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Netflix"), sub("s2", "Spotify")),
            listOf(
                price("p1", "s1", 800, "2024-09-01"),
                price("p2", "s1", 900, "2025-09-01"),
                price("p3", "s2", 1000, "2025-01-01"),
                price("p4", "s2", 1100, "2026-04-01"),
            ),
            today,
        )
        assertEquals(listOf("2026-04-01", "2025-09-01"), changes.map { it.effectiveFrom })
    }

    @Test
    fun changesOlderThanTheWindowAreLeftOut() {
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Ancient")),
            listOf(
                price("p1", "s1", 500, "2020-01-01"),
                price("p2", "s1", 600, "2021-01-01"),
            ),
            today,
        )
        assertTrue(changes.isEmpty(), "a rise five years ago is not price creep today")
    }

    @Test
    fun aRepricedThenUnchangedPeriodIsNotAChange() {
        // Two price rows with the same amount happen when a period is re-entered;
        // reporting "£10.99 → £10.99, 0%" would be noise.
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Netflix")),
            listOf(
                price("p1", "s1", 1099, "2025-06-01"),
                price("p2", "s1", 1099, "2026-01-01"),
            ),
            today,
        )
        assertTrue(changes.isEmpty())
    }

    @Test
    fun deletedSubscriptionsAndDeletedPricesAreIgnored() {
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Gone", deleted = true), sub("s2", "Here")),
            listOf(
                price("p1", "s1", 800, "2025-01-01"),
                price("p2", "s1", 900, "2026-01-01"),
                price("p3", "s2", 800, "2025-01-01"),
                price("p4", "s2", 900, "2026-01-01", deleted = true),
            ),
            today,
        )
        assertTrue(changes.isEmpty(), "tombstones must not resurface as price history")
    }

    @Test
    fun aTinyRiseNeverReadsAsZeroPercent() {
        val changes = PriceWatch.changes(
            listOf(sub("s1", "Barely moved")),
            listOf(
                price("p1", "s1", 10000, "2025-01-01"),
                price("p2", "s1", 10050, "2026-01-01"),
            ),
            today,
        )
        assertEquals(1, changes.single().percent, "0.5% must not print next to a real increase")
    }
}
