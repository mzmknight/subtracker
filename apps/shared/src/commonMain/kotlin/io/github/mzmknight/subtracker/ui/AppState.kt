package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.mzmknight.subtracker.core.DashboardFigures
import io.github.mzmknight.subtracker.core.Figures
import io.github.mzmknight.subtracker.core.MonthHistory
import io.github.mzmknight.subtracker.core.Money
import io.github.mzmknight.subtracker.core.PlainDate
import io.github.mzmknight.subtracker.core.PriceChange
import io.github.mzmknight.subtracker.core.Rates
import io.github.mzmknight.subtracker.core.Reminder
import io.github.mzmknight.subtracker.core.ReminderRule
import io.github.mzmknight.subtracker.core.Reminders
import io.github.mzmknight.subtracker.data.AppSettings
import io.github.mzmknight.subtracker.data.KnownPeer
import io.github.mzmknight.subtracker.data.LocalStore
import io.github.mzmknight.subtracker.data.LogoFetcher
import io.github.mzmknight.subtracker.data.Notifications
import io.github.mzmknight.subtracker.data.PeerBook
import io.github.mzmknight.subtracker.data.RateFetcher
import io.github.mzmknight.subtracker.data.ReminderScheduler
import io.github.mzmknight.subtracker.data.nowEpochMillis
import io.github.mzmknight.subtracker.db.Database
import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.ComputedCharge
import io.github.mzmknight.subtracker.sync.DeviceIdentity
import io.github.mzmknight.subtracker.sync.DiscoveredPeer
import io.github.mzmknight.subtracker.sync.Discovery
import io.github.mzmknight.subtracker.sync.PairingLink
import io.github.mzmknight.subtracker.sync.PeerClient
import io.github.mzmknight.subtracker.sync.PeerDiscovery
import io.github.mzmknight.subtracker.sync.PeerServer
import io.github.mzmknight.subtracker.sync.SyncThrottle
import io.github.mzmknight.subtracker.sync.localAddresses
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.ServerClient
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

enum class SortOrder(val label: String) {
    Cost("Cost"),
    Name("Name"),
    NextBill("Next bill"),
    Cycle("Cycle"),
}

/** Shortest cycle first, so daily and weekly costs surface above yearly ones. */
private val CYCLE_ORDER = listOf("day", "week", "month", "year")

sealed interface Screen {
    data object Dashboard : Screen
    data object Subscriptions : Screen
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?) : Screen
    data object History : Screen
    data object Devices : Screen
}

/**
 * Whether "check the other devices for changes" belongs on this screen.
 *
 * The three read-only views of your own data, and nothing else: an edit form
 * must not have a sync land in the middle of it, and Devices already offers
 * syncing as a labelled control rather than a gesture.
 */
val Screen.isRefreshable: Boolean
    get() = this is Screen.Dashboard || this is Screen.Subscriptions || this is Screen.History

/** Everything the detail screen needs, all derived locally. */
data class SubscriptionDetail(
    val subscription: SubscriptionRecord,
    val prices: List<PricePeriodRecord>,
    val charges: List<ComputedCharge>,
    /** Earlier plans this one took over from, oldest first. Usually empty. */
    val previousPlans: List<PlanPeriod> = emptyList(),
) {
    fun currentPrice(asOf: PlainDate): PricePeriodRecord? =
        prices.filter { (it.from ?: return@filter false) <= asOf }.maxByOrNull { it.effectiveFrom }
            ?: prices.minByOrNull { it.effectiveFrom }

    fun paidToDate(today: PlainDate): Long =
        charges.filter { it.isSettled(today) }.sumOf { it.effectiveAmountMinor }

    fun nextCharge(today: PlainDate): ComputedCharge? =
        charges.filter { !it.isSettled(today) && !it.isSkipped }.minByOrNull { it.due.iso }

    /**
     * Everything paid on this subscription including the plans it replaced —
     * the figure the user means by "what has F1TV cost me", which stops being
     * the same as [paidToDate] the moment a plan is switched.
     */
    fun paidAcrossAllPlans(today: PlainDate): Long =
        paidToDate(today) + previousPlans.sumOf { it.paid }
}

/** One earlier plan, summarised for the detail screen. */
data class PlanPeriod(
    val subscription: SubscriptionRecord,
    val paid: Long,
    val charges: Int,
    val from: PlainDate?,
    val until: PlainDate?,
) {
    val label: String get() = subscription.cycleLabel.replaceFirstChar { it.uppercase() }
}

/**
 * Single state holder, now local-first.
 *
 * There is no "offline mode" any more, because there is no online mode to fall
 * back from: every read and every write goes to the device's own database, and
 * the projection runs here. Syncing — with a peer or with the server — merges
 * into that, and is never required for the app to work.
 */
class AppState(private val scope: CoroutineScope) {

    private val store: LocalStore = LocalStore(Database.get())

    var screen by mutableStateOf<Screen>(Screen.Dashboard)
        private set

    var figures by mutableStateOf<DashboardFigures?>(null)
        private set
    var subscriptions by mutableStateOf<List<SubscriptionRecord>>(emptyList())
        private set
    var upcoming by mutableStateOf<List<ComputedCharge>>(emptyList())
        private set
    var detail by mutableStateOf<SubscriptionDetail?>(null)
        private set
    var history by mutableStateOf<List<MonthHistory>>(emptyList())
        private set
    var priceChanges by mutableStateOf<List<PriceChange>>(emptyList())
        private set
    private var runRates by mutableStateOf<Map<String, Long>>(emptyMap())

