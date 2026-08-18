package io.github.mzmknight.subtracker.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import io.github.mzmknight.subtracker.core.PlainDate

actual fun nowEpochMillis(): Long = System.currentTimeMillis()

actual fun nowLocalMinute(): Int = LocalTime.now().let { it.hour * 60 + it.minute }

actual fun localEpochMillis(date: PlainDate, minute: Int): Long =
    // Start of day plus minutes, rather than atTime(hour, minute): on the night
    // the clocks go forward, 01:30 does not exist and atTime silently shifts it.
    // Adding to a zoned start-of-day resolves the gap the way the calendar does.
    LocalDate.of(date.year, date.month, date.day)
        .atStartOfDay(ZoneId.systemDefault())
        .plusMinutes(minute.toLong())
        .toInstant()
        .toEpochMilli()
