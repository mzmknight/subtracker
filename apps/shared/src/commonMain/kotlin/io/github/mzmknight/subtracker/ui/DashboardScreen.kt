package io.github.mzmknight.subtracker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mzmknight.subtracker.core.DashboardFigures
import io.github.mzmknight.subtracker.core.Logo
import io.github.mzmknight.subtracker.core.Money
import io.github.mzmknight.subtracker.core.MonthTotal
import io.github.mzmknight.subtracker.core.PlainDate
import io.github.mzmknight.subtracker.core.PriceChange
import io.github.mzmknight.subtracker.core.PriceWatch
import io.github.mzmknight.subtracker.sync.ComputedCharge
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

private val MONTH_LABELS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

/** "2026-08" -> "Aug". Raw "08" told the reader nothing. */
internal fun monthLabel(key: String): String =
    key.substring(5).toIntOrNull()?.let { MONTH_LABELS[it - 1] } ?: key

@Composable
fun DashboardScreen(state: AppState) {
    val summary = state.figures ?: return
    val currency = summary.currency
    val subscriptionsById = state.subscriptions.associateBy { it.id }
    val today = state.today

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            // Cash flow and run rate side by side: different questions, never summed.
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                HeroTile(
                    label = "This month",
                    value = Money.formatRounded(summary.thisMonthMinor, currency),
                    modifier = Modifier.weight(1f),
                    trailing = { summary.monthOnMonthPercent?.let { DeltaPill(it) } },
                    // The headline is the whole month, which on the 13th is
                    // mostly a forecast. Saying which part has actually gone is
                    // the difference between "spent £28" and "will spend £28".
                    caption = buildString {
                        append(Money.format(summary.monthSpentMinor, currency))
                        append(" gone")
                        if (summary.monthRemainingMinor > 0) {
                            append("\n")
                            append(Money.format(summary.monthRemainingMinor, currency))
                            append(" still to come")
                        }
                    },
                )
                HeroTile(
                    label = "Monthly run rate",
                    value = Money.formatRounded(summary.monthlyRunRateMinor, currency),
                    modifier = Modifier.weight(1f),
                    accent = true,
                    // Rounded pounds would turn any run rate under £15/month into
                    // "£0 a day", so the daily figure keeps its pennies.
                    caption = "${summary.activeCount} active · " +
                        "${Money.formatRounded(summary.annualRunRateMinor, currency)} a year\n" +
                        "${Money.format(summary.annualRunRateMinor / 365, currency)} a day",
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                SmallTile("Last month", Money.formatRounded(summary.lastMonthMinor, currency), Modifier.weight(1f))
                SmallTile("This year", Money.formatRounded(summary.yearToDateMinor, currency), Modifier.weight(1f))
                SmallTile("All time", Money.formatRounded(summary.allTimeMinor, currency), Modifier.weight(1f))
            }
        }

        if (summary.yearTotalMinor > 0) {
            item {
                SectionCard(
                    title = "${summary.today.take(4)} so far",
                    subtitle = "Where the calendar year stands, spent against forecast.",
                ) {
                    Spacer(Modifier.height(14.dp))
                    YearProgress(summary, currency)
                }
            }
        }

        item {
            SectionCard(
                title = "Cash flow by month",
                subtitle = "Solid bars are paid; outlined bars are forecast.",
            ) {
                Spacer(Modifier.height(16.dp))
                MonthlyBars(summary)
            }
        }

        item {
            SectionCard(
                title = "Next 30 days",
                subtitle = if (state.upcoming.isEmpty()) {
                    "Nothing due in the next month."
                } else {
                    "${summary.upcoming30dCount} charges totalling " +
                        Money.formatRounded(summary.upcoming30dMinor, currency)
                },
            ) {
                if (state.upcoming.isNotEmpty()) Spacer(Modifier.height(12.dp))
                state.upcoming.forEach { charge ->
                    UpcomingRow(charge, subscriptionsById[charge.subscriptionId], today)
                }
            }
        }

        if (state.priceChanges.isNotEmpty()) {
            item {
                val creep = PriceWatch.annualCreepMinor(state.priceChanges)
                SectionCard(
                    title = "Price watch",
                    subtitle = buildString {
                        val count = state.priceChanges.size
                        append("$count change${if (count == 1) "" else "s"} in the last two years")
                        if (creep > 0) {
                            append(" · ")
                            append(Money.formatRounded(creep, currency))
                            append(" a year more than before")
                        }
                    },
                ) {
                    Spacer(Modifier.height(6.dp))
                    state.priceChanges.take(6).forEach { change ->
                        PriceChangeRow(change) { state.go(Screen.Detail(change.subscriptionId)) }
                    }
                }
            }
        }

        if (state.topSpenders().size >= 2) {
            item {
                SectionCard(
                    title = "Top spenders",
                    subtitle = "Ranked by normalised monthly cost, so a yearly plan " +
                        "is comparable with a monthly one.",
                ) {
                    Spacer(Modifier.height(8.dp))
                    state.topSpenders().forEachIndexed { index, (subscription, monthlyMinor) ->
                        TopSpenderRow(index + 1, subscription, monthlyMinor) {
                            state.go(Screen.Detail(subscription.id))
                        }
                    }
                }
            }
        }

        if (summary.runRateByCategory.isNotEmpty()) {
            item {
                SectionCard(
                    title = "Run rate by category",
                    subtitle = "Normalised monthly cost, not this month's bills.",
                ) {
                    Spacer(Modifier.height(12.dp))
                    val total = summary.runRateByCategory.values.sum().coerceAtLeast(1)
                    summary.runRateByCategory.entries
                        .sortedByDescending { it.value }
                        .forEach { (category, minor) -> CategoryBar(category, minor, total, currency) }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = mutedColour())
            }
            content()
        }
    }
}

