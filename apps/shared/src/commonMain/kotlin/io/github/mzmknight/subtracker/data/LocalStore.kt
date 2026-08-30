package io.github.mzmknight.subtracker.data

import io.github.mzmknight.subtracker.core.CsvBackup
import io.github.mzmknight.subtracker.core.DashboardFigures
import io.github.mzmknight.subtracker.core.Figures
import io.github.mzmknight.subtracker.core.History
import io.github.mzmknight.subtracker.core.Lock
import io.github.mzmknight.subtracker.core.MonthHistory
import io.github.mzmknight.subtracker.core.PlainDate
import io.github.mzmknight.subtracker.core.PriceChange
import io.github.mzmknight.subtracker.core.PriceWatch
import io.github.mzmknight.subtracker.core.Reminder
import io.github.mzmknight.subtracker.core.Reminders
import io.github.mzmknight.subtracker.db.Charge_override
import io.github.mzmknight.subtracker.db.Price_period
import io.github.mzmknight.subtracker.db.SubTrackerDatabase
import io.github.mzmknight.subtracker.db.Setting
import io.github.mzmknight.subtracker.db.Subscription
import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.ClockStore
import io.github.mzmknight.subtracker.sync.ComputedCharge
import io.github.mzmknight.subtracker.sync.DeviceIdentity
import io.github.mzmknight.subtracker.sync.DeviceInfo
import io.github.mzmknight.subtracker.sync.HlcClock
import io.github.mzmknight.subtracker.sync.Merge
import io.github.mzmknight.subtracker.sync.MergeReport
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.RecordId
import io.github.mzmknight.subtracker.sync.SettingRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord
import io.github.mzmknight.subtracker.sync.SyncPayload

/**
 * The device's own copy of the data, and the only thing the UI reads from.
 *
 * This is authoritative, not a cache: a subscription created here is real
 * whether or not a server ever hears about it. Syncing — with a peer or with
 * PocketBase — is a separate concern that merges into this, never the other way
 * round.
 *
 * Every write is stamped with the hybrid logical clock and every delete is a
 * tombstone, because both are what make the merge converge.
 */
