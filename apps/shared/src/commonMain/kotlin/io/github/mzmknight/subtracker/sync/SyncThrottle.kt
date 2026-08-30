package io.github.mzmknight.subtracker.sync

/**
 * Rate-limits the sweeps nobody explicitly asked for.
 *
 * Automatic syncing has a failure mode that manual syncing does not: the app
 * decides for itself when to reach out, so a badly chosen trigger becomes a
 * burst of round trips rather than one. Returning to the app is exactly such a
 * trigger — Android raises ON_RESUME once at launch, moments after the launch
 * sync has already run, and again every time you glance at another app and come
 * back.
 *
 * Every sweep records itself here whatever started it, so a resume that follows
 * a sync of any other kind is correctly treated as redundant.
 *
 * A class with an injected clock rather than a pair of fields on the state
 * holder, because the interesting behaviour is entirely about time and testing
 * it should not require standing up the app.
 */
class SyncThrottle(
    private val intervalMillis: Long,
    private val now: () -> Long,
) {

    private var lastAt: Long? = null

    /**
     * Whether an automatic sweep is due.
     *
     * Always true before anything has run, so a cold start still syncs — the
     * throttle exists to suppress repeats, not the first attempt.
     */
    fun allow(): Boolean {
        val last = lastAt ?: return true
        return now() - last >= intervalMillis
    }

    /** Called by every sweep, however it was triggered. */
    fun record() {
        lastAt = now()
    }
}
