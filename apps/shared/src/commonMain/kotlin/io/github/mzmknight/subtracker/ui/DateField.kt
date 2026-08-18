package io.github.mzmknight.subtracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.mzmknight.subtracker.core.PlainDate

/** Milliseconds in a day — the unit Material's date picker works in. */
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * A date you can type or pick.
 *
 * Typing accepts whatever shape the user reaches for and is resolved day-first
 * by [PlainDate.parseUserInput]; the calendar is there for when they do not know
 * the date offhand. Either way the value handed back to the caller is ISO
 * `YYYY-MM-DD`, because that is what the record stores and what the whole app
 * sorts and compares on — the UK formatting is a surface on top of it, never
 * the thing that gets saved.
 *
 * [onChange] receives "" while the text is unparseable, so a half-typed date
 * reads as "no date yet" rather than as the last valid one the user passed
 * through on the way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    isError: Boolean = false,
) {
    // Seeded once and then owned here.
    //
    // This was keyed on `value.isEmpty()`, which wiped the field: backspacing a
    // character out of a valid date made it unparseable, so the reported value
    // went blank, so the key flipped, so the remember re-seeded the text from
    // that blank value — and the half-typed date the user was editing vanished
    // mid-keystroke. The picker writes to `text` directly, so nothing else needs
    // to push a value in here.
    var text by remember { mutableStateOf(PlainDate.parseOrNull(value)?.formatUk() ?: value) }
    var showPicker by remember { mutableStateOf(false) }

    val parsed = PlainDate.parseUserInput(text)
    val looksWrong = text.isNotBlank() && parsed == null

    Column(modifier = modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(PlainDate.parseUserInput(it)?.iso.orEmpty())
            },
            label = { Text(label) },
            placeholder = { Text("31/01/2026") },
            singleLine = true,
            isError = isError || looksWrong,
            supportingText = {
                Text(
                    when {
                        looksWrong -> "Use day/month/year, like 31/01/2026."
                        // Echoing the parsed date back with a named month is the
                        // only way the user can tell 01/02 was read as 1 February.
                        parsed != null -> parsed.formatLong()
                        supportingText != null -> supportingText
                        else -> ""
                    }
                )
            },
            trailingIcon = {
                IconButton(onClick = { showPicker = true }) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = "Pick $label from a calendar")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showPicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = parsed?.let { it.toEpochDay() * MILLIS_PER_DAY },
        )

        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            // floorDiv, not /: the picker reports UTC midnight, and
                            // for any date before 1970 a truncating divide lands a
                            // day late.
                            val picked = PlainDate.fromEpochDay(
                                millis.floorDiv(MILLIS_PER_DAY).toInt(),
                            )
                            text = picked.formatUk()
                            onChange(picked.iso)
                        }
                        showPicker = false
                    },
                    enabled = state.selectedDateMillis != null,
                ) {
                    Text("Select")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
