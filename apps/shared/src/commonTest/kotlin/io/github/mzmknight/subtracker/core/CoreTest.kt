package io.github.mzmknight.subtracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These deliberately duplicate cases from backend/test/engine.test.js. The
 * client and the server must agree exactly about dates and money, and the only
 * way to keep two implementations honest is to assert the same answers on both.
 */
class PlainDateTest {

    @Test
    fun clampsToTheLastDayOfAShortMonth() {
        assertEquals("2026-02-28", PlainDate.parse("2026-01-31").plusMonths(1).iso)
        assertEquals("2028-02-29", PlainDate.parse("2028-01-31").plusMonths(1).iso)
        assertEquals("2026-04-30", PlainDate.parse("2026-03-31").plusMonths(1).iso)
        assertEquals("2025-12-15", PlainDate.parse("2026-01-15").plusMonths(-1).iso)
    }

    @Test
    fun anchorRelativeSteppingDoesNotDrift() {
        // Same schedule the backend produces for a subscription anchored on the 31st.
        val anchor = PlainDate.parse("2026-01-31")
        val got = (0..5).map { anchor.plusMonths(it).iso }
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30"),
            got,
        )
    }

    @Test
    fun leapDayAnchorReturnsToThe29th() {
        val anchor = PlainDate.parse("2024-02-29")
        assertEquals(
            listOf("2024-02-29", "2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"),
            (0..4).map { anchor.plusYears(it).iso },
        )
    }

    @Test
    fun epochDayRoundTrips() {
        var date = PlainDate.parse("2020-01-01")
        val end = PlainDate.parse("2030-01-01")
        while (date < end) {
            assertEquals(date, PlainDate.fromEpochDay(date.toEpochDay()))
            date = date.plusDays(1)
        }
    }

    @Test
    fun crossesMonthAndYearBoundaries() {
        assertEquals("2026-02-01", PlainDate.parse("2026-01-31").plusDays(1).iso)
        assertEquals("2027-01-01", PlainDate.parse("2026-12-31").plusDays(1).iso)
        assertEquals("2024-02-29", PlainDate.parse("2024-02-28").plusDays(1).iso)
        assertEquals("2026-03-01", PlainDate.parse("2026-02-28").plusDays(1).iso)
        assertEquals(22, PlainDate.parse("2026-08-09").daysUntil(PlainDate.parse("2026-08-31")))
    }

    @Test
    fun rejectsImpossibleDates() {
        assertFailsWith<IllegalArgumentException> { PlainDate.parse("2026-02-30") }
        assertFailsWith<IllegalArgumentException> { PlainDate.parse("2026-02-29") }
        assertFailsWith<IllegalArgumentException> { PlainDate.parse("2026-13-01") }
        assertFailsWith<IllegalArgumentException> { PlainDate.parse("09/08/2026") }
        assertNull(PlainDate.parseOrNull(""))
        assertNull(PlainDate.parseOrNull(null))
        assertEquals("2024-02-29", PlainDate.parse("2024-02-29").iso)
    }

    @Test
    fun sortsChronologically() {
        val sorted = listOf("2026-10-01", "2026-02-09", "2026-02-10")
            .map(PlainDate::parse).sorted().map { it.iso }
        assertEquals(listOf("2026-02-09", "2026-02-10", "2026-10-01"), sorted)
        assertEquals("2026-08", PlainDate.parse("2026-08-09").monthKey)
        assertEquals("9 Aug 2026", PlainDate.parse("2026-08-09").formatLong())
    }
}

class MoneyTest {

    @Test
    fun formatsAndParsesWithoutDrift() {
        assertEquals("£12.99", Money.format(1299, "GBP"))
        assertEquals("£0.05", Money.format(5, "GBP"))
        assertEquals("-£12.99", Money.format(-1299, "GBP").replace("£-", "-£"))
        assertEquals("12.99", Money.formatBare(1299, "GBP"))
        assertEquals(1299L, Money.parseOrNull("12.99", "GBP"))
        assertEquals(1299L, Money.parseOrNull("£12.99", "GBP"))
        assertEquals(129950L, Money.parseOrNull("1,299.50", "GBP"))
        assertEquals(10000L, Money.parseOrNull("100", "GBP"))
        assertNull(Money.parseOrNull("12.999", "GBP"))
        assertNull(Money.parseOrNull("free", "GBP"))
        assertEquals(1000L, Money.parseOrNull("1000", "JPY"))
        assertEquals("¥1000", Money.format(1000, "JPY"))
    }

    @Test
    fun groupsThousandsOnDashboardTiles() {
        assertEquals("£4,390", Money.formatRounded(439000, "GBP"))
        assertEquals("£44", Money.formatRounded(4390, "GBP"))
        assertEquals("£1,234,568", Money.formatRounded(123456789, "GBP"))
        assertEquals("£0", Money.formatRounded(0, "GBP"))
    }

    @Test
    fun runRateMatchesTheBackendEngine() {
        // Same expectations as the JS suite and the live v_run_rate view.
        assertEquals(1000L, Money.monthlyRunRate(1000, "month", 1))
        assertEquals(1000L, Money.monthlyRunRate(12000, "year", 1))
        assertEquals(1000L, Money.monthlyRunRate(3000, "month", 3))
        assertEquals(4348L, Money.monthlyRunRate(1000, "week", 1))
        // The five seeded subscriptions from the backend verification run.
        assertEquals(1099L, Money.monthlyRunRate(1099, "month", 1))
        assertEquals(792L, Money.monthlyRunRate(9500, "year", 1))
        assertEquals(1200L, Money.monthlyRunRate(3600, "month", 3))
    }

    @Test
    fun runRateDoesNotClampLargeAmountsToAnIntCeiling() {
        // Values taken from the JS engine, not from what Kotlin happened to
        // return: monthlyRunRate rounded to Int, so anything past Int.MAX_VALUE
        // minor units silently pinned to 2147483647 and the two engines
        // disagreed. Rare, but it is a wrong number that never announces itself.
        assertEquals(2_500_000_000L, Money.monthlyRunRate(2_500_000_000L, "month", 1))
        assertEquals(300_000_000_000L, Money.monthlyRunRate(300_000_000_000L, "month", 1))
        assertEquals(41_666_666_667L, Money.monthlyRunRate(500_000_000_000L, "year", 1))
    }

    @Test
    fun describesCyclesReadably() {
        assertEquals("monthly", Money.describeCycle("month", 1))
        assertEquals("yearly", Money.describeCycle("year", 1))
        assertEquals("quarterly", Money.describeCycle("month", 3))
        assertEquals("fortnightly", Money.describeCycle("week", 2))
        assertEquals("every 10 days", Money.describeCycle("day", 10))
    }

    @Test
    fun integerMinorUnitsDoNotAccumulateError() {
        var total = 0L
        repeat(1000) { total += Money.parseOrNull("0.10", "GBP")!! }
        assertEquals(10000L, total)
        assertEquals("100.00", Money.formatBare(total, "GBP"))

        var floatTotal = 0.0
        repeat(1000) { floatTotal += 0.1 }
        assertTrue(floatTotal != 100.0, "float accumulation should drift, proving the point")
    }
}
