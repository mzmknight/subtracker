package io.github.mzmknight.subtracker.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The throttle that keeps automatic syncing from becoming a burst.
 *
 * Time is injected, because the behaviour worth testing is entirely about
 * elapsed time and a test that had to wait thirty seconds to prove it would
 * never be run.
 */
class SyncThrottleTest {

    private var clock = 1_786_000_000_000L
    private fun throttle(interval: Long = 30_000L) = SyncThrottle(interval) { clock }

    @Test
    fun theFirstAttemptIsAlwaysAllowed() {
        // A cold start must sync. The throttle suppresses repeats, not openings.
        assertTrue(throttle().allow())
    }

    @Test
    fun aResumeImmediatelyAfterTheLaunchSyncIsSuppressed() {
        // The case this exists for: Android raises ON_RESUME once at launch,
        // moments after startSyncing() has already swept.
        val throttle = throttle()
        throttle.record()
        assertFalse(throttle.allow(), "the resume that follows the launch sync is redundant")
    }

    @Test
    fun aResumeAfterTheIntervalIsAllowed() {
        val throttle = throttle()
        throttle.record()
        clock += 30_000
        assertTrue(throttle.allow(), "exactly the interval later is due")
    }

    @Test
    fun flickingBetweenAppsDoesNotSweepEveryTime() {
        val throttle = throttle()
        throttle.record()

        // Six glances at another app over half the interval.
        repeat(6) {
            clock += 2_500
            assertFalse(throttle.allow(), "still inside the window at +${(it + 1) * 2_500}ms")
        }

        clock += 20_000
        assertTrue(throttle.allow())
    }

    @Test
    fun recordingAgainRestartsTheWindow() {
        val throttle = throttle()
        throttle.record()
        clock += 25_000
        // A push or a pull-to-refresh landed here; it counts as a sweep, so the
        // resume five seconds later has nothing left to do.
        throttle.record()
        clock += 5_000
        assertFalse(throttle.allow())
    }

    @Test
    fun aClockThatJumpsBackwardsDoesNotUnblockForever() {
        // Wall time can move backwards. The worst this may do is delay a sweep;
        // it must never wedge the throttle open or shut permanently.
        val throttle = throttle()
        throttle.record()
        clock -= 60_000
        assertFalse(throttle.allow(), "a backwards jump must not look like elapsed time")
        clock += 120_000
        assertTrue(throttle.allow())
    }
}
