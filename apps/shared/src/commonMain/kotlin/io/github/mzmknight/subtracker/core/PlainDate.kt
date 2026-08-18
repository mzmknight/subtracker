package io.github.mzmknight.subtracker.core

import kotlin.jvm.JvmInline

/**
 * A calendar date, held exactly as the backend holds it: "YYYY-MM-DD".
 *
 * Mirrors backend/engine/dates.js on purpose. There is deliberately no
 * conversion to any instant type anywhere in the client — a billing date is a
 * date, and the moment it becomes an Instant it starts drifting by a day
 * depending on the device's timezone.
 *
 * Lexicographic order is chronological for zero-padded ISO dates, so comparison
 * and sorting are free.
 */
@JvmInline
value class PlainDate private constructor(val iso: String) : Comparable<PlainDate> {

    val year: Int get() = iso.substring(0, 4).toInt()
    val month: Int get() = iso.substring(5, 7).toInt()
    val day: Int get() = iso.substring(8, 10).toInt()

    /** "2026-08" — the key the monthly aggregate view groups by. */
    val monthKey: String get() = iso.substring(0, 7)

    override fun compareTo(other: PlainDate): Int = iso.compareTo(other.iso)

    override fun toString(): String = iso

    fun toEpochDay(): Int = daysFromCivil(year, month, day)

    fun plusDays(n: Int): PlainDate = fromEpochDay(toEpochDay() + n)

    /** Adds months, clamping the day to the target month's last day. */
    fun plusMonths(n: Int): PlainDate {
        val total = year * 12 + (month - 1) + n
        val y = total.floorDiv(12)
        val m = total.mod(12) + 1
        return of(y, m, minOf(day, daysInMonth(y, m)))
    }

    fun plusYears(n: Int): PlainDate = plusMonths(n * 12)

    fun daysUntil(other: PlainDate): Int = other.toEpochDay() - toEpochDay()

    /** "9 Aug 2026" */
    fun formatLong(): String = "$day ${MONTH_NAMES[month - 1]} $year"

    /** "9 Aug" — for lists where the year is obvious from context. */
    fun formatShort(): String = "$day ${MONTH_NAMES[month - 1]}"

    /**
     * "09/08/2026" — day first, the way it is written in the UK.
     *
     * For text the user types into or reads back out of an input. Prose keeps
     * [formatLong], where a named month cannot be misread as the day.
     */
    fun formatUk(): String = buildString {
        append(day.toString().padStart(2, '0'))
        append('/')
        append(month.toString().padStart(2, '0'))
        append('/')
        append(year.toString().padStart(4, '0'))
    }

    companion object {
        private val MONTH_NAMES = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
        private val MONTH_LENGTHS = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        private val PATTERN = Regex("""^\d{4}-\d{2}-\d{2}$""")

        fun isLeapYear(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

        fun daysInMonth(y: Int, m: Int): Int =
            if (m == 2 && isLeapYear(y)) 29 else MONTH_LENGTHS[m - 1]

        fun of(y: Int, m: Int, d: Int): PlainDate {
            require(m in 1..12) { "month out of range: $m" }
            require(d in 1..daysInMonth(y, m)) { "day out of range: $y-$m-$d" }
            return PlainDate(
                buildString {
                    append(y.toString().padStart(4, '0'))
                    append('-')
                    append(m.toString().padStart(2, '0'))
                    append('-')
                    append(d.toString().padStart(2, '0'))
                }
            )
        }

        fun parse(text: String): PlainDate {
            require(PATTERN.matches(text)) { "expected YYYY-MM-DD, got \"$text\"" }
            return of(
                text.substring(0, 4).toInt(),
                text.substring(5, 7).toInt(),
                text.substring(8, 10).toInt(),
            )
        }

        /** Returns null instead of throwing, for optional fields the API sends as "". */
        fun parseOrNull(text: String?): PlainDate? =
            if (text.isNullOrBlank()) null else runCatching { parse(text) }.getOrNull()

        /**
         * What a person typed, in whichever of the usual shapes they used:
         * `09/08/2026`, `9-8-2026`, `9.8.26`, or plain ISO `2026-08-09`.
         *
         * **Day first, always.** "01/02/2026" is 1 February, never 2 January.
         * There is no way to tell those apart from the digits alone, so the
         * ambiguity is settled once, here, in favour of how the user writes
         * dates — rather than being decided differently by each caller.
         *
         * ISO is still accepted because it is unambiguous (a four-digit leading
         * component cannot be a day) and it is what the records themselves hold.
         */
        fun parseUserInput(text: String?): PlainDate? {
            val trimmed = text?.trim().orEmpty()
            if (trimmed.isEmpty()) return null

            parseOrNull(trimmed)?.let { return it }

            val parts = trimmed.split('/', '-', '.', ' ').filter { it.isNotBlank() }
            if (parts.size != 3) return null
            if (!parts.all { part -> part.all(Char::isDigit) }) return null

            // A four-digit leading part is a year, so this is ISO-ish with
            // separators we did not expect — not a day-first date.
            if (parts[0].length == 4) {
                return runCatching { of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) }.getOrNull()
            }

            val day = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val year = when (parts[2].length) {
                4 -> parts[2].toIntOrNull() ?: return null
                // Two digits is this century. A subscription anchored in 1926 is
                // not a case worth breaking "9/8/26" for.
                2 -> 2000 + (parts[2].toIntOrNull() ?: return null)
                else -> return null
            }
            return runCatching { of(year, month, day) }.getOrNull()
        }

        fun fromEpochDay(z: Int): PlainDate {
            val zz = z + 719468
            val era = zz.floorDiv(146097)
            val doe = zz - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val y = yoe + era * 400
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = mp + if (mp < 10) 3 else -9
            return of(y + if (m <= 2) 1 else 0, m, d)
        }

        private fun daysFromCivil(year: Int, m: Int, d: Int): Int {
            val y = year - if (m <= 2) 1 else 0
            val era = y.floorDiv(400)
            val yoe = y - era * 400
            val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }
    }
}
