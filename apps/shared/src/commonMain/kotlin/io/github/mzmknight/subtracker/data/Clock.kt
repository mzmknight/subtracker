package io.github.mzmknight.subtracker.data

import io.github.mzmknight.subtracker.core.PlainDate

/** Wall-clock milliseconds. Used for the HLC and for "last synced" wording. */
expect fun nowEpochMillis(): Long

/** Local time of day as minutes since midnight — the unit reminder rules use. */
expect fun nowLocalMinute(): Int

/**
 * When [minute] past midnight on [date] happens here, as epoch millis.
 *
 * Needed because a reminder is set in local wall-clock terms ("09:00, two days
 * before") but an alarm is scheduled in absolute time, and the two only line up
 * once a timezone is applied.
 */
expect fun localEpochMillis(date: PlainDate, minute: Int): Long

/** "just now", "12 minutes ago", "3 hours ago", "2 days ago". */
fun describeAge(savedAtEpochMs: Long, now: Long = nowEpochMillis()): String {
    if (savedAtEpochMs <= 0) return "never"
    val seconds = ((now - savedAtEpochMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 90 -> "just now"
        seconds < 3600 -> "${seconds / 60} minutes ago"
        seconds < 7200 -> "an hour ago"
        seconds < 172_800 -> "${seconds / 3600} hours ago"
        else -> "${seconds / 86_400} days ago"
    }
}
