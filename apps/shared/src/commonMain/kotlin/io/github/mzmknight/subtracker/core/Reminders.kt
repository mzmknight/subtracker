package io.github.mzmknight.subtracker.core

import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * When to warn about a renewal, and how loudly.
 *
 * [minute] is minutes since local midnight rather than a time type, because it
 * is stored in a record that syncs and has to mean the same thing after a round
 * trip through SQLite, JSON and a spreadsheet.
 */
data class ReminderRule(val daysBefore: Int, val minute: Int) {
    val hour: Int get() = minute / 60
    val minuteOfHour: Int get() = minute % 60

    /** "09:00" */
    fun formatTime(): String =
        hour.toString().padStart(2, '0') + ":" + minuteOfHour.toString().padStart(2, '0')

    fun describe(): String = when (daysBefore) {
        0 -> "On the day, at ${formatTime()}"
        1 -> "1 day before, at ${formatTime()}"
        else -> "$daysBefore days before, at ${formatTime()}"
    }

    companion object {
        const val MAX_DAYS_BEFORE = 30

        fun of(daysBefore: Int, minute: Int) = ReminderRule(
            daysBefore = daysBefore.coerceIn(0, MAX_DAYS_BEFORE),
            minute = minute.coerceIn(0, 24 * 60 - 1),
        )
    }
}

/** What the device should tell the user, and when. */
data class Reminder(
    val subscriptionId: String,
    val name: String,
    val amountMinor: Long,
    val currency: String,
    val dueDate: PlainDate,
    val fireDate: PlainDate,
    val fireMinute: Int,
) {
    /**
     * A stable key for "this reminder, for this charge".
     *
     * Used to avoid telling someone the same thing twice when an alarm fires
     * more than once — which it will, because rescheduling happens on every
     * launch and on every boot.
     */
    val key: String get() = "$subscriptionId@${dueDate.iso}"

    fun daysBefore(): Int = fireDate.daysUntil(dueDate)

    /** "Netflix renews tomorrow — £12.99" */
    fun title(): String = name

    fun body(): String {
        val amount = Money.format(amountMinor, currency)
        return when (val days = daysBefore()) {
            0 -> "Renews today · $amount"
            1 -> "Renews tomorrow · $amount"
            else -> "Renews in $days days, ${dueDate.formatShort()} · $amount"
        }
    }
}

object Reminders {

    val DEFAULT = ReminderRule(daysBefore = 3, minute = 9 * 60)

    const val MODE_INHERIT = ""
    const val MODE_OFF = "off"
    const val MODE_CUSTOM = "custom"

    /**
     * The rule actually in force for a subscription, or null when it should
     * never be announced.
     *
     * The global switch wins over everything: turning reminders off on a device
     * has to mean off, or the switch is a lie. A per-subscription rule only
     * chooses *how* to be reminded, never whether to override a global no.
     */
    fun ruleFor(
        subscription: SubscriptionRecord,
        globalEnabled: Boolean,
        globalRule: ReminderRule,
    ): ReminderRule? {
        if (!globalEnabled) return null
        if (!subscription.isLive) return null
        return when (subscription.notifyMode) {
            MODE_OFF -> null
            MODE_CUSTOM -> ReminderRule.of(subscription.notifyDaysBefore, subscription.notifyMinute)
            else -> globalRule
        }
    }

    /**
     * Every reminder that has not yet fired, earliest first.
     *
     * [nowMinute] matters as much as [today]: a reminder set for 09:00 that is
     * three days out has already passed if it is now 10:00 on the day it was due
     * to fire, and re-announcing it on every app launch would be maddening.
     *
     * Charges are looked at rather than anchor dates because a skipped charge
     * should not produce a reminder — the whole point of skipping it was that it
     * is not happening.
     */
    fun upcoming(
        subscriptions: List<SubscriptionRecord>,
        prices: List<PricePeriodRecord>,
        overrides: List<ChargeOverrideRecord>,
        today: PlainDate,
        globalEnabled: Boolean,
        globalRule: ReminderRule = DEFAULT,
        horizonDays: Int = 45,
    ): List<Reminder> {
        if (!globalEnabled) return emptyList()

        val charges = Figures.computeCharges(
            subscriptions, prices, overrides, today, monthsBack = 0, monthsForward = 3,
        )
        val byId = subscriptions.associateBy { it.id }
        val horizon = today.plusDays(horizonDays)

        return charges
            .asSequence()
            .filterNot { it.isSkipped || it.isRefunded }
            .filter { it.due >= today && it.due <= horizon }
            .mapNotNull { charge ->
                val subscription = byId[charge.subscriptionId] ?: return@mapNotNull null
                val rule = ruleFor(subscription, globalEnabled, globalRule) ?: return@mapNotNull null

                // A fire date already in the past is kept, not dropped: adding a
                // subscription that renews in two days when the rule says "seven
                // days before" should still say something, once. Whether it has
                // already been said is the caller's business, not this function's.
                val fireDate = charge.due.plusDays(-rule.daysBefore)

                Reminder(
                    subscriptionId = subscription.id,
                    name = subscription.name,
                    amountMinor = charge.effectiveAmountMinor,
                    currency = charge.currency,
                    dueDate = charge.due,
                    fireDate = fireDate,
                    fireMinute = rule.minute,
                )
            }
            // Two per subscription, not one. One would be enough to *announce*,
            // but the alarm for the following renewal is booked from this same
            // list — and with only one entry, a device holding a single
            // subscription would announce it and then have nothing left to
            // schedule, so reminders would stop until the app was next opened.
            .groupBy { it.subscriptionId }
            .flatMap { (_, forSubscription) ->
                forSubscription
                    .sortedWith(compareBy({ it.fireDate.iso }, { it.fireMinute }))
                    .take(2)
            }
            .sortedWith(compareBy({ it.fireDate.iso }, { it.fireMinute }, { it.name.lowercase() }))
    }

    /** Everything due to be announced at or before [today] + [nowMinute]. */
    fun due(reminders: List<Reminder>, today: PlainDate, nowMinute: Int): List<Reminder> =
        reminders.filter { it.fireDate < today || (it.fireDate == today && it.fireMinute <= nowMinute) }

    /**
     * The next moment worth waking up for, as (date, minute), or null when there
     * is nothing to say. The platform turns this into an alarm.
     */
    fun nextFire(reminders: List<Reminder>): Pair<PlainDate, Int>? =
        reminders.minWithOrNull(compareBy({ it.fireDate.iso }, { it.fireMinute }))
            ?.let { it.fireDate to it.fireMinute }

}