@Composable
private fun HeroTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    accent: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (accent) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else mutedColour(),
                    modifier = Modifier.weight(1f),
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(10.dp))
            Text(
                value,
                style = MaterialTheme.typography.displaySmall,
                color = if (accent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            caption?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (accent) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    } else {
                        mutedColour()
                    },
                )
            }
        }
    }
}

@Composable
private fun SmallTile(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = mutedColour())
            Spacer(Modifier.height(8.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** Spending less than last month is good news, so down is green. */
@Composable
private fun DeltaPill(percent: Int) {
    val positive = percent > 0
    val colour = if (positive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colour.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            (if (positive) "+" else "") + "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = colour,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MonthlyBars(summary: DashboardFigures) {
    val series = windowAround(summary.monthlySeries, summary.today.take(7))
    if (series.isEmpty()) {
        Text("No charges recorded yet.", style = MaterialTheme.typography.bodySmall, color = mutedColour())
        return
    }
    val peak = series.maxOf { it.totalMinor }.coerceAtLeast(1)
    val primary = MaterialTheme.colorScheme.primary
    val currentMonth = summary.today.take(7)

    Row(
        modifier = Modifier.fillMaxWidth().height(160.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        series.forEach { point ->
            val isCurrent = point.month == currentMonth
            val fraction = (point.totalMinor.toFloat() / peak).coerceIn(0.03f, 1f)
            val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp)

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.weight((1f - fraction).coerceAtLeast(0.0001f)))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(fraction)
                        .clip(shape)
                        .then(
                            when {
                                point.isFuture -> Modifier
                                    .background(primary.copy(alpha = 0.10f))
                                    .border(1.5.dp, primary.copy(alpha = 0.5f), shape)
                                isCurrent -> Modifier.background(primary)
                                else -> Modifier.background(primary.copy(alpha = 0.55f))
                            }
                        )
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    monthLabel(point.month),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else mutedColour(),
                )
            }
        }
    }
}

/** Nine months of history plus the current month and three forecast. */
private fun windowAround(series: List<MonthTotal>, currentMonth: String): List<MonthTotal> {
    if (series.isEmpty()) return emptyList()
    val sorted = series.sortedBy { it.month }
    val index = sorted.indexOfFirst { it.month >= currentMonth }.takeIf { it >= 0 } ?: sorted.lastIndex
    val from = (index - 8).coerceAtLeast(0)
    val to = (index + 4).coerceAtMost(sorted.size)
    return sorted.subList(from, to)
}

