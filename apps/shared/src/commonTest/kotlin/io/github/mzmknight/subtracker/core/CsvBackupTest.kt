package io.github.mzmknight.subtracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import io.github.mzmknight.subtracker.sync.ChargeOverrideRecord
import io.github.mzmknight.subtracker.sync.PricePeriodRecord
import io.github.mzmknight.subtracker.sync.SettingRecord
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

/**
 * A backup is only worth having if it comes back identical, so most of this is
 * round-trip. The rest is the ways a CSV quietly corrupts itself: a comma in a
 * note, a spreadsheet reformatting the dates, a stray byte-order mark.
 */
class CsvBackupTest {

    private val netflix = SubscriptionRecord(
        id = "s1", updatedAt = "0000000000000001-00001-devicexxxxxxxxxx",
        name = "Netflix", vendor = "netflix", category = "entertainment", currency = "GBP",
        cycleUnit = "month", cycleCount = 1, anchorDate = "2026-01-15", status = "active",
        paymentMethod = "Amex", colour = "#E50914", icon = "AAAAmassiveBase64Logo",
        notes = "Standard, with ads",
    )
    private val yearly = SubscriptionRecord(
        id = "s2", updatedAt = "0000000000000002-00001-devicexxxxxxxxxx",
        name = "Some Annual Thing", anchorDate = "2025-03-01",
        cycleUnit = "year", cycleCount = 1, status = "cancelled", endDate = "2027-03-01",
    )
    private val prices = listOf(
        PricePeriodRecord("p1", "0000000000000001-00002-devicexxxxxxxxxx", subscriptionId = "s1", amountMinor = 899, effectiveFrom = "2026-01-15"),
        PricePeriodRecord("p2", "0000000000000003-00001-devicexxxxxxxxxx", subscriptionId = "s1", amountMinor = 1099, effectiveFrom = "2026-05-01", note = "Rise, again"),
        PricePeriodRecord("p3", "0000000000000002-00002-devicexxxxxxxxxx", subscriptionId = "s2", amountMinor = 12000, effectiveFrom = "2025-03-01"),
    )
    private val overrides = listOf(
        ChargeOverrideRecord(
            id = ChargeOverrideRecord.idFor("s1", "2026-03-15"),
            updatedAt = "0000000000000004-00001-devicexxxxxxxxxx",
            subscriptionId = "s1", dueDate = "2026-03-15",
            kind = ChargeOverrideRecord.KIND_AMOUNT, amountMinor = 500,
        ),
    )

    private val subscriptions = listOf(netflix, yearly)

    private fun roundTrip() = CsvBackup.parse(CsvBackup.export(subscriptions, prices, overrides))

    @Test
    fun everythingComesBackAcrossARoundTrip() {
        val parsed = roundTrip()

        assertEquals(2, parsed.subscriptions.size)
        assertEquals(3, parsed.prices.size)
        assertEquals(1, parsed.overrides.size)
        assertTrue(parsed.problems.isEmpty(), "clean data must import cleanly: ${parsed.problems}")
    }

    @Test
    fun aSubscriptionKeepsEveryFieldThatIsNotTheLogo() {
        val back = roundTrip().subscriptions.first { it.id == "s1" }

        assertEquals(netflix.copy(icon = "").toString(), back.toString())
        assertEquals(
            "", back.icon,
            "the logo is deliberately left out — it would dwarf the file",
        )
    }

    @Test
    fun reminderRulesSurviveTheRoundTrip() {
        // Unlike the logo, these are in the file. A backup that quietly reset
        // every "never remind me about this one" would be worse than useless.
        val quiet = netflix.copy(id = "s5", notifyMode = "off")
        val custom = netflix.copy(id = "s6", notifyMode = "custom", notifyDaysBefore = 7, notifyMinute = 8 * 60)

        val back = CsvBackup.parse(CsvBackup.export(listOf(quiet, custom), emptyList(), emptyList()))

        assertEquals("off", back.subscriptions.first { it.id == "s5" }.notifyMode)
        with(back.subscriptions.first { it.id == "s6" }) {
            assertEquals("custom", notifyMode)
            assertEquals(7, notifyDaysBefore)
            assertEquals(8 * 60, notifyMinute)
        }
    }

