package io.github.mzmknight.subtracker.core

import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.ComputedCharge
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

data class MonthTotal(
    val month: String,
    val totalMinor: Long,
    val chargeCount: Int,
    val settledCount: Int,
    val isFuture: Boolean,
)

/**
 * Everything the dashboard shows, computed from local records.
 *
 * The earlier design cached the server's summary verbatim and refused to
 * recalculate, to avoid two implementations disagreeing about what a month cost.
 * That constraint is gone now for a specific reason: the on-device engine is
 * checked against the server engine on every build via the shared corpus, so
 * "recomputed locally" and "what the server would say" are the same answer.
 *
 * Which is what makes a device usable with no server at all.
 */
data class DashboardFigures(
    val today: String,
    val currency: String,
    val thisMonthMinor: Long,
    val lastMonthMinor: Long,
    val yearToDateMinor: Long,
    val allTimeMinor: Long,
    val monthlyRunRateMinor: Long,
    val annualRunRateMinor: Long,
    val runRateByCategory: Map<String, Long>,
    val activeCount: Int,
    val upcoming30dCount: Int,
    val upcoming30dMinor: Long,
    val monthlySeries: List<MonthTotal>,
    // Every headline above mixes money that has already left the account with
    // money that has not. These four keep the two apart, because "spent £28 this
    // month" and "will spend £28 this month" are different claims and only one
    // of them is true on the 13th.
    /** Charges this month that have already fallen due. */
    val monthSpentMinor: Long = 0,
    /** Charges still to come this month. */
    val monthRemainingMinor: Long = 0,
    /** Charges this calendar year that have already fallen due. */
    val yearSpentMinor: Long = 0,
    /** Charges still to come before the year is out. */
    val yearRemainingMinor: Long = 0,
    /** In use by a subscription but with no conversion rate set. */
    val currenciesWithoutRates: List<String> = emptyList(),
) {
    /** Signed percentage against last month, or null when there's no base. */
    val monthOnMonthPercent: Int?
        get() = if (lastMonthMinor == 0L) null
        else (((thisMonthMinor - lastMonthMinor) * 100) / lastMonthMinor).toInt()

    /** What the whole calendar year comes to, spent and forecast together. */
    val yearTotalMinor: Long get() = yearSpentMinor + yearRemainingMinor

    /** How far through the year's spending, 0..1 — for the progress bar. */
    val yearProgress: Float
        get() = if (yearTotalMinor <= 0) 0f else yearSpentMinor.toFloat() / yearTotalMinor
}

object Figures {

    const val DEFAULT_MONTHS_BACK = 36
    const val DEFAULT_MONTHS_FORWARD = 24

    /**
     * Expand every subscription's schedule and attach any override that applies.
     * Deleted subscriptions contribute nothing, including to history — a deleted
     * subscription is one you decided you never wanted recorded.
     */
    fun computeCharges(
        subscriptions: List<SubscriptionRecord>,
        prices: List<PricePeriodRecord>,
        overrides: List<ChargeOverrideRecord>,
        today: PlainDate,
        monthsBack: Int = DEFAULT_MONTHS_BACK,
        monthsForward: Int = DEFAULT_MONTHS_FORWARD,
    ): List<ComputedCharge> {
        val windowStart = today.plusMonths(-monthsBack)
        val windowEnd = today.plusMonths(monthsForward)

        val pricesBySubscription = prices.filterNot { it.deleted }.groupBy { it.subscriptionId }
        val overridesByKey = overrides.filterNot { it.deleted }
            .associateBy { it.subscriptionId to it.dueDate }

        return subscriptions.filterNot { it.deleted }.flatMap { subscription ->
            val subscriptionPrices = pricesBySubscription[subscription.id].orEmpty()
            if (subscriptionPrices.isEmpty()) return@flatMap emptyList()

            Projection.generate(
                schedule = subscription,
                prices = subscriptionPrices,
                windowStart = windowStart,
                windowEnd = windowEnd,
                today = today,
            ).map { occurrence ->
                ComputedCharge(
                    subscriptionId = subscription.id,
                    due = occurrence.due,
                    amountMinor = occurrence.amountMinor,
                    currency = subscription.currency,
                    override = overridesByKey[subscription.id to occurrence.due.iso],
                )
            }
        }
    }