    var sortOrder by mutableStateOf(SortOrder.Cost)
        private set

    fun sortBy(order: SortOrder) {
        sortOrder = order
    }

    /**
     * The subscription list in the user's chosen order.
     *
     * Ended subscriptions always sink to the bottom whatever the sort — they are
     * history, and letting a cancelled service head the list because it happened
     * to be the most expensive is actively misleading.
     */
    fun orderedSubscriptions(): List<SubscriptionRecord> {
        val comparator = when (sortOrder) {
            SortOrder.Name -> compareBy<SubscriptionRecord> { it.name.lowercase() }
            SortOrder.Cost -> compareByDescending { runRates[it.id] ?: -1 }
            SortOrder.NextBill -> compareBy { nextDueDate(it.id) ?: "9999-99-99" }
            SortOrder.Cycle -> compareBy<SubscriptionRecord> { CYCLE_ORDER.indexOf(it.cycleUnit) }
                .thenBy { it.cycleCount }
        }
        // A superseded plan is hidden rather than deleted. Its charges are real
        // and still count in History and in the subscription's own total, but as
        // a *row* it is the same subscription on old terms — showing both makes
        // one F1TV look like two.
        val superseded = subscriptions.mapNotNull { it.replaces.ifBlank { null } }.toSet()

        return subscriptions
            .filterNot { it.id in superseded }
            .sortedWith(
                compareBy<SubscriptionRecord> { !it.isLive }
                    .then(comparator)
                    .thenBy { it.name.lowercase() },
            )
    }

    private fun nextDueDate(subscriptionId: String): String? =
        upcoming.firstOrNull { it.subscriptionId == subscriptionId }?.due?.iso

    /** Top spenders by normalised monthly cost. */
    fun topSpenders(limit: Int = 5): List<Pair<SubscriptionRecord, Long>> =
        subscriptions.filter { it.isLive }
            .mapNotNull { subscription -> runRates[subscription.id]?.let { subscription to it } }
            .sortedByDescending { it.second }
            .take(limit)

    /**
     * Which month is open in the History tab. Held here rather than in the
     * screen so that opening a subscription from inside a month and coming back
     * returns you to the month you were reading, not to the top of the list.
     */
    var expandedMonth by mutableStateOf<String?>(null)
        private set

    fun toggleMonth(month: String) {
        expandedMonth = if (expandedMonth == month) null else month
    }

    /**
     * Which years are open in the History tab.
     *
     * Null means "not chosen yet", which the screen reads as the most recent
     * year only. Storing the default as an actual set here instead would need
     * the history to have loaded first, and the two race.
     */
    var expandedYears by mutableStateOf<Set<String>?>(null)
        private set

    fun toggleYear(year: String, currentlyExpanded: Set<String>) {
        expandedYears = if (year in currentlyExpanded) {
            currentlyExpanded - year
        } else {
            currentlyExpanded + year
        }
        // A month left open inside a year that is now folded away would spring
        // back open the next time the year is expanded, which reads as a bug.
        if (expandedMonth?.startsWith(year) == true && year !in expandedYears.orEmpty()) {
            expandedMonth = null
        }
    }

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    var lastSyncedAt by mutableStateOf(0L)
        private set

    /**
     * The device's own date. Previously this came from the server, to stop the
     * two disagreeing about what counted as settled — with no server in the
     * picture the device decides for itself.
     *
     * Read at construction rather than pushed in afterwards. Doing it the other
     * way round shipped a bug: the initial load raced with the later
     * setToday(), so whichever coroutine finished last won and the UI could
     * settle on the placeholder date.
     *
     * Observable, because a plain `var` is invisible to Compose — the header
     * would keep showing whatever it first read.
     */
    private var _today: PlainDate by mutableStateOf(systemToday())

    val today: PlainDate get() = _today

    val deviceName: String get() = DeviceIdentity.name

    init {
        // Before the first reload, which reads the home currency: the synced
        // copy is the authoritative one, and reading the device-local fallback
        // first would show one currency and then quietly swap it.
        AppSettings.useSyncedStore(store)
        reload()
    }

    /** For a date rollover while the app is open, and for tests. */
    fun setToday(date: PlainDate) {
        if (date == _today) return
        _today = date
        reload()
    }

    fun go(target: Screen) {
        error = null
        screen = target
        if (target is Screen.Detail) loadDetail(target.id)
    }

    fun dismissError() {
        error = null
    }

    fun dismissNotice() {
        notice = null
    }