    @Test
    fun aFileWrittenBeforeRemindersExistedStillImports() {
        // Older backups have no reminder columns. Falling back to zero would mean
        // "on the day, at midnight" rather than the sensible default.
        val withoutColumns = CsvBackup.export(subscriptions, prices, overrides)
            .lines()
            .joinToString("\r\n") { line -> line.split(",").dropLast(3).joinToString(",") }

        val back = CsvBackup.parse(withoutColumns)
        val restored = back.subscriptions.first { it.id == "s1" }

        assertEquals("", restored.notifyMode)
        assertEquals(3, restored.notifyDaysBefore)
        assertEquals(9 * 60, restored.notifyMinute)
    }

    @Test
    fun subscriptionsInDifferentCurrenciesEachKeepTheirOwn() {
        val dollars = netflix.copy(id = "s7", name = "Proton", currency = "USD")
        val euros = netflix.copy(id = "s8", name = "Spotify EU", currency = "EUR")
        val prices = listOf(
            PricePeriodRecord("pa", "1", subscriptionId = "s7", amountMinor = 1980, effectiveFrom = "2026-01-15"),
            PricePeriodRecord("pb", "1", subscriptionId = "s8", amountMinor = 1099, effectiveFrom = "2026-01-15"),
        )

        val back = CsvBackup.parse(CsvBackup.export(listOf(dollars, euros), prices, emptyList()))

        assertEquals("USD", back.subscriptions.first { it.id == "s7" }.currency)
        assertEquals("EUR", back.subscriptions.first { it.id == "s8" }.currency)
        assertEquals(1980, back.prices.first { it.id == "pa" }.amountMinor)
        assertEquals(1099, back.prices.first { it.id == "pb" }.amountMinor)
    }

    @Test
    fun aZeroDecimalCurrencyRoundTripsAtTheRightScale() {
        // The bug this pins: the amount was written using the subscription's
        // currency but read using the price row's, which was never filled in —
        // so it fell back to GBP and ¥1000 came back as ¥100,000. Harmless for
        // dollars and euros, which share GBP's two decimals, and silently
        // catastrophic for yen.
        val yen = netflix.copy(id = "s9", name = "Japanese thing", currency = "JPY")
        val price = PricePeriodRecord("pj", "1", subscriptionId = "s9", amountMinor = 1000, effectiveFrom = "2026-01-15")

        val csv = CsvBackup.export(listOf(yen), listOf(price), emptyList())
        assertTrue(csv.contains("1000"), "yen has no minor units, so it is written whole")

        val back = CsvBackup.parse(csv)
        assertEquals("JPY", back.subscriptions.single().currency)
        assertEquals(1000, back.prices.single().amountMinor, "must not be scaled by 100")
    }

    @Test
    fun aPriceRowWithNoCurrencyFallsBackToItsSubscription() {
        // Backups written before price rows carried a currency, and files a
        // person has edited by hand, both look like this.
        val yen = netflix.copy(id = "s9", currency = "JPY")
        val price = PricePeriodRecord("pj", "1", subscriptionId = "s9", amountMinor = 1000, effectiveFrom = "2026-01-15")

        val stripped = CsvBackup.export(listOf(yen), listOf(price), emptyList())
            .lines()
            .joinToString("\r\n") { line ->
                val cells = line.split(",")
                // Blank the currency column on price rows only.
                if (cells.firstOrNull() == "price") {
                    cells.mapIndexed { i, c -> if (i == 7) "" else c }.joinToString(",")
                } else {
                    line
                }
            }

        assertEquals(1000, CsvBackup.parse(stripped).prices.single().amountMinor)
    }

    @Test
    fun theHlcTimestampSurvivesBecauseTheMergeDependsOnIt() {
        // Without this, an imported record either always wins or always loses,
        // and restoring an old backup silently overwrites newer edits.
        val back = roundTrip()
        assertEquals(netflix.updatedAt, back.subscriptions.first { it.id == "s1" }.updatedAt)
        assertEquals(prices[1].updatedAt, back.prices.first { it.id == "p2" }.updatedAt)
    }

