package io.github.mzmknight.subtracker.core

import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SettingRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * The whole app, as one CSV file.
 *
 * Only *intent* is exported — subscriptions, their price history, and deliberate
 * adjustments to individual charges. Charges themselves are a pure function of
 * those three, so exporting them would be exporting a derived value that any
 * device can recompute, and would make the file enormous for no gain.
 *
 * One file with a `type` column rather than three files in a zip: it opens in
 * Excel with a double-click, and a backup that takes a second tool to read is a
 * backup people stop taking.
 *
 * Tombstones are included. A deleted subscription that vanished from the export
 * would come back to life on the next sync with a device that still remembers
 * it, so "deleted" has to survive the round trip as a fact.
 */
object CsvBackup {

    const val FORMAT_VERSION = "1"

    /** Suggested when saving. The date makes successive backups sort sensibly. */
    fun fileNameFor(today: PlainDate): String = "subtracker-${today.iso}.csv"

    private val HEADER = listOf(
        "type", "id", "updated_at", "deleted",
        // subscription
        "name", "vendor", "category", "currency", "cycle_unit", "cycle_count",
        "anchor_date", "status", "end_date", "trial_end", "payment_method", "colour", "notes",
        // price period and override
        "subscription", "amount", "effective_from", "note", "due_date", "kind",
        // reminder rule
        "notify_mode", "notify_days_before", "notify_minute",
        // setting
        "value",
    )

    private const val TYPE = 0
    private const val ID = 1
    private const val UPDATED_AT = 2
    private const val DELETED = 3
    private const val NAME = 4
    private const val VENDOR = 5
    private const val CATEGORY = 6
    private const val CURRENCY = 7
    private const val CYCLE_UNIT = 8
    private const val CYCLE_COUNT = 9
    private const val ANCHOR_DATE = 10
    private const val STATUS = 11
    private const val END_DATE = 12
    private const val TRIAL_END = 13
    private const val PAYMENT_METHOD = 14
    private const val COLOUR = 15
    private const val NOTES = 16
    private const val SUBSCRIPTION = 17
    private const val AMOUNT = 18
    private const val EFFECTIVE_FROM = 19
    private const val NOTE = 20
    private const val DUE_DATE = 21
    private const val KIND = 22
    private const val NOTIFY_MODE = 23
    private const val NOTIFY_DAYS = 24
    private const val NOTIFY_MINUTE = 25
    private const val VALUE = 26

    // ------------------------------------------------------------- export

