package io.github.mzmknight.subtracker.core

import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * One recorded change in what a service charges.
 *
 * [annualImpactMinor] is the number worth reading. A £2 rise on something billed
 * monthly and a £2 rise on something billed yearly are the same headline and
 * wildly different amounts of money, so both are normalised to a year before
 * being compared or summed.
 */
data class PriceChange(
    val subscriptionId: String,
    val name: String,
    val colourHex: String,
    val category: String,
    val icon: String,
    val currency: String,
    val fromMinor: Long,
    val toMinor: Long,
    val effectiveFrom: String,
    val annualImpactMinor: Long,
    /** [annualImpactMinor] is in this, which may differ from [currency]. */
    val homeCurrency: String,
    val isScheduled: Boolean,
) {
    val deltaMinor: Long get() = toMinor - fromMinor
    val isIncrease: Boolean get() = deltaMinor > 0

    /**
     * Percentage change against the old price, rounded away from zero so a real
     * rise never displays as "0%".
     */
    val percent: Int
        get() {
            if (fromMinor <= 0) return 0
            val exact = deltaMinor * 100.0 / fromMinor
            return when {
                exact > 0 && exact < 1 -> 1
                exact < 0 && exact > -1 -> -1
                else -> exact.toInt()
            }
        }

    val date: PlainDate? get() = PlainDate.parseOrNull(effectiveFrom)
}

/**
 * Price creep, across everything.
 *
 * The per-subscription price history already existed — it has to, because each
 * charge is valued at the price in force on its own due date, which is what
 * stops a rise today from rewriting what last year cost. This reads the same
 * table sideways to answer the question that history alone cannot: what has been
 * quietly getting more expensive?
 */
object PriceWatch {

    /** Far enough back to catch an annual review cycle twice over. */
    const val DEFAULT_WITHIN_MONTHS = 24

    fun changes(
        subscriptions: List<SubscriptionRecord>,
        prices: List<PricePeriodRecord>,
        today: PlainDate,
        rates: Rates? = null,
        withinMonths: Int = DEFAULT_WITHIN_MONTHS,
    ): List<PriceChange> {
        val horizon = today.plusMonths(-withinMonths)
        val livePrices = prices.filterNot { it.deleted }.groupBy { it.subscriptionId }

        return subscriptions.filterNot { it.deleted }.flatMap { subscription ->
            val ordered = livePrices[subscription.id]
                .orEmpty()
                .filter { PlainDate.parseOrNull(it.effectiveFrom) != null }
                .sortedBy { it.effectiveFrom }

            // The opening price is not a change — there was nothing before it.
            ordered.zipWithNext().mapNotNull { (previous, current) ->
                if (previous.amountMinor == current.amountMinor) return@mapNotNull null

                val effective = PlainDate.parse(current.effectiveFrom)
                if (effective < horizon) return@mapNotNull null

                // Annualised directly, not via the monthly run rate: rounding to
                // the penny per month and then multiplying by twelve turns a £20
                // yearly rise into £20.04.
                val before = Money.annualRunRate(
                    previous.amountMinor, subscription.cycleUnit, subscription.cycleCount,
                )
                val after = Money.annualRunRate(
                    current.amountMinor, subscription.cycleUnit, subscription.cycleCount,
                )

                PriceChange(
                    subscriptionId = subscription.id,
                    name = subscription.name,
                    colourHex = subscription.colour,
                    category = subscription.category,
                    icon = subscription.icon,
                    currency = subscription.currency,
                    fromMinor = previous.amountMinor,
                    toMinor = current.amountMinor,
                    effectiveFrom = current.effectiveFrom,
                    // From/to stay in the subscription's own currency — the row
                    // reads "$19.80 → $23.76" — but the annual impact is summed
                    // across subscriptions into one headline, so it converts.
                    annualImpactMinor = rates?.toHome(after - before, subscription.currency)
                        ?: (after - before),
                    homeCurrency = rates?.home ?: subscription.currency,
                    // A rise you have recorded but which has not started yet is
                    // the most actionable kind — it is still cancellable.
                    isScheduled = effective > today,
                )
            }
        }.sortedByDescending { it.effectiveFrom }
    }

    /**
     * What the rises in [changes] add to a year, net of any reductions.
     *
     * Only counts changes already in force. A scheduled rise has not cost
     * anything yet, and quoting it as though it had overstates the damage.
     */
    fun annualCreepMinor(changes: List<PriceChange>): Long =
        changes.filterNot { it.isScheduled }.sumOf { it.annualImpactMinor }
}