@Composable
private fun UpcomingRow(
    charge: ComputedCharge,
    subscription: SubscriptionRecord?,
    today: PlainDate,
) {
    val name = subscription?.name ?: "Unknown"
    val daysUntil = today.daysUntil(charge.due)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name, subscription?.colour.orEmpty(), subscription?.category ?: "other",
            icon = subscription?.icon.orEmpty(),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    daysUntil <= 0 -> "Due today"
                    daysUntil == 1 -> "Tomorrow"
                    else -> "In $daysUntil days · ${charge.due.formatShort()}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedColour(),
            )
        }
        Text(
            Money.format(charge.effectiveAmountMinor, charge.currency),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

/**
 * Spent against forecast for the calendar year.
 *
 * A bar rather than three numbers on their own: "£75 of £203" is a sentence
 * people read at a glance, and the filled portion answers "how much is left"
 * without anyone doing the subtraction.
 */
@Composable
private fun YearProgress(summary: DashboardFigures, currency: String) {
    val spent = summary.yearSpentMinor
    val remaining = summary.yearRemainingMinor
    val total = summary.yearTotalMinor

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Money.format(spent, currency),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "of ${Money.format(total, currency)}",
                style = MaterialTheme.typography.bodyMedium,
                color = mutedColour(),
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
        ) {
            Box(
                modifier = Modifier
                    // Floored so a year that has barely started still shows
                    // something rather than an apparently empty bar.
                    .fillMaxWidth(summary.yearProgress.coerceIn(0.015f, 1f))
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Spacer(Modifier.height(14.dp))
        YearLine("Spent so far", spent, currency, MaterialTheme.colorScheme.primary)
        YearLine("Still to come", remaining, currency, MaterialTheme.colorScheme.onSurface)
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
        YearLine("Full year", total, currency, MaterialTheme.colorScheme.onSurface, bold = true)
    }
}

@Composable
private fun YearLine(
    label: String,
    amountMinor: Long,
    currency: String,
    colour: Color,
    bold: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = mutedColour(),
            modifier = Modifier.weight(1f),
        )
        Text(
            Money.format(amountMinor, currency),
            style = MaterialTheme.typography.bodyMedium,
            color = colour,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun PriceChangeRow(change: PriceChange, onClick: () -> Unit) {
    val rising = change.isIncrease
    val tint = if (rising) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(change.name, change.colourHex, change.category, size = 36, icon = change.icon)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    change.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (change.isScheduled) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(tint.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("upcoming", style = MaterialTheme.typography.labelSmall, color = tint)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(Money.format(change.fromMinor, change.currency))
                    append(" → ")
                    append(Money.format(change.toMinor, change.currency))
                    change.date?.let {
                        append(" · ")
                        append(it.formatLong())
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = mutedColour(),
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${if (rising) "+" else ""}${change.percent}%",
                style = MaterialTheme.typography.titleSmall,
                color = tint,
            )
            // The annual figure is the point: a £2 rise on a monthly plan and a £2
            // rise on a yearly one look identical until they are normalised.
            Text(
                "${if (rising) "+" else ""}${Money.format(change.annualImpactMinor, change.currency)} a year",
                style = MaterialTheme.typography.labelSmall,
                color = mutedColour(),
            )
        }
    }
}

@Composable
private fun TopSpenderRow(
    rank: Int,
    subscription: SubscriptionRecord,
    monthlyMinor: Long,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleSmall,
            color = mutedColour(),
            modifier = Modifier.width(22.dp),
        )
        Avatar(
            subscription.name, subscription.colour, subscription.category,
            size = 34, icon = subscription.icon,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(subscription.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                subscription.cycleLabel,
                style = MaterialTheme.typography.labelSmall,
                color = mutedColour(),
            )
        }
        Text(
            Money.format(monthlyMinor, subscription.currency),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun Avatar(
    name: String,
    colourHex: String,
    category: String,
    size: Int = 40,
    icon: String = "",
) {
    val colour = parseColour(colourHex, categoryColour(category))

    // Keyed on the encoded string so the decode happens once per logo, not on
    // every scroll frame — these are rendered inside a LazyColumn.
    val logo = remember(icon) {
        Logo.decode(icon)?.let { bytes -> runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
    }

    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            // Logos are overwhelmingly designed for a light backdrop and many are
            // transparent PNGs, so a dark tile would erase half of them. The
            // monogram keeps the tinted wash it always had.
            .background(if (logo != null) LogoTile else colour.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            Image(
                bitmap = logo,
                contentDescription = null,
                // Fit, not Crop: a wordmark that is wider than it is tall would
                // otherwise have both ends cut off and become unreadable.
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding((size * 0.14f).dp),
            )
        } else {
            Text(
                name.trim().take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = colour,
            )
        }
    }
}

/** Soft off-white: a pure white circle is harsh against the dark palette. */
private val LogoTile = Color(0xFFF4F6FA)

@Composable
private fun CategoryBar(category: String, minor: Long, total: Long, currency: String) {
    val fraction = (minor.toFloat() / total).coerceIn(0.02f, 1f)
    val colour = categoryColour(category)
    Column(modifier = Modifier.padding(vertical = 7.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(colour))
            Spacer(Modifier.width(9.dp))
            Text(
                category.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                Money.formatRounded(minor, currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(7.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(colour),
            )
        }
    }
}

/** "#e50914" -> Color, falling back when the field is empty or junk. */
internal fun parseColour(hex: String, fallback: Color): Color {
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6) return fallback
    val value = cleaned.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000L or value)
}