class LocalStore(
    private val database: SubTrackerDatabase,
    private val identity: DeviceInfo = DeviceIdentity,
    private val clockStore: ClockStore = DeviceIdentity,
    now: () -> Long = { nowEpochMillis() },
) : SyncedSettingsStore {

    private val queries = database.subTrackerQueries

    private val clock = HlcClock(identity.id, now).apply {
        // Restoring matters: a device whose clock jumped backwards over a
        // restart would otherwise issue timestamps that sort before its own
        // earlier edits, and lose them on the next merge.
        restore(clockStore.loadClockState())
    }

    /**
     * Guards *stamping and persisting together*, which [HlcClock]'s own lock
     * cannot: it makes each tick atomic, but not the save that must follow it.
     * Interleaved, two callers can tick A then B and persist them in the other
     * order, leaving the stored state older than a stamp already written to a
     * record — and after a restart the clock would reissue it.
     */
    private val clockLock = Lock()

    private fun stamp(): String = clockLock.withLock {
        val next = clock.tick()
        clockStore.saveClockState(next)
        next.encode()
    }

    // ------------------------------------------------------------ mapping

    private fun Subscription.toRecord() = SubscriptionRecord(
        id = id,
        updatedAt = updated_at,
        deleted = deleted != 0L,
        name = name,
        vendor = vendor,
        category = category,
        currency = currency,
        cycleUnit = cycle_unit,
        cycleCount = cycle_count.toInt(),
        anchorDate = anchor_date,
        status = status,
        endDate = end_date,
        trialEnd = trial_end,
        paymentMethod = payment_method,
        colour = colour,
        icon = icon,
        notes = notes,
        notifyMode = notify_mode,
        notifyDaysBefore = notify_days_before.toInt(),
        notifyMinute = notify_minute.toInt(),
        replaces = replaces,
    )

    private fun Price_period.toRecord() = PricePeriodRecord(
        id = id,
        updatedAt = updated_at,
        deleted = deleted != 0L,
        subscriptionId = subscription_id,
        amountMinor = amount_minor,
        effectiveFrom = effective_from,
        note = note,
    )

    private fun Charge_override.toRecord() = ChargeOverrideRecord(
        id = id,
        updatedAt = updated_at,
        deleted = deleted != 0L,
        subscriptionId = subscription_id,
        dueDate = due_date,
        kind = kind,
        amountMinor = amount_minor,
    )

    private fun Setting.toRecord() = SettingRecord(
        id = id,
        updatedAt = updated_at,
        deleted = deleted != 0L,
        value = value_,
    )

    private fun write(record: SubscriptionRecord) = queries.upsertSubscription(
        record.id, record.updatedAt, if (record.deleted) 1L else 0L, record.name,
        record.vendor, record.category, record.currency, record.cycleUnit,
        record.cycleCount.toLong(), record.anchorDate, record.status,
        record.endDate, record.trialEnd, record.paymentMethod, record.colour,
        record.icon, record.notes,
        record.notifyMode, record.notifyDaysBefore.toLong(), record.notifyMinute.toLong(),
        record.replaces,
    )

    private fun write(record: PricePeriodRecord) = queries.upsertPricePeriod(
        record.id, record.updatedAt, if (record.deleted) 1L else 0L,
        record.subscriptionId, record.amountMinor, record.effectiveFrom, record.note,
    )

    private fun write(record: ChargeOverrideRecord) = queries.upsertOverride(
        record.id, record.updatedAt, if (record.deleted) 1L else 0L,
        record.subscriptionId, record.dueDate, record.kind, record.amountMinor,
    )

    private fun write(record: SettingRecord) = queries.upsertSetting(
        record.id, record.updatedAt, if (record.deleted) 1L else 0L, record.value,
    )

    // ------------------------------------------------------------ settings

    /** Includes tombstones — for syncing, not for reading a value. */
    fun allSettings(): List<SettingRecord> =
        queries.selectAllSettings().executeAsList().map { it.toRecord() }

    /** Null when the key has never been set here, which callers read as "use the default". */
    override fun setting(key: String): String? =
        queries.selectSetting(key).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.value_

    /**
     * Stamped on write, like every other record, so the merge can tell which
     * device changed a setting last. Writing the value it already holds is
     * skipped: restamping on every read-modify-write would let a device that
     * merely *opened* the settings screen beat one that actually changed
     * something.
     */
    override fun putSetting(key: String, value: String) {
        if (setting(key) == value) return
        write(SettingRecord(id = key, updatedAt = stamp(), value = value))
    }

    // ------------------------------------------------------------ reads

    /** Includes tombstones — for syncing, not for display. */
    fun allSubscriptions(): List<SubscriptionRecord> =
        queries.selectAllSubscriptions().executeAsList().map { it.toRecord() }

    fun allPrices(): List<PricePeriodRecord> =
        queries.selectAllPricePeriods().executeAsList().map { it.toRecord() }

    fun allOverrides(): List<ChargeOverrideRecord> =
        queries.selectAllOverrides().executeAsList().map { it.toRecord() }

    fun subscriptions(): List<SubscriptionRecord> = allSubscriptions().filterNot { it.deleted }

    fun subscription(id: String): SubscriptionRecord? =
        queries.selectSubscriptionById(id).executeAsOneOrNull()?.toRecord()?.takeUnless { it.deleted }

    fun pricesFor(subscriptionId: String): List<PricePeriodRecord> =
        queries.selectLivePricePeriodsFor(subscriptionId).executeAsList().map { it.toRecord() }

    fun isEmpty(): Boolean = queries.countSubscriptions().executeAsOne() == 0L

    // ------------------------------------------------------------ derived

    fun figures(today: PlainDate, homeCurrency: String = "GBP"): DashboardFigures =
        Figures.compute(
            allSubscriptions(), allPrices(), allOverrides(), today, homeCurrency,
            rates = AppSettings.rates,
        )

    fun chargesFor(subscriptionId: String, today: PlainDate): List<ComputedCharge> {
        val subscription = subscription(subscriptionId) ?: return emptyList()
        return Figures.computeCharges(
            listOf(subscription), pricesFor(subscriptionId), allOverrides(), today,
        ).sortedBy { it.due.iso }
    }

    fun upcoming(today: PlainDate, withinDays: Int = 30): List<ComputedCharge> {
        val horizon = today.plusDays(withinDays)
        return Figures.computeCharges(allSubscriptions(), allPrices(), allOverrides(), today)
            .filter { it.due >= today && it.due <= horizon && !it.isSkipped }
            .sortedBy { it.due.iso }
    }

    /**
     * Every month with activity, broken down by subscription.
     *
     * Deliberately projected over a much wider window than the dashboard uses —
     * the dashboard answers "what is happening now", this answers "what did I
     * spend in March two years ago", and the second needs the history to exist.
     */
    fun history(today: PlainDate, homeCurrency: String = "GBP"): List<MonthHistory> =
        History.months(
            allSubscriptions(), allPrices(), allOverrides(), today, homeCurrency,
            rates = AppSettings.rates,
        )

    /** Reads the price history sideways: what has been getting more expensive. */
    fun priceChanges(today: PlainDate): List<PriceChange> =
        PriceWatch.changes(allSubscriptions(), allPrices(), today, AppSettings.rates)

    /** Renewals worth announcing, earliest first — including any already passed. */
    fun upcomingReminders(today: PlainDate): List<Reminder> =
        Reminders.upcoming(
            subscriptions = allSubscriptions(),
            prices = allPrices(),
            overrides = allOverrides(),
            today = today,
            globalEnabled = AppSettings.remindersEnabled,
            globalRule = AppSettings.reminderRule,
        )

    // ------------------------------------------------------------ writes

    /**
     * A subscription and its opening price are written in one transaction. They
     * are useless apart — a subscription with no price produces no schedule and
     * shows as costing nothing — so they must not be separable by a crash.
     */
    fun createSubscription(draft: SubscriptionRecord, openingAmountMinor: Long): SubscriptionRecord {
        val id = draft.id.ifBlank { RecordId.generate() }
        val record = draft.copy(id = id, updatedAt = stamp(), deleted = false)
        val price = PricePeriodRecord(
            id = RecordId.generate(),
            updatedAt = stamp(),
            subscriptionId = id,
            amountMinor = openingAmountMinor,
            effectiveFrom = record.anchorDate,
        )
        database.transaction {
            write(record)
            write(price)
        }
        return record
    }

    /**
     * End the current plan and start a new one, as two linked records.
     *
     * Not an edit. `cycle_unit` and `cycle_count` are columns on the
     * subscription rather than a versioned table, so the projection recomputes
     * *every* occurrence on whatever cycle is current — changing monthly to
     * yearly in place reprices a 10.99 charge from July as 89.99 and drops the
     * months between. The old plan therefore keeps its own record, frozen where
     * it stopped, and the new one starts fresh.
     *
     * The old plan ends the day *before* the new one starts. `endDate` is the
     * last date a subscription can still bill on, so ending it on the switch
     * date itself would bill both plans that day.
     *
     * One transaction: a half-applied switch leaves either two live plans
     * charging at once or none at all, and both look like the app losing data.
     */
    fun switchPlan(
        current: SubscriptionRecord,
        startingOn: PlainDate,
        cycleUnit: String,
        cycleCount: Int,
        amountMinor: Long,
    ): SubscriptionRecord {
        val ended = current.copy(
            status = "cancelled",
            endDate = startingOn.plusDays(-1).iso,
            updatedAt = stamp(),
        )
        val newId = RecordId.generate()
        // Carries the name, logo, colour and reminder rule over: to the user
        // this is the same subscription on different terms, and making them
        // reassemble its appearance would make the feature not worth using.
        val replacement = current.copy(
            id = newId,
            updatedAt = stamp(),
            status = "active",
            endDate = null,
            trialEnd = null,
            anchorDate = startingOn.iso,
            cycleUnit = cycleUnit,
            cycleCount = cycleCount,
            replaces = current.id,
        )
        val price = PricePeriodRecord(
            id = RecordId.generate(),
            updatedAt = stamp(),
            subscriptionId = newId,
            amountMinor = amountMinor,
            effectiveFrom = startingOn.iso,
        )
        database.transaction {
            write(ended)
            write(replacement)
            write(price)
        }
        return replacement
    }

    /**
     * The chain of plans behind a subscription, oldest first, excluding itself.
     * Follows `replaces` backwards; guarded against a cycle because a corrupted
     * or maliciously edited link would otherwise loop forever.
     */
    fun planHistory(id: String): List<SubscriptionRecord> {
        val out = mutableListOf<SubscriptionRecord>()
        val seen = mutableSetOf(id)
        var previous = subscription(id)?.replaces.orEmpty()
        while (previous.isNotBlank() && seen.add(previous)) {
            val record = subscription(previous) ?: break
            out += record
            previous = record.replaces
        }
        return out.reversed()
    }

    fun updateSubscription(record: SubscriptionRecord): SubscriptionRecord {
        val stamped = record.copy(updatedAt = stamp())
        write(stamped)
        return stamped
    }

    /** Tombstone, never a row removal — see [io.github.mzmknight.subtracker.sync.Syncable]. */
    fun deleteSubscription(id: String) {
        val existing = queries.selectSubscriptionById(id).executeAsOneOrNull()?.toRecord() ?: return
        write(existing.copy(deleted = true, updatedAt = stamp()))
    }

    /**
     * Corrects the price in force, in place.
     *
     * Distinct from [addPrice], and the difference matters: a price *rise* is a
     * new period, so past months keep what they actually cost. A *mistyped*
     * price is not an event that happened — it is a wrong number, and adding a
     * period for it would invent a price change that never occurred and put it
     * in Price Watch.
     */
    fun correctCurrentPrice(subscriptionId: String, amountMinor: Long): PricePeriodRecord? {
        val latest = pricesFor(subscriptionId).maxByOrNull { it.effectiveFrom } ?: return null
        if (latest.amountMinor == amountMinor) return latest
        val corrected = latest.copy(amountMinor = amountMinor, updatedAt = stamp())
        write(corrected)
        return corrected
    }

    fun addPrice(subscriptionId: String, amountMinor: Long, effectiveFrom: String): PricePeriodRecord {
        val record = PricePeriodRecord(
            id = RecordId.generate(),
            updatedAt = stamp(),
            subscriptionId = subscriptionId,
            amountMinor = amountMinor,
            effectiveFrom = effectiveFrom,
        )
        write(record)
        return record
    }

    /**
     * The override id is derived from (subscription, dueDate), so setting one
     * twice — or on two devices — updates a single record rather than creating
     * rivals for the same charge.
     */
    fun setOverride(
        subscriptionId: String,
        dueDate: String,
        kind: String,
        amountMinor: Long? = null,
    ): ChargeOverrideRecord {
        val record = ChargeOverrideRecord(
            id = ChargeOverrideRecord.idFor(subscriptionId, dueDate),
            updatedAt = stamp(),
            subscriptionId = subscriptionId,
            dueDate = dueDate,
            kind = kind,
            amountMinor = amountMinor,
        )
        write(record)
        return record
    }

    fun clearOverride(subscriptionId: String, dueDate: String) {
        val id = ChargeOverrideRecord.idFor(subscriptionId, dueDate)
        val existing = queries.selectOverrideById(id).executeAsOneOrNull()?.toRecord() ?: return
        write(existing.copy(deleted = true, updatedAt = stamp()))
    }

    // ------------------------------------------------------------ sync

    fun exportAll(): SyncPayload = SyncPayload(
        deviceId = identity.id,
        deviceName = identity.name,
        subscriptions = allSubscriptions(),
        prices = allPrices(),
        overrides = allOverrides(),
        settings = allSettings(),
    )

    /**
     * Merge a peer's payload in. Everything is applied in one transaction so a
     * failure part-way cannot leave half a peer's history behind.
     *
     * The local clock is advanced past the highest timestamp seen, or a device
     * with a slow clock would lose every subsequent merge.
     */
    fun applyIncoming(payload: SyncPayload): MergeReport {
        val subscriptions = Merge.merge(allSubscriptions(), payload.subscriptions)
        val prices = Merge.merge(allPrices(), payload.prices)
        val overrides = Merge.merge(allOverrides(), payload.overrides)
        val settings = Merge.merge(allSettings(), payload.settings)

        database.transaction {
            subscriptions.incoming.forEach { write(it) }
            prices.incoming.forEach { write(it) }
            overrides.incoming.forEach { write(it) }
            settings.incoming.forEach { write(it) }
        }

        val highest = Merge.highestTimestamp(
            payload.subscriptions + payload.prices + payload.overrides + payload.settings,
        )
        if (highest != null) {
            // Same pairing as stamp(): observe and save are one step or the
            // stored state can end up behind what has already been issued.
            clockLock.withLock { clockStore.saveClockState(clock.observe(highest)) }
        }

        return MergeReport(
            subscriptionsChanged = subscriptions.incoming.size,
            pricesChanged = prices.incoming.size,
            overridesChanged = overrides.incoming.size,
            settingsChanged = settings.incoming.size,
            sentCount = subscriptions.outgoing.size + prices.outgoing.size +
                overrides.outgoing.size + settings.outgoing.size,
        )
    }

    /** What this device holds that the peer's payload is missing or has stale. */
    fun changesFor(payload: SyncPayload): SyncPayload = SyncPayload(
        deviceId = DeviceIdentity.id,
        deviceName = DeviceIdentity.name,
        subscriptions = Merge.merge(allSubscriptions(), payload.subscriptions).outgoing,
        prices = Merge.merge(allPrices(), payload.prices).outgoing,
        overrides = Merge.merge(allOverrides(), payload.overrides).outgoing,
        settings = Merge.merge(allSettings(), payload.settings).outgoing,
    )

    // ------------------------------------------------------------ backup

    fun exportCsv(): String =
        CsvBackup.export(allSubscriptions(), allPrices(), allOverrides(), allSettings())

    data class ImportReport(
        val merged: MergeReport,
        val readFromFile: Int,
        val problems: List<String>,
    )

    /**
     * Restore from a CSV, through the same merge a peer's data goes through.
     *
     * Merging rather than replacing is the point. Import is last-write-wins on
     * the hybrid logical clock, so an old backup cannot silently undo edits made
     * since it was taken, and importing the same file twice changes nothing the
     * second time. It also means a backup can be imported on a device that has
     * never seen any of it, which is the case that actually matters.
     */
    fun importCsv(text: String): ImportReport {
        val parsed = CsvBackup.parse(text)
        val existing = allSubscriptions().associateBy { it.id }

        // Logos are not in the file. Without this the imported record — which
        // wins on timestamp — would overwrite the local one with a blank icon,
        // so restoring a backup would silently wipe every logo the user had.
        val subscriptions = parsed.subscriptions.map { imported ->
            val localIcon = existing[imported.id]?.icon.orEmpty()
            if (imported.icon.isBlank() && localIcon.isNotBlank()) {
                imported.copy(icon = localIcon)
            } else {
                imported
            }
        }

        return ImportReport(
            merged = applyIncoming(
                SyncPayload(
                    deviceId = identity.id,
                    deviceName = identity.name,
                    subscriptions = subscriptions,
                    prices = parsed.prices,
                    overrides = parsed.overrides,
                    settings = parsed.settings,
                )
            ),
            readFromFile = parsed.total,
            problems = parsed.problems,
        )
    }

    /**
     * Force the file's contents to win, rather than merging on timestamps.
     *
     * Import alone cannot undo a mistake. Deleting a subscription writes a
     * tombstone stamped *now*, which is newer than anything in a backup taken
     * before it — so a plain merge correctly refuses to bring it back, and the
     * one case people most want a backup for is the one it cannot serve.
     *
     * Every record is restamped with a fresh clock reading so it beats whatever
     * is here, which also means the restored state propagates to paired devices
     * as an ordinary sync instead of being reverted by the first one that still
     * remembers the delete.
     *
     * Records *not* in the file are left alone. Removing them would make this a
     * destructive rollback of everything added since, which is a different and
     * far more dangerous operation than the one the button offers.
     */
    fun restoreCsv(text: String): ImportReport {
        val parsed = CsvBackup.parse(text)
        val existing = allSubscriptions().associateBy { it.id }

        val subscriptions = parsed.subscriptions.map { imported ->
            val localIcon = existing[imported.id]?.icon.orEmpty()
            val withIcon =
                if (imported.icon.isBlank() && localIcon.isNotBlank()) imported.copy(icon = localIcon)
                else imported
            withIcon.copy(updatedAt = stamp())
        }
        val prices = parsed.prices.map { it.copy(updatedAt = stamp()) }
        val overrides = parsed.overrides.map { it.copy(updatedAt = stamp()) }
        val settings = parsed.settings.map { it.copy(updatedAt = stamp()) }

        database.transaction {
            subscriptions.forEach { write(it) }
            prices.forEach { write(it) }
            overrides.forEach { write(it) }
            settings.forEach { write(it) }
        }

        return ImportReport(
            merged = MergeReport(
                subscriptionsChanged = subscriptions.size,
                pricesChanged = prices.size,
                overridesChanged = overrides.size,
                settingsChanged = settings.size,
                sentCount = 0,
            ),
            readFromFile = parsed.total,
            problems = parsed.problems,
        )
    }

    fun wipe() {
        queries.clearAll()
    }
}
