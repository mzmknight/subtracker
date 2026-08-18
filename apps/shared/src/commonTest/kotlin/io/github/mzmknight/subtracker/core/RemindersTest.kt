package io.github.mzmknight.subtracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * Reminder scheduling, where every mistake is silent: a reminder that fires a
 * day late, twice, or not at all all look identical from inside the app.
 */
class RemindersTest {

    private val today = PlainDate.parse("2026-08-10")
    private val nine = 9 * 60

    private fun sub(
        id: String,
        name: String,
        anchor: String = "2026-08-15",
        status: String = "active",
        mode: String = Reminders.MODE_INHERIT,
        days: Int = 3,
        minute: Int = nine,
    ) = SubscriptionRecord(
        id = id, updatedAt = "1", name = name, anchorDate = anchor,
        cycleUnit = "month", cycleCount = 1, status = status, currency = "GBP",
        notifyMode = mode, notifyDaysBefore = days, notifyMinute = minute,
    )

    private fun price(id: String, subId: String, minor: Long, from: String = "2026-08-15") =
        PricePeriodRecord(id = id, updatedAt = "1", subscriptionId = subId, amountMinor = minor, effectiveFrom = from)

    private fun upcoming(
        subs: List<SubscriptionRecord>,
        prices: List<PricePeriodRecord>,
        overrides: List<ChargeOverrideRecord> = emptyList(),
        enabled: Boolean = true,
        rule: ReminderRule = ReminderRule(3, nine),
    ) = Reminders.upcoming(subs, prices, overrides, today, enabled, rule)

    private val netflix = sub("s1", "Netflix", anchor = "2026-08-15")
    private val netflixPrice = price("p1", "s1", 1299)

    @Test
    fun aRenewalIsAnnouncedTheConfiguredNumberOfDaysBefore() {
        val reminders = upcoming(listOf(netflix), listOf(netflixPrice))
        val next = reminders.first()

        assertEquals("2026-08-15", next.dueDate.iso)
        assertEquals("2026-08-12", next.fireDate.iso, "three days before the 15th")
        assertEquals(nine, next.fireMinute)
        assertEquals(1299, next.amountMinor)
    }

    @Test
    fun theMessageSaysWhatItIsAndWhatItCosts() {
        val next = upcoming(listOf(netflix), listOf(netflixPrice)).first()
        assertEquals("Netflix", next.title())
        assertEquals("Renews in 3 days, 15 Aug · £12.99", next.body())
    }

    @Test
    fun onTheDayAndTomorrowReadAsWords() {
        val sameDay = upcoming(
            listOf(sub("s1", "Netflix", mode = Reminders.MODE_CUSTOM, days = 0)),
            listOf(netflixPrice),
        ).first()
        assertEquals("Renews today · £12.99", sameDay.body())

        val dayBefore = upcoming(
            listOf(sub("s1", "Netflix", mode = Reminders.MODE_CUSTOM, days = 1)),
            listOf(netflixPrice),
        ).first()
        assertEquals("Renews tomorrow · £12.99", dayBefore.body())
    }

    // ------------------------------------------------------------ the rules

    @Test
    fun aSubscriptionWithNoRuleFollowsTheGlobalOne() {
        val rule = Reminders.ruleFor(netflix, globalEnabled = true, globalRule = ReminderRule(7, 8 * 60))
        assertEquals(ReminderRule(7, 8 * 60), rule)
    }

    @Test
    fun aCustomRuleBeatsTheGlobalOne() {
        val custom = sub("s1", "Netflix", mode = Reminders.MODE_CUSTOM, days = 1, minute = 20 * 60)
        val rule = Reminders.ruleFor(custom, globalEnabled = true, globalRule = ReminderRule(7, 8 * 60))
        assertEquals(ReminderRule(1, 20 * 60), rule)
    }

    @Test
    fun aSubscriptionSetToNeverIsSilentEvenThoughGlobalIsOn() {
        val off = sub("s1", "Netflix", mode = Reminders.MODE_OFF)
        assertNull(Reminders.ruleFor(off, globalEnabled = true, globalRule = ReminderRule(3, nine)))
        assertTrue(upcoming(listOf(off), listOf(netflixPrice)).isEmpty())
    }

    @Test
    fun theGlobalSwitchBeatsEveryPerSubscriptionRule() {
        // Otherwise "off" is a lie: a subscription with its own settings would
        // keep notifying after the user turned the whole thing off.
        val custom = sub("s1", "Netflix", mode = Reminders.MODE_CUSTOM, days = 1)
        assertNull(Reminders.ruleFor(custom, globalEnabled = false, globalRule = ReminderRule(3, nine)))
        assertTrue(upcoming(listOf(custom), listOf(netflixPrice), enabled = false).isEmpty())
    }

    @Test
    fun cancelledAndPausedSubscriptionsAreNotAnnounced() {
        assertTrue(upcoming(listOf(sub("s1", "X", status = "cancelled")), listOf(netflixPrice)).isEmpty())
        assertTrue(upcoming(listOf(sub("s1", "X", status = "paused")), listOf(netflixPrice)).isEmpty())
    }