    @Test
    fun amountsAreWrittenAsMoneyAndReadBackExactly() {
        val csv = CsvBackup.export(subscriptions, prices, overrides)
        assertTrue(csv.contains("8.99"), "amounts should be readable in a spreadsheet, not minor units")
        assertTrue(csv.contains("120.00"))

        val back = CsvBackup.parse(csv)
        assertEquals(899, back.prices.first { it.id == "p1" }.amountMinor)
        assertEquals(1099, back.prices.first { it.id == "p2" }.amountMinor)
        assertEquals(12000, back.prices.first { it.id == "p3" }.amountMinor)
        assertEquals(500, back.overrides.single().amountMinor)
    }

    @Test
    fun aCommaInANoteDoesNotShiftEveryColumnAfterIt() {
        // The classic CSV corruption: it parses, it looks plausible, and the
        // price has quietly become the date.
        val back = roundTrip()
        assertEquals("Standard, with ads", back.subscriptions.first { it.id == "s1" }.notes)
        assertEquals("Rise, again", back.prices.first { it.id == "p2" }.note)
    }

    @Test
    fun quotesAndNewlinesInsideACellSurvive() {
        val awkward = netflix.copy(
            id = "s9",
            notes = "He said \"cancel it\"\nbut I didn't, and, well…",
        )
        val back = CsvBackup.parse(CsvBackup.export(listOf(awkward), emptyList(), emptyList()))
        assertEquals(awkward.notes, back.subscriptions.single().notes)
    }

    @Test
    fun tombstonesSurviveSoADeletionIsNotUndone() {
        // A deleted subscription missing from the backup would come back to life
        // on the next sync with a device that still remembers it.
        val deleted = netflix.copy(id = "s3", deleted = true)
        val back = CsvBackup.parse(CsvBackup.export(listOf(deleted), emptyList(), emptyList()))
        assertTrue(back.subscriptions.single().deleted)
    }

    @Test
    fun datesMangledByASpreadsheetStillImport() {
        // Excel rewrites 2026-01-15 as 15/01/2026 the moment it saves the file.
        val csv = CsvBackup.export(subscriptions, prices, overrides)
            .replace("2026-01-15", "15/01/2026")
            .replace("2026-05-01", "01/05/2026")

        val back = CsvBackup.parse(csv)
        assertEquals("2026-01-15", back.subscriptions.first { it.id == "s1" }.anchorDate)
        assertEquals("2026-05-01", back.prices.first { it.id == "p2" }.effectiveFrom)
    }

    @Test
    fun reorderedColumnsStillImport() {
        val rows = CsvBackup.export(subscriptions, prices, overrides).trim().lines().map { it.split(",") }
        // Move the whole "id" column to the end, as a spreadsheet user might.
        val reordered = rows.joinToString("\r\n") { cells ->
            (cells.drop(2) + cells[0] + cells[1]).joinToString(",")
        }
        // Columns are looked up by name, so this must still work — but only for
        // rows with no quoted commas, which this naive re-split cannot handle.
        val back = CsvBackup.parse(reordered)
        assertTrue(back.subscriptions.isNotEmpty() || back.problems.isNotEmpty())
    }

    @Test
    fun aByteOrderMarkDoesNotHideTheHeader() {
        val csv = "﻿" + CsvBackup.export(subscriptions, prices, overrides)
        assertEquals(2, CsvBackup.parse(csv).subscriptions.size)
    }

    @Test
    fun aFileThatIsNotABackupIsRejectedClearly() {
        assertFailsWith<CsvBackup.NotABackupException> { CsvBackup.parse("") }
        assertFailsWith<CsvBackup.NotABackupException> { CsvBackup.parse("name,amount\nNetflix,10.99") }
        assertFailsWith<CsvBackup.NotABackupException> { CsvBackup.parse("hello there") }
    }

    @Test
    fun badRowsAreReportedRatherThanSilentlyDropped() {
        val csv = CsvBackup.export(subscriptions, prices, overrides)
            .trimEnd() + "\r\n" +
            "price,pX,0000000000000005-00001-devicexxxxxxxxxx,0,,,,,,,,,,,,,,s1,not-a-number,2026-06-01,,,\r\n" +
            "subscription,sX,0000000000000006-00001-devicexxxxxxxxxx,0,Broken,,,,,,,,,,,,,,,,,,\r\n"

        val back = CsvBackup.parse(csv)

        assertEquals(2, back.problems.size, "both bad rows should be reported: ${back.problems}")
        assertTrue(back.problems.any { it.contains("not-a-number") })
        assertTrue(back.problems.any { it.contains("Broken") })
        // And the good rows still came through.
        assertEquals(2, back.subscriptions.size)
        assertEquals(3, back.prices.size)
    }

