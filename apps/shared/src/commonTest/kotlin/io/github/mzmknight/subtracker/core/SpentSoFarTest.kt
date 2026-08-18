package io.github.mzmknight.subtracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * Money already gone versus money still to come.
 *
 * The headline totals mix the two, which is fine as a headline and wrong as an
 * answer to "what have I spent". The split is easy to get out by exactly one
 * charge — the one falling due today — and nothing about the resulting number
 * looks unusual.
 */
class SpentSoFarTest {

    private val today = PlainDate.parse("2026-08-13")

    private fun sub(id: String, name: String, anchor: String) = SubscriptionRecord(
        id = id, updatedAt = "1", name = name, anchorDate = anchor,
        cycleUnit = "month", cycleCount = 1, status = "active", currency = "GBP",
    )

    private fun price(id: String, subId: String, minor: Long, from: String) =
        PricePeriodRecord(id = id, updatedAt = "1", subscriptionId = subId, amountMinor = minor, effectiveFrom = from)

    // Bills on the 5th (past) and the 25th (still to come) of each month.
    private val early = sub("s1", "Early", "2026-01-05")
    private val late = sub("s2", "Late", "2026-01-25")
    private val subscriptions = listOf(early, late)
    private val prices = listOf(price("p1", "s1", 1000, "2026-01-05"), price("p2", "s2", 500, "2026-01-25"))

    private fun figures(overrides: List<ChargeOverrideRecord> = emptyList()) =
        Figures.compute(subscriptions, prices, overrides, today)

    @Test
    fun thisMonthIsSplitIntoGoneAndStillToCome() {
        val f = figures()

        assertEquals(1000, f.monthSpentMinor, "the 5th has passed")
        assertEquals(500, f.monthRemainingMinor, "the 25th has not")
        assertEquals(f.thisMonthMinor, f.monthSpentMinor + f.monthRemainingMinor)
    }

    @Test
    fun aChargeDueTodayCountsAsStillToCome() {
        // It has not left the account yet. Counting it as spent would overstate
        // the figure by exactly one charge on one day a month, which is the kind
        // of thing nobody notices and everybody would be annoyed by.
        val onTheDay = Figures.compute(
            listOf(sub("s3", "Today", "2026-01-13")),
            listOf(price("p3", "s3", 777, "2026-01-13")),
            emptyList(),
            today,
        )
        assertEquals(0, onTheDay.monthSpentMinor)
        assertEquals(777, onTheDay.monthRemainingMinor)
    }

    @Test
    fun theYearSplitsTheSameWayAndAddsUp() {
        val f = figures()

        // Jan–Jul both charges, plus August's 5th: 7 * 1500 + 1000.
        assertEquals(7 * 1500 + 1000, f.yearSpentMinor)
        assertEquals(f.yearSpentMinor + f.yearRemainingMinor, f.yearTotalMinor)
        assertTrue(f.yearRemainingMinor > 0, "there is still the rest of the year")
    }

    @Test
    fun theFullYearCoversTwelveMonthsOfBoth() {
        val f = figures()
        assertEquals(12 * 1500, f.yearTotalMinor)
    }

    @Test
    fun aSkippedChargeIsNeitherSpentNorOwed() {
        val skip = ChargeOverrideRecord(
            id = ChargeOverrideRecord.idFor("s1", "2026-08-05"),
            updatedAt = "2", subscriptionId = "s1", dueDate = "2026-08-05",
            kind = ChargeOverrideRecord.KIND_SKIPPED,
        )
        val f = figures(listOf(skip))

        assertEquals(0, f.monthSpentMinor, "a skipped charge did not cost anything")
        assertEquals(500, f.monthRemainingMinor)
    }

    @Test
    fun progressIsTheSpentShareOfTheYear() {
        val f = figures()
        assertEquals(f.yearSpentMinor.toFloat() / f.yearTotalMinor, f.yearProgress)
        assertTrue(f.yearProgress > 0f && f.yearProgress < 1f)
    }

    @Test
    fun nothingRecordedMeansNoProgressRatherThanADivideByZero() {
        val empty = Figures.compute(emptyList(), emptyList(), emptyList(), today)
        assertEquals(0, empty.yearTotalMinor)
        assertEquals(0f, empty.yearProgress)
        assertEquals(0, empty.monthSpentMinor)
    }

    @Test
    fun foreignChargesAreCountedInTheHomeCurrency() {
        val dollars = sub("s4", "Proton", "2026-01-05").copy(currency = "USD")
        val f = Figures.compute(
            listOf(dollars),
            listOf(price("p4", "s4", 1000, "2026-01-05")),
            emptyList(),
            today,
            "GBP",
            Rates("GBP", mapOf("USD" to 0.75)),
        )
        // Eight charges have fallen due, each $10.00 → £7.50.
        assertEquals(8 * 750, f.yearSpentMinor)
    }
}