    /** Normalised monthly cost of everything live, priced as at [today]. */
    fun runRate(
        subscriptions: List<SubscriptionRecord>,
        prices: List<PricePeriodRecord>,
        today: PlainDate,
        rates: Rates? = null,
    ): Map<String, Long> {
        val pricesBySubscription = prices.filterNot { it.deleted }.groupBy { it.subscriptionId }
        val perSubscription = mutableMapOf<String, Long>()

        for (subscription in subscriptions) {
            if (!subscription.isLive) continue
            // Already ended: still has history, but costs nothing going forward.
            val ends = subscription.endDate?.let(PlainDate::parseOrNull)
            if (ends != null && ends < today) continue

            val price = Projection.resolvePriceAt(
                pricesBySubscription[subscription.id].orEmpty(), today,
            ) ?: continue
            val native = Money.monthlyRunRate(
                price.amountMinor, subscription.cycleUnit, subscription.cycleCount,
            )
            // Null keeps the native amount, which is what the subscription list
            // wants — it shows each one in its own currency. Anything summing
            // these has to pass rates in.
            perSubscription[subscription.id] =
                rates?.toHome(native, subscription.currency) ?: native
        }
        return perSubscription
    }

    fun compute(
        subscriptions: List<SubscriptionRecord>,
        prices: List<PricePeriodRecord>,
        overrides: List<ChargeOverrideRecord>,
        today: PlainDate,
        homeCurrency: String = "GBP",
        rates: Rates = Rates(homeCurrency),
        monthsBack: Int = DEFAULT_MONTHS_BACK,
        monthsForward: Int = DEFAULT_MONTHS_FORWARD,
    ): DashboardFigures {
        val charges = computeCharges(subscriptions, prices, overrides, today, monthsBack, monthsForward)
        val thisMonth = today.monthKey
        val lastMonth = today.plusMonths(-1).monthKey
        val year = today.iso.take(4)

        // Every total below is in the home currency. Adding a $23.76 charge to a
        // pile of pounds because both are "2376 minor units" produces a figure
        // that is wrong and looks completely ordinary, which is the worst kind.
        fun ComputedCharge.homeMinor(): Long = rates.toHome(effectiveAmountMinor, currency)

        val grouped = charges.groupBy { it.due.monthKey }
        val series = grouped.map { (month, monthCharges) ->
            MonthTotal(
                month = month,
                totalMinor = monthCharges.sumOf { it.homeMinor() },
                chargeCount = monthCharges.size,
                settledCount = monthCharges.count { it.isSettled(today) },
                isFuture = month > thisMonth,
            )
        }.sortedBy { it.month }

        val runRates = runRate(subscriptions, prices, today, rates)
        val byCategory = mutableMapOf<String, Long>()
        for (subscription in subscriptions) {
            val monthly = runRates[subscription.id] ?: continue
            byCategory[subscription.category] = (byCategory[subscription.category] ?: 0) + monthly
        }

        val horizon = today.plusDays(30)
        val upcoming = charges.filter { it.due >= today && it.due <= horizon && !it.isSkipped }
        val totalRunRate = runRates.values.sum()

        // isSettled is `due < today`, so a charge due today counts as still to
        // come — which is right, it has not left the account yet.
        val monthCharges = charges.filter { it.due.monthKey == thisMonth }
        val yearCharges = charges.filter { it.due.iso.take(4) == year }
        val monthSpent = monthCharges.filter { it.isSettled(today) }.sumOf { it.homeMinor() }
        val yearSpent = yearCharges.filter { it.isSettled(today) }.sumOf { it.homeMinor() }

        return DashboardFigures(
            today = today.iso,
            currency = homeCurrency,
            thisMonthMinor = series.firstOrNull { it.month == thisMonth }?.totalMinor ?: 0,
            lastMonthMinor = series.firstOrNull { it.month == lastMonth }?.totalMinor ?: 0,
            yearToDateMinor = series
                .filter { it.month.take(4) == year && it.month <= thisMonth }
                .sumOf { it.totalMinor },
            allTimeMinor = series.filter { it.month <= thisMonth }.sumOf { it.totalMinor },
            monthlyRunRateMinor = totalRunRate,
            annualRunRateMinor = totalRunRate * 12,
            runRateByCategory = byCategory,
            activeCount = runRates.size,
            upcoming30dCount = upcoming.size,
            upcoming30dMinor = upcoming.sumOf { it.homeMinor() },
            monthlySeries = series,
            monthSpentMinor = monthSpent,
            monthRemainingMinor = monthCharges.sumOf { it.homeMinor() } - monthSpent,
            yearSpentMinor = yearSpent,
            yearRemainingMinor = yearCharges.sumOf { it.homeMinor() } - yearSpent,
            // Surfaced rather than silently absorbed: an unknown currency passes
            // through unconverted, so the user has to be told which rate is
            // missing instead of being handed a total that is quietly wrong.
            currenciesWithoutRates = rates.missingFrom(
                subscriptions.filterNot { it.deleted }.map { it.currency },
            ),
        )
    }
}