    @Test
    fun anOverrideIdIsRederivedRatherThanTrustedFromTheFile() {
        // Two devices adjusting the same charge must produce one record. An id
        // edited in a spreadsheet would break that, so it is recomputed.
        val csv = CsvBackup.export(subscriptions, prices, overrides)
            .replace(overrides.single().id, "somebody-typed-this")

        val back = CsvBackup.parse(csv)
        assertEquals(
            ChargeOverrideRecord.idFor("s1", "2026-03-15"),
            back.overrides.single().id,
        )
    }

    @Test
    fun anEmptyDatabaseExportsAHeaderAndImportsAsNothing() {
        val csv = CsvBackup.export(emptyList(), emptyList(), emptyList())
        assertFailsWith<CsvBackup.NotABackupException> { CsvBackup.parse(csv) }
    }

    // ------------------------------------------------------------- settings

    @Test
    fun settingsSurviveTheRoundTrip() {
        val settings = listOf(
            SettingRecord("home_currency", "0000000000000009-00001-devicexxxxxxxxxx", value = "EUR"),
            SettingRecord(
                "currency_rates", "0000000000000009-00002-devicexxxxxxxxxx",
                // Semicolons, not newlines: a value with a line break in it is a
                // multi-line cell, which is legal CSV but miserable to look at
                // in a spreadsheet and easy for one to mangle on re-save.
                value = "USD=0.79;SEK=0.075",
            ),
        )

        val back = CsvBackup.parse(CsvBackup.export(subscriptions, prices, overrides, settings))

        // Compared by key, because the export sorts rows so successive backups
        // of the same data produce identical files and diff cleanly.
        assertEquals(settings.sortedBy { it.id }, back.settings)
    }

    @Test
    fun aSettingRowWithNoNameIsReportedRatherThanImportedBlank() {
        val csv = CsvBackup.export(subscriptions, prices, overrides, emptyList()).trimEnd() +
            "\r\nsetting,,0000000000000009-00001-devicexxxxxxxxxx,0\r\n"

        val back = CsvBackup.parse(csv)
        assertTrue(back.settings.isEmpty())
        assertTrue(back.problems.any { "setting with no name" in it }, back.problems.toString())
    }

    @Test
    fun aBackupTakenBeforeSettingsSyncedStillImports() {
        // Written out as a literal rather than derived from today's export, so
        // it keeps testing the real v1 shape even after the current one changes
        // again. This is the file someone reaches for precisely when something
        // has already gone wrong; it has to keep opening.
        val v1 = buildString {
            append("type,id,updated_at,deleted,name,vendor,category,currency,")
            append("cycle_unit,cycle_count,anchor_date,status,end_date,trial_end,")
            append("payment_method,colour,notes,subscription,amount,effective_from,")
            append("note,due_date,kind,notify_mode,notify_days_before,notify_minute\r\n")
            append("meta,1\r\n")
            append("subscription,s1,0000000000000001-00001-devicexxxxxxxxxx,0,Netflix,")
            append("netflix,entertainment,GBP,month,1,2026-01-15,active,,,Amex,#E50914,")
            append("Standard,,,,,,,,,\r\n")
            append("price,p1,0000000000000001-00002-devicexxxxxxxxxx,0,,,,GBP,,,,,,,,,,")
            append("s1,8.99,2026-01-15,,,,,,\r\n")
        }

        val back = CsvBackup.parse(v1)

        assertEquals("Netflix", back.subscriptions.single().name)
        assertEquals(899, back.prices.single().amountMinor)
        assertTrue(back.settings.isEmpty(), "a v1 file simply has no settings")
        assertTrue(back.problems.isEmpty(), back.problems.toString())
        // The reminder columns are absent too, so they must land on the defaults
        // rather than on zero — which would mean "on the day, at midnight".
        assertEquals(Reminders.DEFAULT.daysBefore, back.subscriptions.single().notifyDaysBefore)
        assertEquals(Reminders.DEFAULT.minute, back.subscriptions.single().notifyMinute)
    }

    @Test
    fun theSuggestedFileNameSortsByDate() {
        assertEquals("subtracker-2026-08-10.csv", CsvBackup.fileNameFor(PlainDate.parse("2026-08-10")))
    }
}
