package io.github.mzmknight.subtracker.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import io.github.mzmknight.subtracker.core.Money

/**
 * Switching an amount from one currency to another on the edit form.
 *
 * The bug behind these: the field held "18.00" and the currency chips changed
 * only the currency. "18.00" is not a legal number of yen — [Money.parseOrNull]
 * rejects a fraction a zero-decimal currency cannot hold — so picking JPY turned
 * the field red and disabled Save, with nothing on screen saying that the pence
 * were what had gone wrong.
 */
class EditFormTest {

    @Test
    fun droppingToAZeroDecimalCurrencyKeepsTheAmountTheUserTyped() {
        assertEquals("18", restateAmount("18.00", "GBP", "JPY"))
        assertEquals("1000", restateAmount("999.50", "GBP", "JPY"))
    }

    @Test
    fun risingToATwoDecimalCurrencyRestoresTheMinorUnits() {
        assertEquals("18.00", restateAmount("18", "JPY", "GBP"))
    }

    @Test
    fun whatComesBackIsSomethingTheFormCanParse() {
        // The point of the exercise: the restated text has to survive the very
        // validation that rejected the original.
        val restated = restateAmount("18.00", "GBP", "JPY")
        assertEquals(18L, Money.parseOrNull(restated, "JPY"))
        assertNull(Money.parseOrNull("18.00", "JPY"))
    }

    @Test
    fun currenciesWithTheSameMinorUnitsLeaveTheTextAlone() {
        // Not reformatted: rewriting "18." to "18.00" mid-keystroke would move
        // the caret out from under the user.
        assertEquals("18.", restateAmount("18.", "GBP", "USD"))
        assertEquals("18.00", restateAmount("18.00", "GBP", "EUR"))
    }

    @Test
    fun textThatIsNotANumberYetIsHandedBackUntouched() {
        assertEquals("", restateAmount("", "GBP", "JPY"))
        assertEquals("abc", restateAmount("abc", "GBP", "JPY"))
    }
}