    @Test
    fun aSkippedChargeIsNotAnnounced() {
        // The point of skipping it was that it is not happening.
        val skip = ChargeOverrideRecord(
            id = ChargeOverrideRecord.idFor("s1", "2026-08-15"),
            updatedAt = "2", subscriptionId = "s1", dueDate = "2026-08-15",
            kind = ChargeOverrideRecord.KIND_SKIPPED,
        )
        val reminders = upcoming(listOf(netflix), listOf(netflixPrice), overrides = listOf(skip))
        assertTrue(reminders.none { it.dueDate.iso == "2026-08-15" })
    }

    @Test
    fun anAdjustedAmountIsTheOneAnnounced() {
        val corrected = ChargeOverrideRecord(
            id = ChargeOverrideRecord.idFor("s1", "2026-08-15"),
            updatedAt = "2", subscriptionId = "s1", dueDate = "2026-08-15",
            kind = ChargeOverrideRecord.KIND_AMOUNT, amountMinor = 500,
        )
        val next = upcoming(listOf(netflix), listOf(netflixPrice), overrides = listOf(corrected)).first()
        assertEquals(500, next.amountMinor)
        assertTrue(next.body().contains("£5.00"))
    }

    // ------------------------------------------------------ firing and repeat

    @Test
    fun aReminderIsDueOnceItsMomentHasPassed() {
        val reminders = upcoming(listOf(netflix), listOf(netflixPrice))

        // 11 Aug: the 12th has not arrived.
        assertTrue(Reminders.due(reminders, PlainDate.parse("2026-08-11"), 23 * 60).isEmpty())
        // 12 Aug at 08:59, one minute early.
        assertTrue(Reminders.due(reminders, PlainDate.parse("2026-08-12"), nine - 1).isEmpty())
        // 12 Aug at 09:00 exactly.
        assertEquals(1, Reminders.due(reminders, PlainDate.parse("2026-08-12"), nine).size)
        // And still due if the device was asleep and nothing ran until the 13th.
        assertEquals(1, Reminders.due(reminders, PlainDate.parse("2026-08-13"), 0).size)
    }

    @Test
    fun aWindowThatAlreadyOpenedIsStillWorthSayingOnce() {
        // Added today, renews in two days, rule says seven days before. The
        // moment has passed but the renewal has not, and staying silent about a
        // charge two days away would be the wrong call.
        val late = sub("s1", "Netflix", anchor = "2026-08-12", mode = Reminders.MODE_CUSTOM, days = 7)
        val reminders = upcoming(listOf(late), listOf(price("p1", "s1", 1299, "2026-08-12")))

        assertTrue(reminders.isNotEmpty())
        assertEquals(1, Reminders.due(reminders, today, nine + 1).size)
    }

    @Test
    fun eachSubscriptionOffersTwoSoTheChainNeverRunsDry() {
        // The alarm for the following renewal is booked from this same list. With
        // only one entry per subscription, a device holding a single subscription
        // would announce it and then have nothing left to schedule.
        val reminders = upcoming(listOf(netflix), listOf(netflixPrice))
        assertEquals(2, reminders.size)
        assertEquals(listOf("2026-08-12", "2026-09-12"), reminders.map { it.fireDate.iso })
    }

    @Test
    fun theNextFireIsTheEarliestAcrossEverything() {
        val spotify = sub("s2", "Spotify", anchor = "2026-08-11")
        val reminders = upcoming(
            listOf(netflix, spotify),
            listOf(netflixPrice, price("p2", "s2", 1199, "2026-08-11")),
        )

        // Spotify renews on the 11th, so its reminder was due on the 8th.
        assertEquals(PlainDate.parse("2026-08-08") to nine, Reminders.nextFire(reminders))
    }

    @Test
    fun eachReminderHasAStableKeyPerCharge() {
        // What stops the same renewal being announced twice after a reboot.
        val first = upcoming(listOf(netflix), listOf(netflixPrice)).first()
        val again = upcoming(listOf(netflix), listOf(netflixPrice)).first()

        assertEquals(first.key, again.key)
        assertEquals("s1@2026-08-15", first.key)
        // A different charge of the same subscription is a different key.
        assertTrue(upcoming(listOf(netflix), listOf(netflixPrice)).map { it.key }.toSet().size == 2)
    }

    @Test
    fun nothingIsProducedWhenRemindersAreOff() {
        assertTrue(upcoming(listOf(netflix), listOf(netflixPrice), enabled = false).isEmpty())
        assertNull(Reminders.nextFire(emptyList()))
    }

    @Test
    fun ruleDescriptionsReadAsEnglish() {
        assertEquals("On the day, at 09:00", ReminderRule(0, nine).describe())
        assertEquals("1 day before, at 08:30", ReminderRule(1, 8 * 60 + 30).describe())
        assertEquals("7 days before, at 20:00", ReminderRule(7, 20 * 60).describe())
    }

    @Test
    fun rulesAreClampedToSomethingSane() {
        assertEquals(ReminderRule.MAX_DAYS_BEFORE, ReminderRule.of(999, nine).daysBefore)
        assertEquals(0, ReminderRule.of(-5, nine).daysBefore)
        assertEquals(0, ReminderRule.of(3, -1).minute)
        assertEquals(24 * 60 - 1, ReminderRule.of(3, 99_999).minute)
    }
}
