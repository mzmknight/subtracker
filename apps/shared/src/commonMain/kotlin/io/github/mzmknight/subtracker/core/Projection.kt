package io.github.mzmknight.subtracker.core

/**
 * The billing projection, on-device.
 *
 * A direct port of backend/engine/engine.js. Two implementations of this logic
 * is a real risk, so neither is trusted on its own: both are checked against the
 * same generated corpus of cases in ProjectionVectors, which is produced from
 * the JS engine and asserted by both test suites.
 *
 * Devices need this because charges are *derived*. They are never synced — each
 * device recomputes them from the subscription and its price history, which is
 * what keeps the sync payload to a few KB of user intent.
 *
 * Pure: no clock, no storage. `today` is always passed in.
 */
object Projection {

    /** What the engine needs from a subscription. Keeps this file free of wire types. */
    interface Schedule {
        val anchorDate: String
        val cycleUnit: String
        val cycleCount: Int
        val status: String
        val endDate: String?
    }

    /** What the engine needs from a price period. */
    interface Price {
        val amountMinor: Long
        val effectiveFrom: String
    }

    data class Occurrence(val due: PlainDate, val amountMinor: Long)

    private const val MAX_ITERATIONS = 100_000

    val SUBSCRIPTION_STATUSES = listOf("active", "trial", "paused", "cancelled")

    /**
     * The nth occurrence, counted from the anchor.
     *
     * Deliberately a function of n rather than of the previous occurrence.
     * Stepping from the previous date lets one month-end clamp corrupt the whole
     * schedule: 31 Jan -> 28 Feb -> 28 Mar, when March should return to the 31st.
     */
    fun occurrenceAt(schedule: Schedule, n: Int): PlainDate {
        val anchor = PlainDate.parse(schedule.anchorDate)
        return when (schedule.cycleUnit) {
            "month" -> anchor.plusMonths(n * schedule.cycleCount)
            "year" -> anchor.plusMonths(n * schedule.cycleCount * 12)
            "week" -> anchor.plusDays(n * schedule.cycleCount * 7)
            "day" -> anchor.plusDays(n * schedule.cycleCount)
            else -> throw IllegalArgumentException("unknown cycle unit: ${schedule.cycleUnit}")
        }
    }

    /**
     * Last date this subscription can still bill on.
     *  - cancelled: stops at end_date, or today if none was recorded
     *  - paused: keeps its history but generates nothing in the future
     *  - end_date: always caps, whatever the status
     */
    fun generationEnd(schedule: Schedule, today: PlainDate, windowEnd: PlainDate): PlainDate {
        var end = windowEnd
        if (schedule.status == "paused") {
            end = minOf(end, today)
        }
        if (schedule.status == "cancelled") {
            end = minOf(end, schedule.endDate?.let(PlainDate::parse) ?: today)
        }
        schedule.endDate?.let { PlainDate.parseOrNull(it)?.let { date -> end = minOf(end, date) } }
        return end
    }

    private fun monthsBetween(from: PlainDate, to: PlainDate): Int {
        var months = (to.year - from.year) * 12 + (to.month - from.month)
        if (to.day < from.day) months -= 1
        return months
    }

    /**
     * Skip to the first occurrence that could fall inside the window rather than
     * walking from the anchor — a daily subscription started years ago would
     * otherwise burn thousands of iterations on every recompute.
     *
     * Deliberately lands one step early: month clamping makes n-to-date slightly
     * non-uniform, and starting a step short costs nothing.
     */
    private fun firstCandidateIndex(schedule: Schedule, windowStart: PlainDate): Int {
        val anchor = PlainDate.parse(schedule.anchorDate)
        val n = when (schedule.cycleUnit) {
            "month" -> monthsBetween(anchor, windowStart) / schedule.cycleCount
            "year" -> monthsBetween(anchor, windowStart) / (schedule.cycleCount * 12)
            "week" -> (windowStart.toEpochDay() - anchor.toEpochDay()) / (schedule.cycleCount * 7)
            else -> (windowStart.toEpochDay() - anchor.toEpochDay()) / schedule.cycleCount
        }
        return maxOf(0, n - 1)
    }

    fun validate(schedule: Schedule) {
        require(schedule.status in SUBSCRIPTION_STATUSES) {
            "status must be one of ${SUBSCRIPTION_STATUSES.joinToString("/")}, got \"${schedule.status}\""
        }
        require(schedule.cycleUnit in Money.CYCLE_UNITS) {
            "cycle_unit must be one of ${Money.CYCLE_UNITS.joinToString("/")}, got \"${schedule.cycleUnit}\""
        }
        require(schedule.cycleCount >= 1) {
            "cycle_count must be a positive integer, got ${schedule.cycleCount}"
        }
        val anchor = PlainDate.parse(schedule.anchorDate)
        schedule.endDate?.takeIf { it.isNotBlank() }?.let {
            require(PlainDate.parse(it) >= anchor) { "end_date is before anchor_date" }
        }
    }

    /** Every billing date inside [windowStart, windowEnd]. */
    fun generateOccurrences(
        schedule: Schedule,
        windowStart: PlainDate,
        windowEnd: PlainDate,
        today: PlainDate,
    ): List<PlainDate> {
        validate(schedule)
        val end = generationEnd(schedule, today, windowEnd)
        val anchor = PlainDate.parse(schedule.anchorDate)
        if (anchor > end) return emptyList()

        val out = mutableListOf<PlainDate>()
        var n = firstCandidateIndex(schedule, windowStart)
        var guard = 0
        while (guard++ < MAX_ITERATIONS) {
            val date = occurrenceAt(schedule, n)
            if (date > end) break
            if (date >= windowStart) out += date
            n++
        }
        check(guard < MAX_ITERATIONS) { "occurrence generation did not terminate" }
        return out
    }

    /**
     * The price in force on a date: the latest period starting on or before it.
     * Charges predating every recorded price fall back to the earliest one —
     * the forgiving reading of a price period added later than the anchor, and
     * better than silently dropping charges out of the history.
     */
    fun <P : Price> resolvePriceAt(prices: List<P>, date: PlainDate): P? {
        if (prices.isEmpty()) return null
        val sorted = prices.sortedBy { it.effectiveFrom }
        return sorted.lastOrNull { PlainDate.parse(it.effectiveFrom) <= date } ?: sorted.first()
    }

    /**
     * The full schedule with each occurrence priced at whatever was in force on
     * its own due date — which is why price history is a table and not a column.
     */
    fun generate(
        schedule: Schedule,
        prices: List<Price>,
        windowStart: PlainDate,
        windowEnd: PlainDate,
        today: PlainDate,
    ): List<Occurrence> {
        if (prices.isEmpty()) return emptyList()
        return generateOccurrences(schedule, windowStart, windowEnd, today).mapNotNull { due ->
            resolvePriceAt(prices, due)?.let { Occurrence(due, it.amountMinor) }
        }
    }
}
