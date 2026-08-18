package io.github.mzmknight.subtracker.core

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Turning foreign amounts into the home currency, for totals only.
 *
 * Rates are entered by hand rather than fetched. That is a deliberate choice,
 * not a shortcut: the app talks to nothing but the user's own devices, and
 * adding a daily call to an exchange-rate service would leak the fact that they
 * hold foreign subscriptions in exchange for a precision nobody tracking
 * personal subscriptions needs.
 *
 * The honest consequence, stated in the UI: one rate per currency applies to
 * *all* history, so correcting a rate moves what past months appear to have
 * cost. Amounts on the subscription itself are always shown in their own
 * currency and never converted — only aggregates are.
 */
data class Rates(
    val home: String,
    /**
     * Rates the user typed. These always win — an automatic refresh must never
     * overwrite a number someone entered deliberately, or the app quietly
     * disagrees with them every time it syncs.
     */
    private val manual: Map<String, Double> = emptyMap(),
    /** Rates last downloaded, used wherever there is no manual one. */
    private val fetched: Map<String, Double> = emptyMap(),
    /** When [fetched] was retrieved, epoch millis; 0 when never. */
    val fetchedAt: Long = 0L,
) {

    fun rateFor(currency: String): Double? = when {
        currency == home -> 1.0
        else -> manual[currency] ?: fetched[currency]
    }

    fun knows(currency: String): Boolean = rateFor(currency) != null

    /** Whether this rate was typed in rather than downloaded. */
    fun isManual(currency: String): Boolean = currency in manual

    /**
     * Converts into the home currency.
     *
     * An unknown currency passes through unchanged, which is wrong but visibly
     * so — the alternative, dropping it, would quietly under-report a total and
     * look entirely plausible. Callers surface [missingFrom] so the user is told
     * which rate is missing rather than left with a number that is silently off.
     */
    fun toHome(amountMinor: Long, currency: String): Long {
        if (currency == home) return amountMinor
        val rate = rateFor(currency) ?: return amountMinor
        return convert(amountMinor, currency, home, rate)
    }

    /** Currencies present in [currencies] that have no rate and are not home. */
    fun missingFrom(currencies: Collection<String>): List<String> =
        currencies.distinct().filter { it != home && !knows(it) }.sorted()

    /** Sets a rate by hand, pinning it against future refreshes. */
    fun with(currency: String, rate: Double): Rates =
        copy(manual = manual + (currency to rate))

    /** Drops the manual rate, falling back to whatever was last downloaded. */
    fun without(currency: String): Rates = copy(manual = manual - currency)

    /**
     * Replaces the downloaded set. Manual entries are untouched, so a refresh
     * cannot undo a deliberate choice.
     */
    fun withFetched(rates: Map<String, Double>, at: Long): Rates =
        copy(fetched = rates, fetchedAt = at)

    val manualRates: Map<String, Double> get() = manual.toSortedMap()
    val fetchedRates: Map<String, Double> get() = fetched.toSortedMap()

    /** Everything with a rate from either source. */
    val known: Map<String, Double>
        get() = (fetched + manual).toSortedMap()

    companion object {
        /**
         * Rescales between currencies whose minor units differ.
         *
         * ¥1000 is 1000 minor units and £10.00 is 1000 minor units, but they are
         * a thousand yen and ten pounds — so converting cannot just multiply.
         * The amount is taken back to major units, converted, and re-expressed
         * in the target's minor units.
         */
        fun convert(amountMinor: Long, from: String, to: String, rate: Double): Long {
            val fromScale = 10.0.pow(Money.minorDigits(from))
            val toScale = 10.0.pow(Money.minorDigits(to))
            return (amountMinor / fromScale * rate * toScale).roundToLong()
        }

        /**
         * Reads a typed rate.
         *
         * Rejects zero and negatives outright: a zero rate silently erases a
         * subscription from every total, which looks exactly like the app losing
         * data. Accepts a comma as the decimal separator, since most of the
         * places these rates come from write them that way.
         */
        fun parseRate(text: String): Double? {
            val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
            if (!value.isFinite() || value <= 0.0) return null
            return value
        }

        /** "0.79" — trimmed so a whole number does not read as 1.000000. */
        fun formatRate(rate: Double): String {
            val rounded = (rate * 1_000_000).roundToLong() / 1_000_000.0
            if (abs(rounded - rounded.toLong()) < 1e-9) return rounded.toLong().toString()
            return rounded.toString().trimEnd('0').trimEnd('.')
        }
    }
}
