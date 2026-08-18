package io.github.mzmknight.subtracker.ui

import java.time.LocalDate
import io.github.mzmknight.subtracker.core.PlainDate

actual fun systemToday(): PlainDate {
    val now = LocalDate.now()
    return PlainDate.of(now.year, now.monthValue, now.dayOfMonth)
}
