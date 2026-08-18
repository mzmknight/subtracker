package io.github.mzmknight.subtracker.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mzmknight.subtracker.core.History
import io.github.mzmknight.subtracker.core.MonthHistory
import io.github.mzmknight.subtracker.core.MonthShare
import io.github.mzmknight.subtracker.core.Money
import io.github.mzmknight.subtracker.core.YearHistory

/**
 * Spending, backwards.
 *
 * Every figure here is recomputed from the subscriptions and their price
 * periods rather than read from stored rows, so correcting a price you got
 * wrong two years ago silently corrects all the months it touched.
 */
@Composable
fun HistoryScreen(state: AppState) {
    val months = state.history

    if (months.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Nothing to look back on yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Once a subscription has billed, every month it appears in shows up here — " +
                    "with a breakdown of what took what share.",
                style = MaterialTheme.typography.bodyMedium,
                color = mutedColour(),
            )
        }
        return
    }

    val years = History.years(months)

    // Everything expanded at once is unreadable by the third year. The most
    // recent year is the one being looked at; the rest are folded until asked for.
    val expandedYears = state.expandedYears ?: setOf(years.first().year)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        years.forEach { year ->
            val open = year.year in expandedYears

            item(key = "year-${year.year}") {
                YearCard(
                    year = year,
                    expanded = open,
                    onToggle = { state.toggleYear(year.year, expandedYears) },
                    onOpenSubscription = { state.go(Screen.Detail(it)) },
                )
            }

            if (open) {
                items(year.months, key = { it.month }) { month ->
                    MonthCard(
                        month = month,
                        expanded = state.expandedMonth == month.month,
                        onToggle = { state.toggleMonth(month.month) },
                        onOpenSubscription = { state.go(Screen.Detail(it)) },
                    )
                }
            }
        }
    }
}

/**
 * A year, foldable, with what it was made of.
 *
 * The breakdown lives here rather than only inside each month because that is
 * the question a year answers — "what did 2025 go on" — and opening twelve
 * months one at a time to work it out is the clutter this is meant to remove.
 */
@Composable
private fun YearCard(
    year: YearHistory,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenSubscription: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(year.year, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${year.months.size} month${if (year.months.size == 1) "" else "s"} · " +
                            "${year.shares.size} subscription${if (year.shares.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColour(),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        Money.formatRounded(year.totalMinor, year.currency),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text("spent", style = MaterialTheme.typography.labelSmall, color = mutedColour())
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse ${year.year}" else "Expand ${year.year}",
                    tint = mutedColour(),
                )
            }

            StackedBar(
                shares = year.shares,
                totalMinor = year.totalMinor,
                faded = false,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
            )

            if (expanded && year.shares.isNotEmpty()) {
                Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                    year.shares.forEach { share ->
                        ShareRow(
                            share = share,
                            percent = year.percentOf(share),
                            currency = year.currency,
                            onClick = { onOpenSubscription(share.subscriptionId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCard(
    month: MonthHistory,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenSubscription: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            monthLabel(month.month),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (month.isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (month.isFuture) {
                            Spacer(Modifier.width(8.dp))
                            Pill("forecast")
                        } else if (month.isCurrent) {
                            Spacer(Modifier.width(8.dp))
                            Pill("this month")
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${month.chargeCount} charge${if (month.chargeCount == 1) "" else "s"} · " +
                            "${month.shares.size} subscription${if (month.shares.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColour(),
                    )
                }

                Text(
                    Money.format(month.totalMinor, month.currency),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = mutedColour(),
                )
            }

            // The composition bar is always rendered, collapsed or not: it is the
            // fastest read of "what was this month" and costs one row of height.
            StackedBar(
                shares = month.shares,
                totalMinor = month.totalMinor,
                faded = month.isFuture,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Column(modifier = Modifier.padding(16.dp)) {
                    month.shares.forEach { share ->
                        ShareRow(
                            share = share,
                            percent = month.percentOf(share),
                            currency = month.currency,
                            onClick = { onOpenSubscription(share.subscriptionId) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One stacked bar showing each subscription's share of a period.
 *
 * Slices are floored to a visible minimum so a £1 line next to a £90 one still
 * has something to point at, which does mean the widths stop being exactly
 * proportional at the extremes — the printed percentage next to each name is
 * the precise figure.
 */
@Composable
private fun StackedBar(
    shares: List<MonthShare>,
    totalMinor: Long,
    faded: Boolean,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)

    if (totalMinor <= 0 || shares.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(track),
        )
        return
    }

    // Drawn in a stable order, not in size order like the legend beside it.
    // Sorting segments by size makes the stack flip the moment one subscription
    // overtakes another — a price rise in May had the colours swap places
    // halfway down the year, which reads as a bug and makes two months
    // impossible to compare at a glance. The legend's dots carry the identity,
    // so the two orders never need to agree.
    val drawn = remember(shares) {
        shares.sortedWith(compareBy({ it.name.lowercase() }, { it.subscriptionId }))
    }

    Row(
        modifier = modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(track),
    ) {
        drawn.forEach { share ->
            val fraction = share.fractionOf(totalMinor).coerceAtLeast(0.015f)
            val colour = shareColour(share)
            Box(
                modifier = Modifier
                    .weight(fraction)
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(if (faded) colour.copy(alpha = 0.35f) else colour),
            )
        }
    }
}

@Composable
private fun ShareRow(
    share: MonthShare,
    percent: Int,
    currency: String,
    onClick: () -> Unit,
) {
    val colour = shareColour(share)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(share.name, style = MaterialTheme.typography.bodyMedium)
            if (share.chargeCount > 1) {
                Text(
                    "${share.chargeCount} charges",
                    style = MaterialTheme.typography.labelSmall,
                    color = mutedColour(),
                )
            }
        }
        Text(
            "$percent%",
            style = MaterialTheme.typography.bodySmall,
            color = mutedColour(),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            Money.format(share.totalMinor, currency),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = mutedColour())
    }
}

/** The subscription's own colour, falling back to its category's. */
private fun shareColour(share: MonthShare): Color =
    parseColour(share.colourHex, categoryColour(share.category))
