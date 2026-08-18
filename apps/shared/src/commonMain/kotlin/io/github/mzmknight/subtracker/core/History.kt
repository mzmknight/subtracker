package io.github.mzmknight.subtracker.core

import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.ComputedCharge
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord


/**
 * What one subscription cost inside one month, and how much of that month it was.
 *
 * Carries the subscription's own name and colour rather than just its id: the
 * history is browsed months at a time, and re-resolving every id against the
 * subscription list on each recomposition is work the UI should not be doing.
 */
data class MonthShare(
    val subscriptionId: String,
    val name: String,
    val colourHex: String,
    val category: String,
    val totalMinor: Long,
    val chargeCount: Int,
) {
    /** Share of the month, 0..1. */
    fun fractionOf(monthTotalMinor: Long): Float =
        if (monthTotalMinor <= 0) 0f else totalMinor.toFloat() / monthTotalMinor
}

/**
 * One month of history, already broken down by subscription.
 *
 * Nothing here is stored. Charges are a pure function of subscriptions and their
 * price periods, so browsing five years back is a wider projection window rather
 * than a query against saved rows — which is also why correcting a price rise
 * retroactively fixes every month it touched.
 */
data class MonthHistory(
    val month: String,
    val totalMinor: Long,
    val currency: String,
    val isFuture: Boolean,
    val isCurrent: Boolean,
    val shares: List<MonthShare>,
    val charges: List<ComputedCharge>,
) {
    val year: String get() = month.take(4)
    val chargeCount: Int get() = charges.size

    fun percentOf(share: MonthShare): Int = sharePercent(share.totalMinor, totalMinor)
}

/**
 * Whole-percent share, biased so that a subscription that cost anything at all
 * never renders as "0%" — at which point the row contradicts the amount printed
 * next to it.
 */
internal fun sharePercent(partMinor: Long, totalMinor: Long): Int {
    if (totalMinor <= 0) return 0
    val exact = partMinor * 100.0 / totalMinor
    return if (exact > 0 && exact < 1) 1 else exact.toInt()
}

/**
 * A year's worth of months, newest first.
 *
 * Carries its own [shares] so a collapsed year can still say what it was made
 * of. Without that, folding the months away to cut clutter also throws away the
 * only thing that made the year readable at a glance.
 */
data class YearHistory(
    val year: String,
    val totalMinor: Long,
    val currency: String,
    val months: List<MonthHistory>,
    val shares: List<MonthShare>,
) {
    fun percentOf(share: MonthShare): Int = sharePercent(share.totalMinor, totalMinor)
}

object History {

    /** Five years back is further than anyone has receipts for, and still cheap. */
    const val DEFAULT_MONTHS_BACK = 60

    /**
     * Only far enough forward to finish the current month.
     *
     * The projection has to reach past today or a charge due on the 28th would
     * be missing from the month you are standing in — but no further, because
     * anything beyond that is forecast, and a screen called History that opens
     * on next year's predictions buries the thing it exists to show.
     */
    const val DEFAULT_MONTHS_FORWARD = 2

    /**
     * Every month that has any charge in it, newest first.
     *
     * Months with nothing in them are absent rather than present-and-zero: an
     * empty row for a month before you subscribed to anything is noise, and the
     * gap itself is the honest signal.
     */
    fun months(
        subscriptions: List<SubscriptionRecord>,
        prices: List<PricePeriodRecord>,
        overrides: List<ChargeOverrideRecord>,
        today: PlainDate,
        homeCurrency: String = "GBP",
        rates: Rates = Rates(homeCurrency),
        includeFuture: Boolean = false,
        monthsBack: Int = DEFAULT_MONTHS_BACK,
        monthsForward: Int = DEFAULT_MONTHS_FORWARD,
    ): List<MonthHistory> {
        val charges = Figures.computeCharges(
            subscriptions, prices, overrides, today, monthsBack, monthsForward,
        )
        if (charges.isEmpty()) return emptyList()

        // Deleted subscriptions generate no charges at all, so anything that
        // survived computeCharges is guaranteed to resolve here.
        val byId = subscriptions.associateBy { it.id }
        val thisMonth = today.monthKey

        // Everything here is in the home currency, including each share — the
        // composition bar divides one by the other, so a mix of units would draw
        // a chart whose slices do not add up to its own total.
        fun ComputedCharge.homeMinor(): Long = rates.toHome(effectiveAmountMinor, currency)

        return charges.groupBy { it.due.monthKey }.map { (month, monthCharges) ->
            val total = monthCharges.sumOf { it.homeMinor() }

            val shares = monthCharges
                .groupBy { it.subscriptionId }
                .map { (subscriptionId, subCharges) ->
                    val subscription = byId[subscriptionId]
                    MonthShare(
                        subscriptionId = subscriptionId,
                        name = subscription?.name ?: "Unknown",
                        colourHex = subscription?.colour.orEmpty(),
                        category = subscription?.category ?: "other",
                        totalMinor = subCharges.sumOf { it.homeMinor() },
                        chargeCount = subCharges.size,
                    )
                }
                // Largest first: the composition bar and its legend have to agree,
                // and "what dominated this month" is the question being asked.
                .sortedWith(compareByDescending<MonthShare> { it.totalMinor }.thenBy { it.name.lowercase() })

            MonthHistory(
                month = month,
                totalMinor = total,
                currency = homeCurrency,
                isFuture = month > thisMonth,
                isCurrent = month == thisMonth,
                shares = shares,
                charges = monthCharges.sortedBy { it.due.iso },
            )
        }
            .filter { includeFuture || !it.isFuture }
            .sortedByDescending { it.month }
    }

    /** The same months grouped into years, newest first. */
    fun years(months: List<MonthHistory>): List<YearHistory> =
        months.groupBy { it.year }
            .map { (year, yearMonths) ->
                // Forecast months are excluded from a year's headline total and
                // from its breakdown: summing spent money together with predicted
                // money produces a figure that is neither, and it is the one
                // people quote.
                val settled = yearMonths.filterNot { it.isFuture }

                val shares = settled
                    .flatMap { it.shares }
                    .groupBy { it.subscriptionId }
                    .map { (_, subShares) ->
                        // Name and colour come from the most recent month the
                        // subscription appears in, so a rename shows the current
                        // name rather than whatever it was called in January.
                        val latest = subShares.first()
                        latest.copy(
                            totalMinor = subShares.sumOf { it.totalMinor },
                            chargeCount = subShares.sumOf { it.chargeCount },
                        )
                    }
                    .sortedWith(
                        compareByDescending<MonthShare> { it.totalMinor }.thenBy { it.name.lowercase() },
                    )

                YearHistory(
                    year = year,
                    totalMinor = settled.sumOf { it.totalMinor },
                    currency = yearMonths.firstOrNull()?.currency ?: "GBP",
                    months = yearMonths.sortedByDescending { it.month },
                    shares = shares,
                )
            }
            .sortedByDescending { it.year }
}