    fun export(
        subscriptions: List<SubscriptionRecord>,
        prices: List<PricePeriodRecord>,
        overrides: List<ChargeOverrideRecord>,
        settings: List<SettingRecord> = emptyList(),
    ): String {
        val currencyOf = subscriptions.associate { it.id to it.currency }

        return buildString {
            appendRow(HEADER)
            appendRow(row { it[TYPE] = "meta"; it[ID] = FORMAT_VERSION })

            for (s in subscriptions.sortedBy { it.name.lowercase() }) {
                appendRow(
                    row {
                        it[TYPE] = "subscription"
                        it[ID] = s.id
                        it[UPDATED_AT] = s.updatedAt
                        it[DELETED] = if (s.deleted) "1" else "0"
                        it[NAME] = s.name
                        it[VENDOR] = s.vendor
                        it[CATEGORY] = s.category
                        it[CURRENCY] = s.currency
                        it[CYCLE_UNIT] = s.cycleUnit
                        it[CYCLE_COUNT] = s.cycleCount.toString()
                        it[ANCHOR_DATE] = s.anchorDate
                        it[STATUS] = s.status
                        it[END_DATE] = s.endDate.orEmpty()
                        it[TRIAL_END] = s.trialEnd.orEmpty()
                        it[PAYMENT_METHOD] = s.paymentMethod
                        it[COLOUR] = s.colour
                        it[NOTES] = s.notes
                        // Included, unlike the logo: a reminder rule is a
                        // sentence of settings, not kilobytes of image, and a
                        // backup that quietly reset every "never remind me about
                        // this one" would be worse than useless.
                        it[NOTIFY_MODE] = s.notifyMode
                        it[NOTIFY_DAYS] = s.notifyDaysBefore.toString()
                        it[NOTIFY_MINUTE] = s.notifyMinute.toString()
                        // `icon` is deliberately absent: a logo is tens of
                        // kilobytes of base64 that would dwarf the real data and
                        // make the file unreadable in a spreadsheet. Import
                        // carries the existing logo forward instead.
                    }
                )
            }

            for (p in prices.sortedWith(compareBy({ it.subscriptionId }, { it.effectiveFrom }))) {
                appendRow(
                    row {
                        it[TYPE] = "price"
                        it[ID] = p.id
                        it[UPDATED_AT] = p.updatedAt
                        it[DELETED] = if (p.deleted) "1" else "0"
                        it[SUBSCRIPTION] = p.subscriptionId
                        // Written as a decimal, not as minor units: "10.99" is
                        // what someone checking the file expects to see. It is
                        // parsed back with integer maths, so nothing is lost.
                        it[AMOUNT] = Money.formatBare(
                            p.amountMinor, currencyOf[p.subscriptionId] ?: "GBP",
                        )
                        // The amount above is meaningless without it. "1000" is
                        // ¥1000 or £10.00 depending entirely on this column, and
                        // leaving it blank made every yen price import a hundred
                        // times too large.
                        it[CURRENCY] = currencyOf[p.subscriptionId] ?: "GBP"
                        it[EFFECTIVE_FROM] = p.effectiveFrom
                        it[NOTE] = p.note
                    }
                )
            }

            for (o in overrides.sortedWith(compareBy({ it.subscriptionId }, { it.dueDate }))) {
                appendRow(
                    row {
                        it[TYPE] = "override"
                        it[ID] = o.id
                        it[UPDATED_AT] = o.updatedAt
                        it[DELETED] = if (o.deleted) "1" else "0"
                        it[SUBSCRIPTION] = o.subscriptionId
                        it[DUE_DATE] = o.dueDate
                        it[KIND] = o.kind
                        it[AMOUNT] = o.amountMinor?.let {
                            Money.formatBare(it, currencyOf[o.subscriptionId] ?: "GBP")
                        }.orEmpty()
                        it[CURRENCY] = currencyOf[o.subscriptionId] ?: "GBP"
                    }
                )
            }

            // Last, so the rows a person opening this in a spreadsheet came to
            // read are not pushed below a block of machine settings.
            for (setting in settings.sortedBy { it.id }) {
                appendRow(
                    row {
                        it[TYPE] = "setting"
                        it[ID] = setting.id
                        it[UPDATED_AT] = setting.updatedAt
                        it[DELETED] = if (setting.deleted) "1" else "0"
                        it[VALUE] = setting.value
                    }
                )
            }
        }
    }

    private fun row(fill: (MutableList<String>) -> Unit): List<String> {
        val cells = MutableList(HEADER.size) { "" }
        fill(cells)
        return cells
    }

    private fun StringBuilder.appendRow(cells: List<String>) {
        cells.forEachIndexed { index, cell ->
            if (index > 0) append(',')
            append(escape(cell))
        }
        append("\r\n")
    }

