package io.github.mzmknight.subtracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Typed dates, read the way they are written in the UK.
 *
 * The dangerous case here is not a rejected date — that is visible. It is
 * 01/02/2026 being silently stored as 2 January, which looks fine, saves fine,
 * and quietly bills a month early forever.
 */
class UkDateTest {

    private fun parsed(text: String) = PlainDate.parseUserInput(text)?.iso

    @Test
    fun dayComesFirst() {
        assertEquals("2026-02-01", parsed("01/02/2026"))
        assertEquals("2026-01-31", parsed("31/01/2026"))
        assertEquals("2026-12-25", parsed("25/12/2026"))
    }

    @Test
    fun anAmbiguousDateIsNeverReadAsMonthFirst() {
        // Both readings are valid dates, so nothing downstream can catch this.
        assertEquals("2026-02-03", parsed("03/02/2026"))
        assertEquals("2026-01-02", parsed("02/01/2026"))
    }

    @Test
    fun theUsualSeparatorsAllWork() {
        assertEquals("2026-08-09", parsed("09/08/2026"))
        assertEquals("2026-08-09", parsed("09-08-2026"))
        assertEquals("2026-08-09", parsed("09.08.2026"))
        assertEquals("2026-08-09", parsed("09 08 2026"))
    }

    @Test
    fun singleDigitDaysAndMonthsAreAccepted() {
        assertEquals("2026-08-09", parsed("9/8/2026"))
        assertEquals("2026-01-01", parsed("1/1/2026"))
    }

    @Test
    fun aTwoDigitYearIsThisCentury() {
        assertEquals("2026-08-09", parsed("9/8/26"))
        assertEquals("2030-03-01", parsed("01/03/30"))
    }

    @Test
    fun isoIsStillAcceptedBecauseItIsWhatTheRecordsHold() {
        // A four-digit leading component cannot be a day, so there is no
        // ambiguity to resolve and no reason to reject it.
        assertEquals("2026-01-31", parsed("2026-01-31"))
        assertEquals("2026-01-31", parsed("2026/01/31"))
    }

    @Test
    fun nonsenseIsRejectedRatherThanGuessedAt() {
        assertNull(parsed(""))
        assertNull(parsed("   "))
        assertNull(parsed("tomorrow"))
        assertNull(parsed("31/01"))
        assertNull(parsed("31/01/2026/05"))
        assertNull(parsed("aa/bb/cccc"))
        assertNull(parsed("9/8/2"))
    }

    @Test
    fun impossibleDatesAreRejectedNotClamped() {
        // Clamping 31 February to the 28th would silently change the anchor, and
        // the anchor is what every future charge is generated from.
        assertNull(parsed("31/02/2026"))
        assertNull(parsed("32/01/2026"))
        assertNull(parsed("01/13/2026"))
        assertNull(parsed("00/01/2026"))
    }

    @Test
    fun leapDaysAreAcceptedOnlyInLeapYears() {
        assertEquals("2028-02-29", parsed("29/02/2028"))
        assertNull(parsed("29/02/2026"))
    }

    @Test
    fun formattingIsZeroPaddedDayFirst() {
        assertEquals("09/08/2026", PlainDate.parse("2026-08-09").formatUk())
        assertEquals("31/12/2026", PlainDate.parse("2026-12-31").formatUk())
    }

    @Test
    fun whatIsTypedSurvivesARoundTripBackToTheField() {
        // The field seeds its text from the stored ISO value; if these two ever
        // disagreed, opening a saved subscription would show a different date
        // from the one that was saved.
        for (iso in listOf("2026-01-01", "2026-08-09", "2028-02-29", "2025-11-30")) {
            val shown = PlainDate.parse(iso).formatUk()
            assertEquals(iso, parsed(shown), "$iso displayed as $shown must read back as itself")
        }
    }

    @Test
    fun theEpochDayRoundTripThePickerUsesIsExact() {
        // The picker hands back UTC midnight millis; the field divides back down
        // to a day. Any drift here moves a billing date by a day.
        for (iso in listOf("1970-01-01", "2026-08-09", "2028-02-29", "2099-12-31")) {
            val date = PlainDate.parse(iso)
            val millis = date.toEpochDay() * 86_400_000L
            assertEquals(iso, PlainDate.fromEpochDay(millis.floorDiv(86_400_000L).toInt()).iso)
        }
    }
}