    /**
     * @param onSaved runs only after the write and reload succeed — this is
     *   where navigation belongs. Leaving the user on a filled-in form after a
     *   successful save reads as failure, and invites adding the same thing twice.
     */
    private fun mutate(message: String? = null, onSaved: (() -> Unit)? = null, block: () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                withContext(Dispatchers.Default) { block() }
                reload()
                // Every local write goes through here, so this is the one place
                // the other devices need telling from.
                schedulePush()
                message?.let { notice = it }
                onSaved?.invoke()
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
            } finally {
                busy = false
            }
        }
    }

    /** Recompute everything from the local database. Cheap: it is all in SQLite. */
    fun reload() {
        scope.launch {
            val snapshot = withContext(Dispatchers.Default) {
                val subs = store.subscriptions()
                Snapshot(
                    subscriptions = subs,
                    figures = store.figures(_today, AppSettings.homeCurrency),
                    upcoming = store.upcoming(_today, 30),
                    runRates = Figures.runRate(subs, store.allPrices(), _today),
                    history = store.history(_today, AppSettings.homeCurrency),
                    priceChanges = store.priceChanges(_today),
                )
            }
            subscriptions = snapshot.subscriptions
            figures = snapshot.figures
            upcoming = snapshot.upcoming
            runRates = snapshot.runRates
            history = snapshot.history
            priceChanges = snapshot.priceChanges
            (screen as? Screen.Detail)?.let { loadDetail(it.id) }
        }
    }

    private data class Snapshot(
        val subscriptions: List<SubscriptionRecord>,
        val figures: DashboardFigures,
        val upcoming: List<ComputedCharge>,
        val runRates: Map<String, Long>,
        val history: List<MonthHistory>,
        val priceChanges: List<PriceChange>,
    )

    fun loadDetail(id: String) {
        scope.launch {
            val loaded = withContext(Dispatchers.Default) {
                val subscription = store.subscription(id) ?: return@withContext null
                SubscriptionDetail(
                    subscription = subscription,
                    prices = store.pricesFor(id),
                    charges = store.chargesFor(id, _today),
                    previousPlans = store.planHistory(id).map { plan ->
                        val charges = store.chargesFor(plan.id, _today)
                        val settled = charges.filter { it.isSettled(_today) }
                        PlanPeriod(
                            subscription = plan,
                            paid = settled.sumOf { it.effectiveAmountMinor },
                            charges = settled.size,
                            from = settled.minByOrNull { it.due.iso }?.due,
                            until = settled.maxByOrNull { it.due.iso }?.due,
                        )
                    },
                )
            }
            if (loaded == null) {
                error = "That subscription no longer exists."
                screen = Screen.Subscriptions
            } else {
                detail = loaded
            }
        }
    }

    fun monthlyCostOf(subscriptionId: String): Long? = runRates[subscriptionId]

    // ------------------------------------------------------------- writes
    // All of these work with no network. Nothing here can fail for being offline.

    fun saveNew(draft: SubscriptionRecord, amountMinor: Long) =
        mutate(
            message = "${draft.name} added.",
            onSaved = {
                screen = Screen.Subscriptions
                // Adding something in a new currency is exactly the moment its
                // rate is needed. Without this the totals stay wrong until the
                // next launch or sync, and the Devices card sits there saying
                // "not fetched yet" about a currency now plainly in use.
                refreshRates()
            },
        ) {
            store.createSubscription(draft, amountMinor)
        }

    /**
     * @param correctedPriceMinor when the price on the form was edited. Rewrites
     *   the period in force rather than adding one — see
     *   [LocalStore.correctCurrentPrice] for why those are different operations.
     */
    fun saveExisting(record: SubscriptionRecord, correctedPriceMinor: Long? = null) =
        mutate(
            message = "${record.name} updated.",
            onSaved = {
                go(Screen.Detail(record.id))
                // Changing an existing subscription's currency counts too.
                refreshRates()
            },
        ) {
            store.updateSubscription(record)
            correctedPriceMinor?.let { store.correctCurrentPrice(record.id, it) }
        }

    /**
     * @param onSwitched receives the new subscription's id so the caller can
     *   follow it — staying on the old record would show a cancelled plan and
     *   read as though the switch had failed.
     */
    fun switchPlan(
        current: SubscriptionRecord,
        startingOn: PlainDate,
        cycleUnit: String,
        cycleCount: Int,
        amountMinor: Long,
        onSwitched: (String) -> Unit = {},
    ) {
        var newId = ""
        mutate(
            message = "${current.name} switched to ${Money.describeCycle(cycleUnit, cycleCount)}.",
            onSaved = { if (newId.isNotBlank()) { go(Screen.Detail(newId)); onSwitched(newId) } },
        ) {
            newId = store.switchPlan(
                current, startingOn, cycleUnit, cycleCount, amountMinor,
            ).id
        }
    }

    fun delete(record: SubscriptionRecord) {
        screen = Screen.Subscriptions
        mutate("${record.name} deleted.") { store.deleteSubscription(record.id) }
    }

    fun addPriceChange(subscriptionId: String, amountMinor: Long, effectiveFrom: String) =
        mutate("Price change recorded. Future charges repriced.") {
            store.addPrice(subscriptionId, amountMinor, effectiveFrom)
        }

    fun skipCharge(charge: ComputedCharge) =
        mutate("Charge skipped.") {
            store.setOverride(
                charge.subscriptionId, charge.due.iso, ChargeOverrideRecord.KIND_SKIPPED,
            )
        }

    fun correctCharge(charge: ComputedCharge, amountMinor: Long) =
        mutate("Amount corrected.") {
            store.setOverride(
                charge.subscriptionId, charge.due.iso, ChargeOverrideRecord.KIND_AMOUNT, amountMinor,
            )
        }

    fun clearChargeOverride(charge: ComputedCharge) =
        mutate("Back to the scheduled amount.") {
            store.clearOverride(charge.subscriptionId, charge.due.iso)
        }

    // ------------------------------------------------------------- backup

    /** The file's contents, built on a background thread. */
    fun buildBackup(onReady: (fileName: String, content: String) -> Unit) {
        scope.launch {
            busy = true
            try {
                val content = withContext(Dispatchers.Default) { store.exportCsv() }
                onReady(io.github.mzmknight.subtracker.core.CsvBackup.fileNameFor(_today), content)
            } catch (e: Exception) {
                error = e.message ?: "Couldn't build the backup."
            } finally {
                busy = false
            }
        }
    }

    fun backupSaved(result: Result<Unit>) {
        result
            .onSuccess { notice = "Backup saved." }
            .onFailure { error = it.message ?: "Couldn't save the backup." }
    }

    fun importBackup(result: Result<String>) {
        val text = result.getOrElse {
            error = it.message ?: "Couldn't read that file."
            return
        }
        scope.launch {
            busy = true
            error = null
            try {
                val report = withContext(Dispatchers.Default) { store.importCsv(text) }
                reload()
                schedulePush()
                val changed = report.merged.subscriptionsChanged +
                    report.merged.pricesChanged + report.merged.overridesChanged
                notice = buildString {
                    append("Read ${report.readFromFile} record${if (report.readFromFile == 1) "" else "s"}, ")
                    // "Changed nothing" is a real and common outcome — importing a
                    // backup you already restored, or one older than what is here.
                    append(if (changed == 0) "nothing was new." else "applied $changed.")
                    if (report.problems.isNotEmpty()) {
                        append(" ${report.problems.size} row${if (report.problems.size == 1) "" else "s"} skipped.")
                    }
                }
                importProblems = report.problems
                findMissingLogos()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't import that file."
            } finally {
                busy = false
            }
        }
    }

    /** Rows the import could not read, kept so they can be shown and fixed. */
    var importProblems by mutableStateOf<List<String>>(emptyList())
        private set

    data class LogoSearch(val done: Int, val total: Int, val found: Int)

    /** Non-null while a bulk logo lookup is running. */
    var logoSearch by mutableStateOf<LogoSearch?>(null)
        private set

    val subscriptionsMissingLogos: Int
        get() = subscriptions.count { it.icon.isBlank() }

    /**
     * Fetch logos for everything that has none.
     *
     * Runs itself after an import, because the file deliberately does not carry
     * logos — so a restored device would otherwise show a wall of letters and
     * the only way back was opening every subscription in turn.
     *
     * Each hit is saved as it arrives rather than in one batch at the end: the
     * lookup can take a while over a slow connection, and logos appearing one by
     * one is both better feedback and safe to interrupt.
     */
    fun findMissingLogos() {
        if (logoSearch != null) return

        scope.launch {
            val missing = withContext(Dispatchers.Default) {
                store.subscriptions().filter { it.icon.isBlank() }
            }
            if (missing.isEmpty()) return@launch

            logoSearch = LogoSearch(done = 0, total = missing.size, found = 0)
            var found = 0

            try {
                LogoFetcher().fetchEach(missing.map { it.name to it.vendor }) { index, _, result ->
                    if (result is LogoFetcher.Result.Found) {
                        withContext(Dispatchers.Default) {
                            // Re-read rather than reusing the record captured
                            // before the network call: a sync could have landed
                            // in the meantime, and writing the stale copy back
                            // would undo it.
                            store.subscription(missing[index].id)?.let { current ->
                                if (current.icon.isBlank()) {
                                    store.updateSubscription(current.copy(icon = result.encoded))
                                }
                            }
                        }
                        found++
                    }
                    logoSearch = LogoSearch(index + 1, missing.size, found)
                    reload()
                }
                notice = when {
                    found == 0 -> "No logos found for those. You can still pick images by hand."
                    found == missing.size -> "Found $found logo${if (found == 1) "" else "s"}."
                    else -> "Found $found of ${missing.size}. The rest need an image choosing."
                }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't look up logos."
            } finally {
                logoSearch = null
                reload()
                // Logos are written onto the subscription records themselves, so
                // a search that found any is a change the other devices want.
                if (found > 0) schedulePush()
            }
        }
    }

    fun dismissImportProblems() {
        importProblems = emptyList()
    }

    /**
     * A restore waiting to be confirmed.
     *
     * Restoring overwrites records that are already here, so the file is read
     * and counted first and the user is told what they are about to apply —
     * rather than finding out afterwards.
     */
    var pendingRestore by mutableStateOf<PendingRestore?>(null)
        private set

    data class PendingRestore(val content: String, val summary: String)

    fun prepareRestore(result: Result<String>) {
        val text = result.getOrElse {
            error = it.message ?: "Couldn't read that file."
            return
        }
        scope.launch {
            try {
                val parsed = withContext(Dispatchers.Default) {
                    io.github.mzmknight.subtracker.core.CsvBackup.parse(text)
                }
                pendingRestore = PendingRestore(
                    content = text,
                    summary = "${parsed.subscriptions.size} subscription" +
                        (if (parsed.subscriptions.size == 1) "" else "s") +
                        ", ${parsed.prices.size} price" +
                        (if (parsed.prices.size == 1) "" else "s") +
                        (if (parsed.overrides.isNotEmpty()) ", ${parsed.overrides.size} adjustments" else ""),
                )
            } catch (e: Exception) {
                error = e.message ?: "Couldn't read that file."
            }
        }
    }

    fun cancelRestore() {
        pendingRestore = null
    }

    fun confirmRestore() {
        val text = pendingRestore?.content ?: return
        pendingRestore = null
        scope.launch {
            busy = true
            error = null
            try {
                val report = withContext(Dispatchers.Default) { store.restoreCsv(text) }
                reload()
                // A restore deliberately outranks what is here; the paired
                // devices need it too, or the first one to sync puts the old
                // state straight back.
                schedulePush()
                notice = "Restored ${report.readFromFile} record" +
                    (if (report.readFromFile == 1) "." else "s.")
                importProblems = report.problems
                findMissingLogos()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't restore that file."
            } finally {
                busy = false
            }
        }
    }

    // ------------------------------------------------------------- currency

    var homeCurrency by mutableStateOf(AppSettings.homeCurrency)
        private set
    var rates by mutableStateOf(AppSettings.rates)
        private set

    /** Currencies a subscription uses that have no rate yet. */
    val currenciesNeedingRates: List<String>
        get() = rates.missingFrom(subscriptions.map { it.currency })

    fun changeHomeCurrency(currency: String) {
        AppSettings.homeCurrency = currency
        homeCurrency = AppSettings.homeCurrency
        // Rates are "per one unit of X, in home currency", so every one of them
        // is about the old home currency and is now meaningless. Keeping them
        // would convert with numbers that mean nothing.
        AppSettings.rates = Rates(homeCurrency)
        rates = AppSettings.rates
        reload()
        // Home currency and hand-entered rates are synced settings.
        schedulePush()
        notice = "Totals are now shown in $currency. Any conversion rates were cleared."
    }

    fun setRate(currency: String, text: String) {
        val rate = Rates.parseRate(text)
        if (rate == null) {
            error = "That rate doesn't look like a number greater than zero."
            return
        }
        AppSettings.rates = rates.with(currency, rate)
        rates = AppSettings.rates
        reload()
        schedulePush()
        notice = "1 $currency = ${Rates.formatRate(rate)} $homeCurrency."
    }

    fun clearRate(currency: String) {
        AppSettings.rates = rates.without(currency)
        rates = AppSettings.rates
        reload()
        schedulePush()
        notice = if (rates.knows(currency)) {
            "$currency is back to the downloaded rate."
        } else {
            "Manual rate for $currency removed."
        }
    }

    var refreshingRates by mutableStateOf(false)
        private set

    /**
     * Download rates for the currencies actually in use.
     *
     * [force] skips the staleness check, for the button. Automatic calls — on
     * open and after a sync — leave it false so the app is not making a request
     * every time it wakes up; reference rates are published once a day, so
     * anything more often is noise.
     */
    fun refreshRates(force: Boolean = false) {
        if (refreshingRates) return

        val fetcher = RateFetcher()
        val wanted = fetcher.supported(subscriptions.map { it.currency }) - homeCurrency
        if (wanted.isEmpty()) {
            if (force) notice = "Nothing to convert — everything is already in $homeCurrency."
            return
        }

        val age = nowEpochMillis() - rates.fetchedAt
        if (!force && rates.fetchedAt > 0 && age < RateFetcher.REFRESH_AFTER_MILLIS) return

        scope.launch {
            refreshingRates = true
            try {
                when (val result = fetcher.fetch(homeCurrency, wanted)) {
                    is RateFetcher.Result.Fetched -> {
                        AppSettings.rates = rates.withFetched(result.rates, nowEpochMillis())
                        rates = AppSettings.rates
                        reload()
                        // Only worth saying out loud when the user asked. On the
                        // automatic path this happens quietly on every launch.
                        if (force) {
                            val pinned = result.rates.keys.count { rates.isManual(it) }
                            notice = buildString {
                                append("Rates updated (${result.asOf}).")
                                if (pinned > 0) append(" $pinned kept your own value.")
                            }
                        }
                    }
                    RateFetcher.Result.NothingToFetch -> Unit
                    is RateFetcher.Result.Failed ->
                        if (force) error = "Couldn't update rates: ${result.message}"
                }
            } finally {
                refreshingRates = false
            }
        }
    }

    // ------------------------------------------------------------- reminders

    var remindersEnabled by mutableStateOf(AppSettings.remindersEnabled)
        private set
    var reminderRule by mutableStateOf(AppSettings.reminderRule)
        private set

    /**
     * Observable, not a plain getter reading the notifier.
     *
     * The permission is granted in a system dialog *after* the switch is
     * flipped, and a getter is invisible to Compose — so the "not allowing
     * notifications" warning stayed on screen after the user had just allowed
     * them, which reads as the app being broken.
     */
    var canNotify by mutableStateOf(Notifications.notifier.canNotify)
        private set

    /** The next few renewals that will be announced, for showing in settings. */
    var upcomingReminders by mutableStateOf<List<Reminder>>(emptyList())
        private set

    fun toggleReminders(enabled: Boolean) {
        AppSettings.remindersEnabled = enabled
        remindersEnabled = enabled
        if (enabled) {
            Notifications.notifier.requestPermission()
            // Rules changed, so anything previously announced under the old ones
            // should be allowed to speak again.
            ReminderScheduler.forgetAnnounced()
            // requestPermission returns immediately; the answer arrives when the
            // user taps the system dialog. Watch briefly so the warning clears
            // the moment they allow it rather than at the next minute's poll.
            scope.launch {
                repeat(15) {
                    delay(1_000)
                    canNotify = Notifications.notifier.canNotify
                    if (canNotify) return@launch
                }
            }
        }
        refreshReminders()
        notice = if (enabled) "Reminders on." else "Reminders off."
    }

    fun updateReminderRule(rule: ReminderRule) {
        AppSettings.reminderDaysBefore = rule.daysBefore
        AppSettings.reminderMinute = rule.minute
        reminderRule = AppSettings.reminderRule
        ReminderScheduler.forgetAnnounced()
        refreshReminders()
        // The rule follows the user between devices; the on/off switch does not.
        schedulePush()
    }

    /**
     * Point every subscription back at the global rule.
     *
     * Ones with no rule of their own already follow it — that is what inheriting
     * means — so this exists for the other case: undoing per-subscription
     * settings in bulk after changing your mind about the default.
     */
    fun resetAllRemindersToGlobal() {
        val custom = subscriptions.filter { it.notifyMode != Reminders.MODE_INHERIT }
        if (custom.isEmpty()) {
            notice = "Every subscription already follows this."
            return
        }
        // Both of these belong in onSaved. mutate launches a coroutine, so
        // calling them straight after it ran them *before* the writes landed:
        // the reschedule then read the old rules and booked the wrong alarm.
        mutate(
            message = "${custom.size} subscription${if (custom.size == 1) "" else "s"} reset.",
            onSaved = {
                ReminderScheduler.forgetAnnounced()
                refreshReminders()
            },
        ) {
            custom.forEach { store.updateSubscription(it.copy(notifyMode = Reminders.MODE_INHERIT)) }
        }
    }

    /** Announce anything due and book the next wake-up. */
    fun refreshReminders() {
        scope.launch {
            val next = withContext(Dispatchers.Default) {
                ReminderScheduler.refresh(store)
                store.upcomingReminders(_today)
            }
            upcomingReminders = next.take(5)
            canNotify = Notifications.notifier.canNotify
        }
    }

    fun renameDevice(name: String) {
        DeviceIdentity.name = name
        notice = "This device is now \"$name\"."
    }

    // ------------------------------------------------------------- sync

    private val peers = PeerBook(Database.get())
    private val discovery: PeerDiscovery = Discovery.create()
    /**
     * Being synced *into* used to refresh the data and say nothing, so whichever
     * device you did not press sync on updated in silence — which reads as the
     * app having done nothing at all.
     *
     * The callback arrives on a Ktor handler thread, so everything here is
     * handed to [scope] before it touches Compose state.
     */
    private val server = PeerServer(store, peers, DeviceIdentity) { peerName, taken ->
        scope.launch {
            reload()
            knownPeers = peers.all()
            lastSyncedAt = nowEpochMillis()
            if (taken.changed) notice = taken.describeIncoming(peerName)
        }
    }
    private val client = PeerClient(store, peers, DeviceIdentity) { serverPort }

    var serverPort by mutableStateOf(0)
        private set
    var pairingCode by mutableStateOf<String?>(null)
        private set
    var discoveredPeers by mutableStateOf<List<DiscoveredPeer>>(emptyList())
        private set
    var knownPeers by mutableStateOf<List<KnownPeer>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set

    /** Drives the pull-to-refresh spinner, which is separate from [busy]. */
    var refreshing by mutableStateOf(false)
        private set

    /**
     * Suppresses a resume sweep that follows too soon after any other sweep —
     * including the one at launch, which Android's first ON_RESUME would
     * otherwise repeat immediately.
     */
    private val resumeThrottle =
        SyncThrottle(RESUME_SYNC_THROTTLE_MILLIS) { nowEpochMillis() }

    /** The pending debounced push, cancelled and replaced by each new edit. */
    private var pushJob: Job? = null

    val discoverySupported: Boolean get() = discovery.isSupported

    /**
     * Listening is what lets the *other* device initiate, so it starts with the
     * app rather than only while the Devices screen is open. The port is
     * LAN-only and refuses everything until pairing.
     */
    fun startSyncing() {
        scope.launch {
            try {
                serverPort = server.start()
                discovery.startAdvertising(DeviceIdentity.id, DeviceIdentity.name, serverPort)
                knownPeers = peers.all()
                // Catch up with everything already paired, so opening the app is
                // enough to be current.
                syncAllKnownPeers()
            } catch (e: Exception) {
                error = "Couldn't start syncing on this device: ${e.message}"
            }
        }
    }

    fun stopSyncing() {
        discovery.stopAdvertising()
        discovery.stopDiscovery()
        server.stop()
        serverPort = 0
    }

    fun scanForPeers() {
        scanning = true
        discovery.startDiscovery { peersFound ->
            // Never offer this device to itself.
            discoveredPeers = peersFound.filter { it.deviceId != DeviceIdentity.id }
        }
    }

    fun stopScanning() {
        scanning = false
        discovery.stopDiscovery()
    }

    /**
     * Every address a peer might reach this device at, best guess first.
     *
     * All of them, not just the winner. The ranking is a heuristic over adapter
     * names and it can be wrong — a machine with Hyper-V, a VPN and a docking
     * station has several addresses that all look plausible and only one that
     * works. When the guess is wrong the failure is silent and misleading: the
     * QR scans perfectly and then the connection times out, which reads as a
     * network fault rather than a wrong address. Offering the runners-up costs
     * one line of UI and turns a dead end into a second try.
     */
    var pairingAddresses by mutableStateOf<List<String>>(emptyList())
        private set

    var pairingAddressIndex by mutableStateOf(0)
        private set

    /** The address a peer should use to reach this device, for the QR. */
    val pairingAddress: String? get() = pairingAddresses.getOrNull(pairingAddressIndex)

    /** Whether there is anything else to offer if this one does not work. */
    val hasAlternativeAddress: Boolean get() = pairingAddresses.size > 1

    /**
     * Move to the next candidate, wrapping. The QR is rebuilt from it, so the
     * scanning device gets the new address without anyone retyping anything.
     */
    fun useNextAddress() {
        if (pairingAddresses.size < 2) return
        pairingAddressIndex = (pairingAddressIndex + 1) % pairingAddresses.size
    }

    var scannerOpen by mutableStateOf(false)
        private set

    /** Shows a code, and a QR carrying the same code plus this device's address. */
    fun showPairingCode() {
        if (serverPort == 0) startSyncing()
        pairingCode = server.beginPairing()
        // The address is the bit people get wrong when typing it by hand, which
        // is the whole reason the QR is worth having.
        pairingAddresses = localAddresses()
        pairingAddressIndex = 0
    }

    fun hidePairingCode() {
        server.cancelPairing()
        pairingCode = null
        pairingAddresses = emptyList()
        pairingAddressIndex = 0
    }

    /** Whether the code on screen is still one the server would accept. */
    fun pairingStillOpen(): Boolean = server.isPairingOpen

    /**
     * The server retired the code on its own — it was used, it expired, or it
     * was cancelled after too many wrong guesses. Only the last of those is
     * worth saying out loud: a successful pair announces itself already, and a
     * code that timed out is not news. Being guessed at is.
     */
    fun pairingWindowClosed() {
        if (pairingCode == null) return
        val refused = server.pairingWasRefused
        pairingCode = null
        pairingAddresses = emptyList()
        pairingAddressIndex = 0
        if (refused) {
            notice = "Too many wrong codes — that one is cancelled. Show a new code to try again."
        }
    }

    /** The QR payload, or null when no pairing window is open. */
    fun pairingLink(): PairingLink? {
        val code = pairingCode ?: return null
        val host = pairingAddress ?: return null
        return PairingLink(host, serverPort, code, DeviceIdentity.name)
    }

    fun openScanner() {
        error = null
        scannerOpen = true
    }

    fun closeScanner() {
        scannerOpen = false
    }

    /** The scanner reporting it cannot run — no camera, or permission refused. */
    fun reportScannerProblem(message: String) {
        scannerOpen = false
        error = message
    }

    /** Everything a scanned code carries, so pairing is one step. */
    fun pairFromLink(link: PairingLink) {
        scannerOpen = false
        pairWith(link.host, link.port, link.code)
    }

    fun pairWith(host: String, port: Int, code: String) {
        scope.launch {
            busy = true
            error = null
            try {
                val paired = client.pair(host, port, code)
                val report = client.sync(host, port, paired.deviceId)
                knownPeers = peers.all()
                lastSyncedAt = nowEpochMillis()
                reload()
                refreshRates()
                notice = "Paired with ${paired.deviceName}. ${report.describe()}."
            } catch (e: Exception) {
                error = e.message ?: "Pairing failed."
            } finally {
                busy = false
            }
        }
    }

    fun syncWith(host: String, port: Int, peerId: String) {
        scope.launch {
            busy = true
            error = null
            try {
                val report = client.sync(host, port, peerId)
                knownPeers = peers.all()
                lastSyncedAt = nowEpochMillis()
                reload()
                refreshRates()
                notice = report.describe()
            } catch (e: Exception) {
                error = e.message ?: "Sync failed."
            } finally {
                busy = false
            }
        }
    }

    // ------------------------------------------------------------- server

    private val serverClient = ServerClient(store)

    val serverAddress: String get() = AppSettings.baseUrl
    val serverSignedIn: Boolean get() = AppSettings.hasServer

    fun signInToServer(baseUrl: String, email: String, password: String) {
        scope.launch {
            busy = true
            error = null
            try {
                serverClient.signIn(baseUrl, email, password)
                val report = serverClient.sync()
                lastSyncedAt = nowEpochMillis()
                reload()
                notice = "Connected to the server. ${report.describe()}."
            } catch (e: Exception) {
                error = e.message ?: "Couldn't connect to the server."
            } finally {
                busy = false
            }
        }
    }

    /**
     * The server is merged with exactly like any other peer, which is what makes
     * "the server was down, now catch it up" an ordinary sync.
     */
    fun syncWithServer() {
        scope.launch {
            busy = true
            error = null
            try {
                val report = serverClient.sync()
                lastSyncedAt = nowEpochMillis()
                reload()
                refreshRates()
                notice = report.describe()
            } catch (e: Exception) {
                error = e.message ?: "Server sync failed."
            } finally {
                busy = false
            }
        }
    }

    fun signOutOfServer() {
        AppSettings.signOut()
        notice = "Signed out of the server. Your data is still here."
    }

    /**
     * Sync with an already-paired device using the address remembered from last
     * time — no code, no discovery, no re-pairing.
     */
    fun syncWithKnownPeer(peer: KnownPeer) {
        if (!peer.hasAddress) {
            error = "No saved address for ${peer.name}. Pair with it again to refresh it."
            return
        }
        syncWith(peer.host, peer.port, peer.id)
    }

    /** What one pass over every paired device achieved. */
    private data class Sweep(val attempted: Int, val reached: Int, val received: Int) {
        val changed: Boolean get() = received > 0

        /** No device to sync with, as opposed to none that answered. */
        val hadNobodyToCall: Boolean get() = attempted == 0
    }

    /**
     * One round trip with every paired device we hold an address for.
     *
     * The single place a sweep happens, so opening the app, returning to it,
     * pulling down and saving an edit all converge the same way and cannot
     * drift apart.
     *
     * Off the UI thread: each peer costs an HTTP round trip plus a full export
     * of the local state, and on the push path that happens without the user
     * having asked for anything.
     */
    private suspend fun sweepPeers(): Sweep {
        val reachable = peers.all().filter { it.hasAddress }
        if (reachable.isEmpty()) return Sweep(0, 0, 0)

        resumeThrottle.record()

        val sweep = withContext(Dispatchers.Default) {
            var reached = 0
            var received = 0
            for (peer in reachable) {
                // One unreachable device must not stop the others syncing. A
                // cancellation is rethrown rather than counted as a failed peer:
                // a push replaced by a newer one should stop here, not plough on
                // through the rest of the list.
                try {
                    val report = client.sync(peer.host, peer.port, peer.id)
                    reached++
                    received += report.received
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Asleep, off the network, or moved — all ordinary.
                }
            }
            Sweep(reachable.size, reached, received)
        }

        knownPeers = peers.all()
        if (sweep.reached > 0) {
            lastSyncedAt = nowEpochMillis()
            reload()
            refreshRates()
        }
        return sweep
    }

    /**
     * Called on open: quietly brings every paired device up to date rather than
     * making the user remember to press something.
     */
    fun syncAllKnownPeers() {
        scope.launch {
            val sweep = sweepPeers()
            if (sweep.changed) notice = describeReceived(sweep.received)
        }
    }

    /**
     * Coming back to the app is the moment its numbers are most likely to be
     * stale, so it is the moment worth spending a sweep on.
     *
     * Throttled against [resumeThrottle], which every sweep records into — so
     * the launch sync and the resume that immediately follows it on Android
     * count as one, and flicking between apps does not turn into a burst of
     * round trips.
     */
    fun syncOnResume() {
        if (!resumeThrottle.allow()) return
        scope.launch {
            val sweep = sweepPeers()
            if (sweep.changed) notice = describeReceived(sweep.received)
        }
    }

    /** Pull-to-refresh: the same sweep, but it always says what it found. */
    fun refreshFromPeers() {
        if (refreshing) return
        scope.launch {
            refreshing = true
            try {
                val sweep = sweepPeers()
                // Pulling down is a question, so it always gets an answer —
                // including the two different kinds of nothing, which mean very
                // different things to someone wondering why their phone is out
                // of date.
                notice = when {
                    sweep.changed -> describeReceived(sweep.received)
                    sweep.hadNobodyToCall -> "No paired device with a saved address."
                    sweep.reached == 0 -> "No paired device answered — are they on the same network?"
                    else -> "Already up to date."
                }
            } finally {
                refreshing = false
            }
        }
    }

    private fun describeReceived(count: Int): String =
        "Synced — $count change${if (count == 1) "" else "s"} received."

    /**
     * Send local changes on to every paired device, shortly after they are made.
     *
     * The point of the app is that two devices agree without being told to.
     * Without this, every edit waits for someone to remember to press sync, and
     * the device you did *not* edit on is quietly wrong until you do.
     *
     * Debounced rather than immediate, because one user action is often several
     * writes — saving a new subscription writes the record and its opening
     * price — and the payload is the whole state either way, so one push per
     * burst carries exactly as much as three would.
     *
     * Deliberately silent, in both directions. A push happens because you edited
     * something, not because you asked to sync, so a sleeping peer is the normal
     * case rather than an error worth interrupting anyone about; and a push that
     * succeeds has nothing to report either. Anything a push misses is caught by
     * the next resume, pull or manual sync.
     */
    private fun schedulePush() {
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MILLIS)
            try {
                sweepPeers()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent by design; see above.
            }
        }
    }

    fun forgetPeer(peerId: String) {
        peers.forget(peerId)
        knownPeers = peers.all()
        notice = "Device forgotten. Pair again to sync with it."
    }

    /** Exposed for the sync layer, which merges peers into this same store. */
    fun localStore(): LocalStore = store

    private companion object {
        /**
         * Long enough that a burst of writes is one push, short enough that the
         * other device is current by the time you have picked it up.
         */
        const val PUSH_DEBOUNCE_MILLIS = 2_000L

        /**
         * A resume inside this window of the last sweep is skipped. Covers both
         * the launch sync immediately followed by Android's first ON_RESUME, and
         * the user flicking between two apps.
         */
        const val RESUME_SYNC_THROTTLE_MILLIS = 30_000L
    }
}