    /** RFC 4180 quoting. Notes contain commas; without this the file is corrupt. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    // ------------------------------------------------------------- import

    data class Parsed(
        val subscriptions: List<SubscriptionRecord>,
        val prices: List<PricePeriodRecord>,
        val overrides: List<ChargeOverrideRecord>,
        val settings: List<SettingRecord> = emptyList(),
        /** Rows that could not be read, described well enough to go and fix them. */
        val problems: List<String> = emptyList(),
    ) {
        val total: Int
            get() = subscriptions.size + prices.size + overrides.size + settings.size
        val isEmpty: Boolean get() = total == 0
    }

    class NotABackupException(message: String) : Exception(message)

    fun parse(text: String): Parsed {
        val rows = readRows(text)
        if (rows.isEmpty()) throw NotABackupException("That file is empty.")

        val header = rows.first()
        val index = header.withIndex().associate { (i, name) -> name.trim().lowercase() to i }
        if ("type" !in index || "id" !in index) {
            throw NotABackupException(
                "That doesn't look like a SubTracker backup — it has no \"type\" column.",
            )
        }

        val settings = mutableListOf<SettingRecord>()
        val subscriptions = mutableListOf<SubscriptionRecord>()
        val prices = mutableListOf<PricePeriodRecord>()
        val overrides = mutableListOf<ChargeOverrideRecord>()
        val problems = mutableListOf<String>()

        // Columns are looked up by name rather than by position so a file that
        // has been through a spreadsheet, with columns reordered or extras added,
        // still imports.
        fun cell(row: List<String>, column: Int): String =
            index[HEADER[column]]?.let { row.getOrNull(it) }?.trim().orEmpty()

        // Excel rewrites 2026-01-15 as 15/01/2026 the moment it saves the file,
        // so dates are read back with the same day-first parser the UI uses.
        fun date(row: List<String>, column: Int): String =
            PlainDate.parseUserInput(cell(row, column))?.iso.orEmpty()

        // Gathered before anything is parsed, so a price row can be read in the
        // light of its subscription however the rows happen to be ordered — and
        // so backups written before price rows carried a currency of their own
        // still import at the right scale.
        val currencyOf = rows.drop(1)
            .filter { cell(it, TYPE).equals("subscription", ignoreCase = true) }
            .associate { cell(it, ID) to cell(it, CURRENCY).ifBlank { "GBP" } }

        /** The currency an amount on [row] is denominated in. */
        fun currencyFor(row: List<String>, subscriptionId: String): String =
            cell(row, CURRENCY)
                .ifBlank { currencyOf[subscriptionId].orEmpty() }
                .ifBlank { "GBP" }

        rows.drop(1).forEachIndexed { offset, cells ->
            val line = offset + 2
            if (cells.all { it.isBlank() }) return@forEachIndexed

            val type = cell(cells, TYPE).lowercase()
            val id = cell(cells, ID)
            val updatedAt = cell(cells, UPDATED_AT)
            val deleted = cell(cells, DELETED).let { it == "1" || it.equals("true", true) }

            when (type) {
                "meta", "" -> Unit

                "subscription" -> {
                    val anchor = date(cells, ANCHOR_DATE)
                    when {
                        id.isBlank() -> problems += "Line $line: a subscription with no id."
                        anchor.isBlank() && !deleted ->
                            problems += "Line $line: \"${cell(cells, NAME)}\" has no readable first billing date."
                        else -> subscriptions += SubscriptionRecord(
                            id = id,
                            updatedAt = updatedAt,
                            deleted = deleted,
                            name = cell(cells, NAME),
                            vendor = cell(cells, VENDOR),
                            category = cell(cells, CATEGORY).ifBlank { "other" },
                            currency = cell(cells, CURRENCY).ifBlank { "GBP" },
                            cycleUnit = cell(cells, CYCLE_UNIT).ifBlank { "month" },
                            cycleCount = cell(cells, CYCLE_COUNT).toIntOrNull() ?: 1,
                            anchorDate = anchor,
                            status = cell(cells, STATUS).ifBlank { "active" },
                            endDate = date(cells, END_DATE).ifBlank { null },
                            trialEnd = date(cells, TRIAL_END).ifBlank { null },
                            paymentMethod = cell(cells, PAYMENT_METHOD),
                            colour = cell(cells, COLOUR),
                            notes = cell(cells, NOTES),
                            notifyMode = cell(cells, NOTIFY_MODE),
                            // A file written before reminders existed has no
                            // such columns, so these fall back to the defaults
                            // rather than to zero — which would mean "on the
                            // day, at midnight".
                            notifyDaysBefore = cell(cells, NOTIFY_DAYS).toIntOrNull()
                                ?: Reminders.DEFAULT.daysBefore,
                            notifyMinute = cell(cells, NOTIFY_MINUTE).toIntOrNull()
                                ?: Reminders.DEFAULT.minute,
                        )
                    }
                }

                "price" -> {
                    val subscriptionId = cell(cells, SUBSCRIPTION)
                    val amount = Money.parseOrNull(
                        cell(cells, AMOUNT), currencyFor(cells, subscriptionId),
                    )
                    val from = date(cells, EFFECTIVE_FROM)
                    when {
                        id.isBlank() || subscriptionId.isBlank() ->
                            problems += "Line $line: a price with no id or no subscription."
                        amount == null ->
                            problems += "Line $line: \"${cell(cells, AMOUNT)}\" is not an amount."
                        from.isBlank() ->
                            problems += "Line $line: a price with no readable date."
                        else -> prices += PricePeriodRecord(
                            id = id,
                            updatedAt = updatedAt,
                            deleted = deleted,
                            subscriptionId = subscriptionId,
                            amountMinor = amount,
                            effectiveFrom = from,
                            note = cell(cells, NOTE),
                        )
                    }
                }

                "override" -> {
                    val subscriptionId = cell(cells, SUBSCRIPTION)
                    val due = date(cells, DUE_DATE)
                    when {
                        subscriptionId.isBlank() || due.isBlank() ->
                            problems += "Line $line: an adjustment with no subscription or date."
                        else -> overrides += ChargeOverrideRecord(
                            // Derived from (subscription, date) rather than
                            // trusted from the file: two devices adjusting the
                            // same charge must produce one record, and an id
                            // edited in a spreadsheet would break that.
                            id = ChargeOverrideRecord.idFor(subscriptionId, due),
                            updatedAt = updatedAt,
                            deleted = deleted,
                            subscriptionId = subscriptionId,
                            dueDate = due,
                            kind = cell(cells, KIND).ifBlank { ChargeOverrideRecord.KIND_SKIPPED },
                            amountMinor = Money.parseOrNull(
                                cell(cells, AMOUNT), currencyFor(cells, subscriptionId),
                            ),
                        )
                    }
                }

                // A file written before settings synced has no such rows, and a
                // file written after has no "value" column to read on a device
                // still running the old build — both degrade to no settings
                // rather than to a broken import.
                "setting" -> {
                    if (id.isBlank()) {
                        problems += "Line $line: a setting with no name."
                    } else {
                        settings += SettingRecord(
                            id = id,
                            updatedAt = updatedAt,
                            deleted = deleted,
                            value = cell(cells, VALUE),
                        )
                    }
                }

                else -> problems += "Line $line: unknown row type \"$type\"."
            }
        }

        if (subscriptions.isEmpty() && prices.isEmpty() && overrides.isEmpty() &&
            settings.isEmpty() && problems.isEmpty()
        ) {
            throw NotABackupException("That file has a header but no rows.")
        }

        return Parsed(subscriptions, prices, overrides, settings, problems)
    }

    /**
     * A minimal RFC 4180 reader.
     *
     * Written out rather than split on commas because a note containing a comma —
     * or a newline, which a spreadsheet will happily put in a cell — silently
     * shifts every column after it and imports garbage that looks plausible.
     */
    private fun readRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var index = 0

        fun endCell() {
            row.add(cell.toString())
            cell.clear()
        }

        fun endRow() {
            endCell()
            rows.add(row)
            row = mutableListOf()
        }

        // A byte-order mark survives a spreadsheet round trip and would otherwise
        // become part of the first column's name, so the header never matches.
        val body = text.removePrefix("﻿")

        while (index < body.length) {
            val ch = body[index]
            when {
                inQuotes && ch == '"' && index + 1 < body.length && body[index + 1] == '"' -> {
                    cell.append('"')
                    index++
                }
                ch == '"' -> inQuotes = !inQuotes
                !inQuotes && ch == ',' -> endCell()
                !inQuotes && (ch == '\n' || ch == '\r') -> {
                    if (ch == '\r' && index + 1 < body.length && body[index + 1] == '\n') index++
                    endRow()
                }
                else -> cell.append(ch)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) endRow()

        return rows.filterNot { cells -> cells.all { it.isBlank() } }
    }
}
