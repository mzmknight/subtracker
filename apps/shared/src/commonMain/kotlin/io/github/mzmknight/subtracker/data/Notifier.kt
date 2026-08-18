package io.github.mzmknight.subtracker.data

import io.github.mzmknight.subtracker.core.Reminder

/**
 * Posting reminders, and arranging to be woken up for the next one.
 *
 * Deliberately on-device rather than pushed from the server. The app is
 * local-first and the server is optional, so reminders that depended on it would
 * simply not arrive for anyone who never set one up — which, right now, is
 * everyone.
 */
interface Notifier {
    /** Whether the platform will actually show anything. */
    val canNotify: Boolean

    /**
     * Ask for permission if the platform requires it and it has not been
     * granted. Safe to call repeatedly; platforms that need nothing do nothing.
     */
    fun requestPermission()

    /**
     * Show these now.
     *
     * More than one at a time is collapsed into a single summary rather than a
     * stack: waking up to six separate notifications about subscriptions is
     * exactly the clutter reminders are supposed to prevent.
     */
    fun post(reminders: List<Reminder>, currency: String)

    /**
     * Arrange to be woken at [epochMillis], replacing any previous request.
     * Null cancels without scheduling a new one.
     */
    fun scheduleWakeUp(epochMillis: Long?)
}

/** Used on any platform that has not supplied one, and by tests. */
object NoNotifier : Notifier {
    override val canNotify: Boolean = false
    override fun requestPermission() = Unit
    override fun post(reminders: List<Reminder>, currency: String) = Unit
    override fun scheduleWakeUp(epochMillis: Long?) = Unit
}

/**
 * Installed by each app module at startup, the same way the settings store and
 * database driver are — the shared module cannot build one without a Context.
 */
object Notifications {
    var notifier: Notifier = NoNotifier
}
