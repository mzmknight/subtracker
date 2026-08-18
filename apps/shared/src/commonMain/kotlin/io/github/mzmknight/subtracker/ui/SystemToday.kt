package io.github.mzmknight.subtracker.ui

import io.github.mzmknight.subtracker.core.PlainDate

/**
 * The device's local calendar date.
 *
 * A billing date is a calendar date, so this must be the *local* date, not a UTC
 * instant — otherwise everything either side of midnight settles a day early or
 * late depending on the timezone.
 */
expect fun systemToday(): PlainDate
