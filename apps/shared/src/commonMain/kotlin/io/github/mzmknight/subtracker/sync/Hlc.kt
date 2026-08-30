package io.github.mzmknight.subtracker.sync

import io.github.mzmknight.subtracker.core.Lock

/**
 * Hybrid logical clock.
 *
 * Plain wall-clock timestamps are not good enough for last-write-wins across
 * devices: phones and laptops disagree by seconds or worse, so an edit made
 * later can carry an earlier timestamp and silently lose. An HLC keeps wall
 * time when it is moving forward, and falls back to a counter when it isn't,
 * so causality is preserved even with a skewed or backwards-jumping clock.
 *
 * Encoded as a fixed-width string so lexicographic order *is* causal order —
 * which means SQL `ORDER BY updated_at` and a plain string compare both work
 * without parsing anything.
 */
data class Hlc(
    val millis: Long,
    val counter: Int,
    val deviceId: String,
) : Comparable<Hlc> {

    /** "0001786000000000-00000-a1b2c3d4e5f60718" */
    fun encode(): String =
        millis.toString().padStart(MILLIS_WIDTH, '0') + "-" +
            counter.toString().padStart(COUNTER_WIDTH, '0') + "-" +
            deviceId

    override fun compareTo(other: Hlc): Int {
        millis.compareTo(other.millis).let { if (it != 0) return it }
        counter.compareTo(other.counter).let { if (it != 0) return it }
        return deviceId.compareTo(other.deviceId)
    }

    override fun toString(): String = encode()

    companion object {
        // 16 digits covers milliseconds well past the year 300,000 — the point
        // is only that the width never changes, or sorting breaks.
        private const val MILLIS_WIDTH = 16
        private const val COUNTER_WIDTH = 5

        val ZERO = Hlc(0, 0, "")

        fun parse(encoded: String): Hlc? {
            val parts = encoded.split("-")
            if (parts.size != 3) return null
            val millis = parts[0].toLongOrNull() ?: return null
            val counter = parts[1].toIntOrNull() ?: return null
            return Hlc(millis, counter, parts[2])
        }

        /** Ordering for the encoded form, tolerating anything unparseable as oldest. */
        fun compareEncoded(a: String, b: String): Int {
            val left = parse(a) ?: ZERO
            val right = parse(b) ?: ZERO
            return left.compareTo(right)
        }
    }
}

/**
 * Issues timestamps for this device.
 *
 * `now` is injected rather than read from a global clock so the tests can
 * simulate skew and backwards jumps, which is the entire reason this class
 * exists.
 *
 * Thread-safe, and it has to be. Every method here reads `last`, derives a new
 * value from it and writes it back, which is only atomic if something makes it
 * so. Two threads do meet here: the app's own writes are stamped on the Compose
 * scope, while a peer that opened the connection is merged on a Ktor handler
 * thread — and automatic syncing means that now happens without anyone pressing
 * anything. Interleaved, two callers can read the same `last` and both derive
 * the same stamp from it, so a counter meant to guarantee distinct, causally
 * ordered timestamps quietly stops doing either.
 */
class HlcClock(
    private val deviceId: String,
    private val now: () -> Long,
) {
    private val lock = Lock()
    private var last: Hlc = Hlc(0, 0, deviceId)

    /** Current state, for persisting across restarts. */
    fun peek(): Hlc = lock.withLock { last }

    fun restore(state: Hlc?) = lock.withLock {
        if (state != null && state > last) last = Hlc(state.millis, state.counter, deviceId)
    }

    /** Stamp a local change. */
    fun tick(): Hlc = lock.withLock {
        val wall = now()
        last = if (wall > last.millis) {
            Hlc(wall, 0, deviceId)
        } else {
            // Clock hasn't advanced (or went backwards) — keep causality with
            // the counter rather than emitting a timestamp that sorts earlier.
            Hlc(last.millis, last.counter + 1, deviceId)
        }
        last
    }

    /**
     * Fold in a timestamp seen from another device, so anything this device
     * writes afterwards sorts *after* what it just learned about. Without this,
     * a device with a slow clock would keep losing every merge.
     */
    fun observe(remote: Hlc): Hlc = lock.withLock {
        val wall = now()
        val highest = maxOf(wall, last.millis, remote.millis)
        val counter = when {
            highest == last.millis && highest == remote.millis -> maxOf(last.counter, remote.counter) + 1
            highest == last.millis -> last.counter + 1
            highest == remote.millis -> remote.counter + 1
            else -> 0
        }
        last = Hlc(highest, counter, deviceId)
        last
    }
}
