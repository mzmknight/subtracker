package io.github.mzmknight.subtracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mzmknight.subtracker.core.ReminderRule

/** The choices offered, rather than a free number: these are the useful ones. */
private val DAY_CHOICES = listOf(0, 1, 2, 3, 5, 7, 14)

/**
 * Times on the hour and half hour only.
 *
 * A reminder is not a meeting; nobody needs 09:07, and a full time picker for a
 * value with no precision requirement is a worse experience than a short list.
 */
private val TIME_CHOICES = listOf(
    7 * 60, 8 * 60, 9 * 60, 10 * 60, 12 * 60, 17 * 60, 18 * 60, 20 * 60,
)

private fun labelForDays(days: Int): String = when (days) {
    0 -> "On the day"
    1 -> "1 day"
    else -> "$days days"
}

private fun labelForMinute(minute: Int): String =
    (minute / 60).toString().padStart(2, '0') + ":" + (minute % 60).toString().padStart(2, '0')

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReminderRuleEditor(
    rule: ReminderRule,
    onChange: (ReminderRule) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClock by remember { mutableStateOf(false) }

    if (showClock) {
        val clock = rememberTimePickerState(
            initialHour = rule.hour,
            initialMinute = rule.minuteOfHour,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showClock = false },
            title = { Text("Remind me at") },
            text = { TimePicker(state = clock) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(rule.copy(minute = clock.hour * 60 + clock.minute))
                    showClock = false
                }) { Text("Set") }
            },
            dismissButton = {
                TextButton(onClick = { showClock = false }) { Text("Cancel") }
            },
        )
    }

    Column(modifier = modifier) {
        Text(
            "How far before",
            style = MaterialTheme.typography.labelMedium,
            color = mutedColour(),
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DAY_CHOICES.forEach { days ->
                FilterChip(
                    selected = days == rule.daysBefore,
                    onClick = { onChange(rule.copy(daysBefore = days)) },
                    label = { Text(labelForDays(days)) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("At", style = MaterialTheme.typography.labelMedium, color = mutedColour())
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TIME_CHOICES.forEach { minute ->
                FilterChip(
                    selected = minute == rule.minute,
                    onClick = { onChange(rule.copy(minute = minute)) },
                    label = { Text(labelForMinute(minute)) },
                )
            }
            // Shows the chosen time rather than the word "Custom" once one is
            // set, so the row still says what the rule is at a glance.
            val isPreset = rule.minute in TIME_CHOICES
            FilterChip(
                selected = !isPreset,
                onClick = { showClock = true },
                label = { Text(if (isPreset) "Other…" else labelForMinute(rule.minute)) },
                leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) },
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            rule.describe(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Off / follow the global setting / set its own — the per-subscription choice. */
@Composable
fun ReminderModeRow(
    mode: String,
    onChange: (String) -> Unit,
    inheritedSummary: String,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = mode == "",
                onClick = { onChange("") },
                label = { Text("Default") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = mode == "custom",
                onClick = { onChange("custom") },
                label = { Text("Custom") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = mode == "off",
                onClick = { onChange("off") },
                label = { Text("Never") },
            )
        }
        if (mode == "") {
            Spacer(Modifier.height(6.dp))
            Text(
                inheritedSummary,
                style = MaterialTheme.typography.bodySmall,
                color = mutedColour(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
