package io.github.mzmknight.subtracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.mzmknight.subtracker.core.Money
import io.github.mzmknight.subtracker.core.PlainDate
import io.github.mzmknight.subtracker.sync.ComputedCharge

@Composable
fun DetailScreen(state: AppState) {
    val detail = state.detail ?: return
    val subscription = detail.subscription
    val today = state.today
    val currency = subscription.currency

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            subscription.name, subscription.colour, subscription.category,
                            size = 48, icon = subscription.icon,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(subscription.name, style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "${subscription.cycleLabel.replaceFirstChar { it.uppercase() }} · ${subscription.status}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = mutedColour(),
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))

                    val monthly = state.monthlyCostOf(subscription.id) ?: 0
                    KeyValue("Costs per month", Money.format(monthly, currency))
                    KeyValue("Paid to date", Money.format(detail.paidToDate(today), currency))
                    detail.nextCharge(today)?.let { next ->
                        KeyValue(
                            "Next charge",
                            "${Money.format(next.effectiveAmountMinor, currency)} on ${next.due.formatLong()}",
                        )
                    }
                    subscription.trialEnds?.let { trialEnd ->
                        if (trialEnd >= today) {
                            KeyValue(
                                "Trial ends",
                                "${trialEnd.formatLong()} — ${today.daysUntil(trialEnd)} days left",
                            )
                        }
                    }
                    if (subscription.paymentMethod.isNotBlank()) {
                        KeyValue("Paid with", subscription.paymentMethod)
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { state.go(Screen.Edit(subscription.id)) }) {
                            Text("Edit")
                        }
                        TextButton(onClick = { state.delete(subscription) }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Price history", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Each charge is valued at the price in force on its own due date, " +
                            "so past totals don't change when the price does.",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColour(),
                    )
                    Spacer(Modifier.height(10.dp))
                    detail.prices.sortedByDescending { it.effectiveFrom }.forEach { period ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                Money.format(period.amountMinor, currency),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.width(90.dp),
                            )
                            Text(
                                "from ${period.from?.formatLong() ?: period.effectiveFrom}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = mutedColour(),
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    AddPriceChange(currency) { amountMinor, effectiveFrom ->
                        state.addPriceChange(subscription.id, amountMinor, effectiveFrom)
                    }
                }
            }
        }

        item {
            Text(
                "Charges",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        val ordered = detail.charges.sortedByDescending { it.due.iso }
        items(ordered, key = { it.due.iso }) { charge ->
            ChargeRow(
                charge = charge,
                today = today,
                currency = currency,
                onSkip = { state.skipCharge(charge) },
                onRestore = { state.clearChargeOverride(charge) },
            )
        }
    }
}

/**
 * Records a new price from the date it took effect.
 *
 * This is the only way a price rise ever enters the app, and until it existed
 * the edit form's advice to "add a new price from the date it took effect"
 * pointed at a control that was not there. Editing the subscription cannot do
 * this job: that would change what every past charge is valued at, and the whole
 * point of storing periods separately is that last year's total stays true.
 */
@Composable
private fun AddPriceChange(currency: String, onAdd: (Long, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var amountText by remember { mutableStateOf("") }
    var fromText by remember { mutableStateOf("") }

    val amountMinor = Money.parseOrNull(amountText, currency)
    val fromValid = PlainDate.parseOrNull(fromText) != null
    val canAdd = amountMinor != null && fromValid

    if (!open) {
        TextButton(onClick = { open = true }) { Text("Record a price change") }
        return
    }

    Column {
        OutlinedTextField(
            value = amountText,
            onValueChange = { amountText = it },
            label = { Text("New price") },
            singleLine = true,
            isError = amountText.isNotBlank() && amountMinor == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        // Stacked rather than side by side: the date field carries a calendar
        // button and an echo of the parsed date, and neither fits in half a phone.
        DateField(
            value = fromText,
            onChange = { fromText = it },
            label = "From",
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Charges on or after this date are repriced. Everything before it keeps " +
                "what it actually cost.",
            style = MaterialTheme.typography.bodySmall,
            color = mutedColour(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onAdd(amountMinor!!, fromText.trim())
                    open = false
                    amountText = ""
                    fromText = ""
                },
                enabled = canAdd,
            ) {
                Text("Save price")
            }
            TextButton(onClick = { open = false }) { Text("Cancel") }
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = mutedColour(),
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ChargeRow(
    charge: ComputedCharge,
    today: PlainDate,
    currency: String,
    onSkip: () -> Unit,
    onRestore: () -> Unit,
) {
    val settled = charge.isSettled(today)

    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    charge.due.formatLong(),
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (charge.isSkipped) TextDecoration.LineThrough else null,
                )
                Text(
                    buildString {
                        append(if (settled) "paid" else "forecast")
                        if (charge.hasAmountOverride) append(" · amount corrected")
                        if (charge.isSkipped) append(" · skipped")
                        if (charge.isRefunded) append(" · refunded")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColour(),
                )
            }
            Text(
                Money.format(charge.effectiveAmountMinor, currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (settled) FontWeight.Medium else FontWeight.Normal,
                color = if (charge.isSkipped) mutedColour() else MaterialTheme.colorScheme.onSurface,
            )
            if (charge.override != null) {
                TextButton(onClick = onRestore) { Text("Restore") }
            } else if (!settled) {
                TextButton(onClick = onSkip) { Text("Skip") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    }
}
