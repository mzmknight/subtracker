package io.github.mzmknight.subtracker.core

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Money as an integer count of minor units, mirroring backend/engine/money.js.
 * Nothing in the client ever holds an amount as a Double — division happens
 * only in format(), on the way to the screen.
 */
object Money {

    /** Mirrors CYCLE_UNITS in backend/engine/money.js. */
    val CYCLE_UNITS = listOf("day", "week", "month", "year")

    private val ZERO_DECIMAL = setOf("JPY", "KRW", "VND", "CLP", "ISK")

    private val SYMBOLS = mapOf(
        "GBP" to "£", "EUR" to "€", "USD" to "$", "JPY" to "¥",
        "AUD" to "A$", "CAD" to "C$", "CHF" to "CHF", "SEK" to "kr",
    )

    /** Offered in the pickers. Anything else still works, it just has no symbol. */
    val KNOWN_CURRENCIES = SYMBOLS.keys.toList()

    /** Mean Gregorian year — only needed where no exact monthly equivalent exists. */
    const val DAYS_PER_YEAR: Double = 365.25

    fun minorDigits(currency: String): Int = if (currency in ZERO_DECIMAL) 0 else 2

    fun symbol(currency: String): String = SYMBOLS[currency] ?: "$currency "

    /** 1299, "GBP" -> "£12.99" */
    fun format(amountMinor: Long, currency: String): String =
        symbol(currency) + formatBare(amountMinor, currency)

    /** 1299, "GBP" -> "12.99" — no symbol, for inputs and dense tables. */
    fun formatBare(amountMinor: Long, currency: String): String {
        val digits = minorDigits(currency)
        val negative = amountMinor < 0
        val absolute = abs(amountMinor)
        if (digits == 0) return (if (negative) "-" else "") + absolute.toString()
        val divisor = 100L
        val whole = absolute / divisor
        val frac = (absolute % divisor).toString().padStart(digits, '0')
        return (if (negative) "-" else "") + "$whole.$frac"
    }

    /**
     * Rounded to the nearest whole unit — "£4,390" reads better on a dashboard
     * tile than "£4,390.00" and the pence are noise at that altitude.
     */
    fun formatRounded(amountMinor: Long, currency: String): String {
        val digits = minorDigits(currency)
        val units = if (digits == 0) amountMinor else (amountMinor + 50) / 100
        return symbol(currency) + groupThousands(units)
    }

    private fun groupThousands(value: Long): String {
        val negative = value < 0
        val digits = abs(value).toString()
        val out = StringBuilder()
        for ((index, ch) in digits.withIndex()) {
            if (index > 0 && (digits.length - index) % 3 == 0) out.append(',')
            out.append(ch)
        }
        return (if (negative) "-" else "") + out
    }

    /** "12.99" -> 1299. Returns null on anything unparseable, for live form input. */
    fun parseOrNull(text: String, currency: String): Long? {
        val digits = minorDigits(currency)
        val trimmed = text.trim().replace(",", "").removePrefix(symbol(currency))
        if (trimmed.isEmpty() || !Regex("""^-?\d*(\.\d+)?$""").matches(trimmed)) return null
        val negative = trimmed.startsWith("-")
        val body = trimmed.removePrefix("-")
        val parts = body.split(".")
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val fracText = if (parts.size > 1) parts[1] else ""
        if (fracText.length > digits) return null
        val frac = if (digits == 0) 0L else fracText.padEnd(digits, '0').toLongOrNull() ?: return null
        val value = whole * (if (digits == 0) 1L else 100L) + frac
        return if (negative) -value else value
    }

    /**
     * Normalised monthly cost. Kept identical to monthlyRunRateMinor() in the
     * backend engine so the client never disagrees with the server about what a
     * subscription "costs per month".
     *
     * roundToLong, not roundToInt: the JS side rounds with no 32-bit ceiling, so
     * rounding to Int here silently clamped anything above Int.MAX_VALUE minor
     * units and made the two engines disagree — on the one figure this comment
     * promises they agree about.
     */
    fun monthlyRunRate(amountMinor: Long, cycleUnit: String, cycleCount: Int): Long =
        (amountMinor * cyclesPerYear(cycleUnit, cycleCount) / 12.0).roundToLong()

    /**
     * Annualised cost, rounded once.
     *
     * Not `monthlyRunRate * 12`: that rounds to the nearest penny *per month*
     * and then multiplies the error by twelve, so a £100/year plan rising to
     * £120/year reports as £20.04 rather than £20. Anything comparing two
     * amounts over a year has to come through here.
     */
    fun annualRunRate(amountMinor: Long, cycleUnit: String, cycleCount: Int): Long =
        (amountMinor * cyclesPerYear(cycleUnit, cycleCount)).roundToLong()

    private fun cyclesPerYear(cycleUnit: String, cycleCount: Int): Double = when (cycleUnit) {
        "day" -> DAYS_PER_YEAR / cycleCount
        "week" -> DAYS_PER_YEAR / (7.0 * cycleCount)
        "month" -> 12.0 / cycleCount
        "year" -> 1.0 / cycleCount
        else -> throw IllegalArgumentException("unknown cycle unit: $cycleUnit")
    }

    /** "every 3 months" / "monthly" / "yearly" — for the subscription list. */
    fun describeCycle(cycleUnit: String, cycleCount: Int): String {
        if (cycleCount == 1) {
            return when (cycleUnit) {
                "day" -> "daily"
                "week" -> "weekly"
                "month" -> "monthly"
                "year" -> "yearly"
                else -> cycleUnit
            }
        }
        if (cycleUnit == "week" && cycleCount == 2) return "fortnightly"
        if (cycleUnit == "month" && cycleCount == 3) return "quarterly"
        if (cycleUnit == "month" && cycleCount == 6) return "twice a year"
        return "every $cycleCount ${cycleUnit}s"
    }
}
