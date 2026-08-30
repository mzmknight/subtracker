package io.github.mzmknight.subtracker.sync

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clock under the conditions it actually runs in.
 *
 * Automatic syncing put two threads on this class where there had effectively
 * been one: local writes are stamped on the Compose scope, while a peer that
 * opened the connection is merged on a Ktor handler thread, and pushes now fire
 * on a background dispatcher after every edit rather than only when someone
 * presses a button.
 *
 * Every method reads `last`, derives from it and writes it back. Unguarded, two
 * callers read the same value and derive the same stamp — so the counter that
 * exists to keep timestamps distinct and causally ordered stops doing either.
 * These tests fail against the unlocked version.
 *
 * Desktop-only: this needs real threads, and it is the same JVM code on Android.
 */
class HlcConcurrencyTest {

    private val threads = 8
    private val perThread = 2_000

    @Test
    fun concurrentTicksAreAllDistinct() {
        // A clock frozen at one millisecond, so *every* tick has to go through
        // the counter. Real wall time advancing would hide the race behind
        // millis changing underneath it.
        val clock = HlcClock("device0000000001") { 1_786_000_000_000 }
        val stamps = ConcurrentLinkedQueue<String>()

        runInParallel { repeat(perThread) { stamps += clock.tick().encode() } }

        assertEquals(threads * perThread, stamps.size, "every call returned something")
        assertEquals(
            stamps.size,
            stamps.toSet().size,
            "two threads derived the same stamp from the same counter",
        )
    }

    @Test
    fun concurrentTicksStayMonotonic() {
        // Wall time moves too, which is the ordinary case: the stamps must still
        // form a strictly increasing sequence once sorted, with no duplicates
        // and no value that sorts before one already issued.
        val wall = AtomicLong(1_786_000_000_000)
        val clock = HlcClock("device0000000001") { wall.getAndAdd(1) }
        val stamps = ConcurrentLinkedQueue<Hlc>()

        runInParallel { repeat(perThread) { stamps += clock.tick() } }

        val sorted = stamps.sorted()
        for (i in 1 until sorted.size) {
            assertTrue(
                sorted[i] > sorted[i - 1],
                "stamp $i is not strictly after its predecessor: ${sorted[i - 1]} then ${sorted[i]}",
            )
        }
    }

    @Test
    fun tickAndObserveTogetherNeverGoBackwards() {
        // Both entry points at once — a peer merging while the user is editing,
        // which is exactly what a push after a write produces.
        val clock = HlcClock("device0000000001") { 1_786_000_000_000 }
        val seen = ConcurrentLinkedQueue<Hlc>()
        val remote = Hlc(1_786_000_000_000, 0, "device0000000002")

        runInParallel { index ->
            repeat(perThread) {
                seen += if (index % 2 == 0) clock.tick() else clock.observe(remote)
            }
        }

        assertEquals(
            seen.size,
            seen.toSet().size,
            "a tick and an observe derived the same stamp",
        )
        // The final state must be at least as high as everything handed out, or
        // a restart would reissue a stamp that is already on a record.
        val highest = seen.max()
        assertTrue(clock.peek() >= highest, "the clock ended behind a stamp it had already issued")
    }

    private fun runInParallel(body: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        try {
            repeat(threads) { index ->
                pool.execute {
                    // Released together, so the threads genuinely overlap rather
                    // than each finishing before the next is scheduled.
                    start.await()
                    try {
                        body(index)
                    } finally {
                        done.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(done.await(60, TimeUnit.SECONDS), "workers finished")
        } finally {
            pool.shutdownNow()
        }
    }
}
