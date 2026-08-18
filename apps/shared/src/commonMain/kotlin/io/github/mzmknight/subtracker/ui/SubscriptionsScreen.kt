package io.github.mzmknight.subtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mzmknight.subtracker.core.Money
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

@Composable
fun SubscriptionsScreen(state: AppState) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.subscriptions.isEmpty() && !state.busy) {
            Column(
                modifier = Modifier.fillMaxSize().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No subscriptions yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add one and this device works out every charge it will ever make, " +
                        "backwards and forwards — no server needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = mutedColour(),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.subscriptions.size > 1) {
                item(key = "sort") {
                    SortRow(state.sortOrder) { state.sortBy(it) }
                }
            }

            items(state.orderedSubscriptions(), key = { it.id }) { subscription ->
                SubscriptionRow(
                    subscription = subscription,
                    monthlyMinor = state.monthlyCostOf(subscription.id),
                    onClick = { state.go(Screen.Detail(subscription.id)) },
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = { state.go(Screen.Edit(null)) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Add") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        )
    }
}

@Composable
private fun SubscriptionRow(
    subscription: SubscriptionRecord,
    monthlyMinor: Long?,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(
                subscription.name, subscription.colour, subscription.category,
                size = 44, icon = subscription.icon,
            )
            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        subscription.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (subscription.status != "active") {
                        Spacer(Modifier.width(8.dp))
                        StatusChip(subscription.status)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(subscription.cycleLabel.replaceFirstChar { it.uppercase() })
                        if (subscription.paymentMethod.isNotBlank()) {
                            append(" · ")
                            append(subscription.paymentMethod)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColour(),
                )
            }

            if (monthlyMinor != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Money.format(monthlyMinor, subscription.currency),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "per month",
                        style = MaterialTheme.typography.labelSmall,
                        color = mutedColour(),
                    )
                }
            }
        }
    }
}

/**
 * Scrolls horizontally rather than wrapping: four chips plus the "Sort" label
 * overflow a narrow phone, and a chip that has wrapped onto its own line reads
 * as a separate control rather than one of a set.
 */
@Composable
private fun SortRow(selected: SortOrder, onSelect: (SortOrder) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Sort", style = MaterialTheme.typography.labelMedium, color = mutedColour())
        SortOrder.entries.forEach { order ->
            FilterChip(
                selected = order == selected,
                onClick = { onSelect(order) },
                label = { Text(order.label) },
            )
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val colour = when (status) {
        "trial" -> MaterialTheme.colorScheme.secondary
        "paused" -> mutedColour()
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colour.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = colour,
            fontWeight = FontWeight.Medium,
        )
    }
}
