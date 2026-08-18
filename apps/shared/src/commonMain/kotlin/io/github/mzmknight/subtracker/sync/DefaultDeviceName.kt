package io.github.mzmknight.subtracker.sync

/**
 * What this device calls itself before the user renames it.
 *
 * Every device defaulting to the same string makes the pairing list useless —
 * you cannot tell which "SubTracker device" is your laptop. The phone model and
 * the computer name are both already familiar to the person choosing.
 */
expect fun defaultDeviceName(): String
