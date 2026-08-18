package io.github.mzmknight.subtracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * Mixed currencies.
 *
 * The bug these exist to prevent is not a crash: it is £10.99 and $23.76 being
 * added to make "£34.75" because both are integers of minor units. That number
 * is wrong and looks entirely ordinary, which is why it survived until someone
 * added a dollar subscription.
 */
class CurrencyTest {

    private val today = PlainDate.parse("2026-08-10")
    private val gbp = Rates("GBP", mapOf("USD" to 0.79, "EUR" to 0.85))

    private fun sub(id: String, name: String, currency: String, unit: String, count: Int = 1) =
        SubscriptionRecord(
            id = id, updatedAt = "1", name = name, currency = currency,
            anchorDate = "2026-01-15", cycleUnit = unit, cycleCount = count, status = "active",
        )

    private fun price(id: String, subId: String, minor: Long) =
        PricePeriodRecord(id = id, updatedAt = "1", subscriptionId = subId, amountMinor = minor, effectiveFrom = "2026-01-15")

    // ------------------------------------------------------------ conversion

    @Test
    fun convertsBetweenCurrenciesWithTheSameMinorUnits() {
        assertEquals(1877, Rates.convert(2376, "USD", "GBP", 0.79))
        assertEquals(1000, Rates.convert(1000, "USD", "GBP", 1.0))
    }

    @Test
    fun rescalesWhenTheMinorUnitsDiffer() {
        // 1000 minor units is ¥1000 but only £10.00 — a straight multiply would
        // make a thousand yen into a hundred thousand pence.
        assertEquals(500, Rates.convert(1000, "JPY", "GBP", 0.005))
        assertEquals(1000, Rates.convert(500, "GBP", "JPY", 200.0))
    }

    @Test
    fun theHomeCurrencyIsNeverConverted() {
        assertEquals(1099, gbp.toHome(1099, "GBP"))
        assertEquals(1.0, gbp.rateFor("GBP"))
    }

    @Test
    fun anUnknownCurrencyPassesThroughAndIsReported() {
        // Passing through is wrong, but dropping it would under-report a total
        // and look perfectly plausible. Visibly wrong beats quietly wrong.
        val rates = Rates("GBP")
        assertEquals(2376, rates.toHome(2376, "USD"))
        assertEquals(listOf("USD"), rates.missingFrom(listOf("GBP", "USD", "GBP")))
        assertTrue(!rates.knows("USD"))
        assertTrue(gbp.knows("USD"))
    }

    @Test
    fun ratesAreRejectedWhenTheyWouldEraseMoney() {
        // A zero rate makes a subscription vanish from every total, which is
        // indistinguishable from the app losing it.
        assertNull(Rates.parseRate("0"))
        assertNull(Rates.parseRate("-1.2"))
        assertNull(Rates.parseRate("abc"))
        assertNull(Rates.parseRate(""))
        assertEquals(0.79, Rates.parseRate("0.79"))
        // Most places these are copied from write them this way.
        assertEquals(0.79, Rates.parseRate("0,79"))
    }

    @Test
    fun ratesRoundTripThroughTheirTextForm() {
        assertEquals("0.79", Rates.formatRate(0.79))
        assertEquals("1", Rates.formatRate(1.0))
        assertEquals("155.25", Rates.formatRate(155.25))
        for (text in listOf("0.79", "1", "155.25", "0.0085")) {
            assertEquals(text, Rates.formatRate(Rates.parseRate(text)!!))
        }
    }

    // ------------------------------------------------------------ aggregates

    private val netflix = sub("s1", "Netflix", "GBP", "month")
    private val proton = sub("s2", "Proton", "USD", "year")
    private val subscriptions = listOf(netflix, proton)
    private val prices = listOf(price("p1", "s1", 1099), price("p2", "s2", 1980))

    @Test
    fun theRunRateConvertsForeignSubscriptionsIntoTheHomeCurrency() {
        val figures = Figures.compute(subscriptions, prices, emptyList(), today, "GBP", gbp)

        // Netflix £10.99/month, plus $19.80/year → $1.65/month → £1.30.
        assertEquals(1099 + 130, figures.monthlyRunRateMinor)
    }

    @Test
    fun withoutConversionTheTotalIsTheOldWrongAnswer() {
        // Pins what the bug looked like: the dollar amount added as though it
        // were pounds. If this ever equals the converted figure, conversion has
        // silently stopped happening.
        val unconverted = Figures.compute(subscriptions, prices, emptyList(), today, "GBP", Rates("GBP"))
        val converted = Figures.compute(subscriptions, prices, emptyList(), today, "GBP", gbp)

        assertEquals(1099 + 165, unconverted.monthlyRunRateMinor)
        assertTrue(unconverted.monthlyRunRateMinor != converted.monthlyRunRateMinor)
        assertEquals(listOf("USD"), unconverted.currenciesWithoutRates)
        assertTrue(converted.currenciesWithoutRates.isEmpty())
    }

    @Test
    fun monthlyTotalsConvertToo() {
        val figures = Figures.compute(subscriptions, prices, emptyList(), today, "GBP", gbp)
        val january = figures.monthlySeries.first { it.month == "2026-01" }

        // Both bill on 15 January: £10.99 plus $19.80 → £15.64.
        assertEquals(1099 + Rates.convert(1980, "USD", "GBP", 0.79), january.totalMinor)
    }

    @Test
    fun historySharesAddUpToTheirOwnMonthTotal() {
        // The composition bar divides one by the other, so a mix of units would
        // draw slices that do not add up to the number printed beside them.
        val months = History.months(subscriptions, prices, emptyList(), today, "GBP", gbp)
        for (month in months) {
            assertEquals(
                month.totalMinor,
                month.shares.sumOf { it.totalMinor },
                "${month.month} must be internally consistent",
            )
        }
        assertTrue(months.isNotEmpty())
    }

    @Test
    fun priceRisesKeepTheirOwnCurrencyButReportImpactInHome() {
        val raised = prices + PricePeriodRecord(
            id = "p3", updatedAt = "2", subscriptionId = "s2",
            amountMinor = 2376, effectiveFrom = "2026-06-01",
        )
        val change = PriceWatch.changes(subscriptions, raised, today, gbp).single()

        // The row reads "$19.80 → $23.76" in dollars…
        assertEquals("USD", change.currency)
        assertEquals(1980, change.fromMinor)
        assertEquals(2376, change.toMinor)
        // …but the annual impact is summed across subscriptions, so it converts.
        assertEquals("GBP", change.homeCurrency)
        assertEquals(Rates.convert(396, "USD", "GBP", 0.79), change.annualImpactMinor)
    }

    @Test
    fun aSubscriptionsOwnCostStaysInItsOwnCurrency() {
        // The list shows each subscription in the currency it is billed in;
        // only aggregates are converted. Passing no rates keeps it native.
        val native = Figures.runRate(subscriptions, prices, today)
        assertEquals(165, native["s2"], "Proton's own run rate is \$1.65, not a pound figure")
    }
}
