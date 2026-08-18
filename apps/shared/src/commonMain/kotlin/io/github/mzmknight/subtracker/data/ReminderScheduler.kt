package io.github.mzmknight.subtracker.data

import io.github.mzmknight.subtracker.core.Reminders
import io.github.mzmknight.subtracker.ui.systemToday

/**
 * Announce whatever is due, then book the next wake-up.
 *
 * Runs on every launch, on every alarm and after a reboot. It has to be safe to
 * run at any of those moments and produce nothing new, which is what the
 * announced-key set is for: the same renewal must never be mentioned twice
 * however many times this is called.
 *
 * `systemToday()` lives in the ui package for historical reasons; it is a
 * platform clock, not a composable, and having a second definition of "what day
 * is it" for the scheduler is exactly how a reminder ends up a day out.
 */
object ReminderScheduler {

    fun refresh(store: LocalStore, notifier: Notifier = Notifications.notifier) {
        if (!AppSettings.remindersEnabled) {
            // Nothing pending, and nothing should wake the device for it.
            notifier.scheduleWakeUp(null)
            return
        }

        val today = systemToday()
        val nowMinute = nowLocalMinute()

        val announced = AppSettings.announcedReminders
        val pending = store.upcomingReminders(today).filterNot { it.key in announced }

        val due = Reminders.due(pending, today, nowMinute)
        if (due.isNotEmpty()) {
            notifier.post(due, AppSettings.homeCurrency)
            AppSettings.markAnnounced(due.map { it.key })
        }

        val dueKeys = due.mapTo(mutableSetOf()) { it.key }
        val next = Reminders.nextFire(pending.filterNot { it.key in dueKeys })

        notifier.scheduleWakeUp(next?.let { (date, minute) -> localEpochMillis(date, minute) })
    }

    /**
     * Forget what has been announced.
     *
     * Used when the rules change: moving a reminder from three days out to seven
     * should be able to speak up about a renewal that is already five days away,
     * and it cannot if it is still marked as said.
     */
    fun forgetAnnounced() {
        AppSettings.announcedReminders = emptySet()
    }
}
