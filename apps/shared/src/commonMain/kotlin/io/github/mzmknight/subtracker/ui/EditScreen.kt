package io.github.mzmknight.subtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.mzmknight.subtracker.core.BrandColours
import io.github.mzmknight.subtracker.core.Logo
import io.github.mzmknight.subtracker.core.Money
import io.github.mzmknight.subtracker.core.ReminderRule
import io.github.mzmknight.subtracker.core.Reminders
import io.github.mzmknight.subtracker.data.LogoFetcher
import io.github.mzmknight.subtracker.data.scaleToPng
import io.github.mzmknight.subtracker.core.PlainDate
import io.github.mzmknight.subtracker.sync.SubscriptionRecord

private val CATEGORIES = listOf("entertainment", "software", "utilities", "health", "finance", "other")
internal val CYCLE_UNITS = listOf("day", "week", "month", "year")
private val STATUSES = listOf("active", "trial", "paused", "cancelled")

@Composable
fun EditScreen(state: AppState, subscriptionId: String?) {
    val existing = subscriptionId?.let { id -> state.subscriptions.firstOrNull { it.id == id } }
    val currentPrice = state.detail
        ?.takeIf { it.subscription.id == subscriptionId }
        ?.currentPrice(state.today)

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var category by remember { mutableStateOf(existing?.category ?: "entertainment") }
    var cycleUnit by remember { mutableStateOf(existing?.cycleUnit ?: "month") }
    var cycleCount by remember { mutableStateOf((existing?.cycleCount ?: 1).toString()) }

    // Whether the count-and-unit controls are showing. Seeded from the record
    // rather than defaulting closed: a subscription billed every 18 months has
    // no preset to land on, and a form that offered only four chips would look
    // like it disagreed with the data it had just loaded.
    //
    // Held here and not inside the LazyColumn item, because an item scrolled off
    // screen is disposed — remembering it down there collapsed the panel the
    // moment the user scrolled to the save button and back.
    var customCycle by remember {
        mutableStateOf(CYCLE_PRESETS.none { it.matches(cycleUnit, cycleCount.toIntOrNull() ?: 1) })
    }
    var anchorDate by remember { mutableStateOf(existing?.anchorDate.orEmpty()) }
    var status by remember { mutableStateOf(existing?.status ?: "active") }
    var endDate by remember { mutableStateOf(existing?.endDate.orEmpty()) }
    var paymentMethod by remember { mutableStateOf(existing?.paymentMethod.orEmpty()) }
    var amountText by remember {
        // Formatted in the subscription's own currency, not always GBP: a yen
        // amount has no minor units, so 1000 formatted as GBP reads "10.00".
        val own = existing?.currency ?: "GBP"
        mutableStateOf(currentPrice?.let { Money.formatBare(it.amountMinor, own) }.orEmpty())
    }
    var colour by remember { mutableStateOf(existing?.colour.orEmpty()) }
    var colourPickedManually by remember { mutableStateOf(!existing?.colour.isNullOrBlank()) }

    /**
     * Follows the name until the user overrides it, so typing "Netflix" turns
     * the swatch Netflix red without anyone having to go looking for a picker.
     */
    val effectiveColour =
        if (colourPickedManually && colour.isNotBlank()) colour else BrandColours.suggest(name)

    var notifyMode by remember { mutableStateOf(existing?.notifyMode.orEmpty()) }
    var notifyDays by remember {
        mutableStateOf(existing?.notifyDaysBefore ?: Reminders.DEFAULT.daysBefore)
    }
    var notifyMinute by remember {
        mutableStateOf(existing?.notifyMinute ?: Reminders.DEFAULT.minute)
    }

    var icon by remember { mutableStateOf(existing?.icon.orEmpty()) }
    var fetchingLogo by remember { mutableStateOf(false) }
    var logoMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Scaling happens off the main thread: the picker hands back whatever the
    // camera roll holds, and decoding a 12-megapixel photo on the UI thread
    // freezes the form for a visible beat.
    val pickImage = rememberImagePicker { picked ->
        if (picked == null) return@rememberImagePicker
        scope.launch {
            logoMessage = null
            val png = withContext(Dispatchers.Default) { scaleToPng(picked, Logo.TARGET_DIMENSION) }
            when {
                png == null -> logoMessage = "That file isn't an image I can read."
                !Logo.isWithinLimit(png) -> logoMessage = "That image is too detailed to sync — try a simpler one."
                else -> icon = Logo.encode(png)
            }
        }
    }

    var currency by remember {
        mutableStateOf(existing?.currency ?: state.figures?.currency ?: "GBP")
    }
    val anchorValid = PlainDate.parseOrNull(anchorDate) != null
    val endValid = endDate.isBlank() || PlainDate.parseOrNull(endDate) != null
    val countValid = cycleCount.toIntOrNull()?.let { it >= 1 } == true
    val amountMinor = Money.parseOrNull(amountText, currency)
    val amountValid = existing != null || amountMinor != null
    val canSave = name.isNotBlank() && anchorValid && endValid && countValid && amountValid && !state.busy

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            // Shown when editing too. Hiding it was defensible — a price rise
            // must not rewrite what past months cost — but it meant the cost was
            // nowhere on the screen people go to change the cost, and the only
            // way in was a button on another screen entirely.
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                label = { Text("Amount (${Money.symbol(currency).trim()})") },
                supportingText = {
                    Text(
                        if (existing == null) {
                            "The price it charges each cycle, e.g. 10.99"
                        } else {
                            "Corrects the current price, including on charges already " +
                                "recorded at it. For an actual price rise use \"Record a price " +
                                "change\" on the subscription instead, so past months keep what " +
                                "they really cost."
                        },
                    )
                },
                isError = amountText.isNotBlank() && amountMinor == null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Column {
                Label("Billing cycle")

                CyclePresets(
                    unit = cycleUnit,
                    count = cycleCount.toIntOrNull() ?: 1,
                    customOpen = customCycle,
                    onPick = { unit, count ->
                        cycleUnit = unit
                        cycleCount = count.toString()
                        customCycle = false
                    },
                    onCustom = { customCycle = true },
                )

                if (customCycle) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(10.dp))
                        OutlinedTextField(
                            value = cycleCount,
                            onValueChange = { cycleCount = it.filter(Char::isDigit).take(3) },
                            singleLine = true,
                            isError = !countValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(96.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // Its own line, not alongside the number: "Every [12]" plus
                    // four unit chips does not fit the width of a phone, and the
                    // ones past the edge cannot be pressed.
                    ChipRow(CYCLE_UNITS, cycleUnit) { cycleUnit = it }
                }

                // Always on screen, preset or custom. It is the one line that
                // says what the form will actually do, in the words a person
                // would use — "every 4 weeks" is thirteen payments a year, not
                // twelve, and that is worth reading back to them.
                cycleCount.toIntOrNull()?.takeIf { it >= 1 }?.let { count ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Bills ${Money.describeCycle(cycleUnit, count)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item {
            Column {
                Label("Currency")
                ChipRow(Money.KNOWN_CURRENCIES, currency) { picked ->
                    // Restated, not left as typed. "18.00" is not a legal number
                    // of yen, so switching to JPY turned the amount red and
                    // disabled Save with nothing on screen explaining that the
                    // pence were the problem.
                    amountText = restateAmount(amountText, currency, picked)
                    currency = picked
                }
                if (currency != state.homeCurrency) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.rates.knows(currency)) {
                            "Shown in $currency. Totals convert to ${state.homeCurrency} " +
                                "using the rate you set under Devices."
                        } else {
                            "No ${state.homeCurrency} rate set for $currency yet — until you set " +
                                "one under Devices, totals will add it up as though it were " +
                                "${state.homeCurrency}."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.rates.knows(currency)) mutedColour()
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        item {
            Column {
                DateField(
                    value = anchorDate,
                    onChange = { anchorDate = it },
                    label = "First billing date",
                )
                Text(
                    "Every future date is worked out from this one, so a 31st anchor still " +
                        "bills on the 28th in February and returns to the 31st in March.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColour(),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                )
            }
        }

        item {
            Column {
                Label("Category")
                ChipRow(CATEGORIES, category) { category = it }
            }
        }

        item {
            Column {
                Label("Appearance")

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name.ifBlank { "?" }, effectiveColour, category, size = 52, icon = icon)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                icon.isNotBlank() -> "Using a logo"
                                colourPickedManually -> "Your colour"
                                BrandColours.isKnownBrand(name) -> "Matched to ${name.trim()}"
                                else -> "Colour picked automatically"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedColour(),
                        )
                        LogoButtons(
                            hasLogo = icon.isNotBlank(),
                            canFetch = name.isNotBlank() && !fetchingLogo,
                            fetching = fetchingLogo,
                            onFind = {
                                scope.launch {
                                    fetchingLogo = true
                                    logoMessage = null
                                    logoMessage = when (val found = LogoFetcher().fetch(name, name)) {
                                        is LogoFetcher.Result.Found -> {
                                            icon = found.encoded
                                            null
                                        }
                                        LogoFetcher.Result.NoDomain ->
                                            "Type the service's name first."
                                        LogoFetcher.Result.NotFound ->
                                            "No logo found for that name — try choosing an image."
                                        is LogoFetcher.Result.Failed -> found.message
                                    }
                                    fetchingLogo = false
                                }
                            },
                            onChoose = pickImage,
                            onRemove = { icon = ""; logoMessage = null },
                        )
                    }
                }

                logoMessage?.let { message ->
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = MaterialTheme.typography.bodySmall, color = mutedColour())
                }

                Spacer(Modifier.height(12.dp))
                Swatches(selected = effectiveColour) {
                    colour = it
                    colourPickedManually = true
                }
                if (icon.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The colour still marks this subscription in the history chart, " +
                            "where a logo would be too small to tell apart.",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColour(),
                    )
                }
            }
        }

        item {
            Column {
                Label("Status")
                ChipRow(STATUSES, status) { status = it }
            }
        }

        item {
            Column {
                Label("Reminder")
                ReminderModeRow(
                    mode = notifyMode,
                    onChange = { notifyMode = it },
                    inheritedSummary = if (state.remindersEnabled) {
                        "Follows this device: ${state.reminderRule.describe().lowercase()}."
                    } else {
                        "Reminders are off on this device, so nothing will be sent."
                    },
                )
                if (notifyMode == Reminders.MODE_CUSTOM) {
                    Spacer(Modifier.height(12.dp))
                    ReminderRuleEditor(
                        rule = ReminderRule.of(notifyDays, notifyMinute),
                        onChange = { notifyDays = it.daysBefore; notifyMinute = it.minute },
                    )
                }
            }
        }

        if (status == "cancelled") {
            item {
                DateField(
                    value = endDate,
                    onChange = { endDate = it },
                    label = "Cancelled from",
                    // "on or after" was wrong and would cost the user a
                    // duplicate charge: the engine treats this as the last
                    // date a subscription can still bill on, so a charge
                    // falling exactly on it is generated.
                    supportingText = "The last date this can still bill on.",
                )
            }
        }

        item {
            OutlinedTextField(
                value = paymentMethod,
                onValueChange = { paymentMethod = it },
                label = { Text("Paid with (optional)") },
                placeholder = { Text("Amex") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Button(
                onClick = {
                    val subscription = (existing ?: SubscriptionRecord(id = "", updatedAt = "")).copy(
                        name = name.trim(),
                        vendor = (existing?.vendor ?: name).trim().lowercase(),
                        category = category,
                        currency = currency,
                        cycleUnit = cycleUnit,
                        cycleCount = cycleCount.toIntOrNull() ?: 1,
                        anchorDate = anchorDate.trim(),
                        status = status,
                        endDate = if (status == "cancelled") endDate.trim() else "",
                        paymentMethod = paymentMethod.trim(),
                        // Resolved rather than left blank: the stored colour is
                        // what the other devices will see, and a blank would let
                        // each of them re-derive its own.
                        colour = effectiveColour,
                        icon = icon,
                        notifyMode = notifyMode,
                        notifyDaysBefore = notifyDays,
                        notifyMinute = notifyMinute,
                    )
                    if (existing == null) {
                        state.saveNew(subscription, amountMinor ?: 0)
                    } else {
                        // Only when it actually moved, so an unrelated edit does
                        // not restamp the price period for no reason.
                        val changed = amountMinor?.takeIf { it != currentPrice?.amountMinor }
                        state.saveExisting(subscription, changed)
                    }
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (existing == null) "Add subscription" else "Save changes")
            }
        }

        if (existing != null) {
            item {
                Text(
                    "To record a price change, add a new price from the date it took effect — " +
                        "editing here won't rewrite what you already paid.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * A section heading, including the gap beneath it. The gap lived at each call
 * site and had drifted — 6dp here, 10dp there, nothing at all above Category and
 * Status — so identical-looking sections sat at visibly different heights.
 */
@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
}

/**
 * FlowRow so that three buttons plus a long label still fit a narrow phone —
 * in a plain Row the last one is pushed off the edge and cannot be pressed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogoButtons(
    hasLogo: Boolean,
    canFetch: Boolean,
    fetching: Boolean,
    onFind: () -> Unit,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onFind, enabled = canFetch) {
            Text(if (fetching) "Looking…" else "Find logo")
        }
        TextButton(onClick = onChoose) { Text("Choose image") }
        if (hasLogo) {
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

/**
 * The colour palette.
 *
 * Selection is compared case-insensitively because a colour can arrive here
 * three ways — picked from this row, derived from the brand table, or synced
 * from another device — and nothing guarantees they agree on hex casing. Without
 * that, a synced subscription shows no swatch selected at all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(selected: String, onSelect: (String) -> Unit) {
    val normalisedSelection = selected.removePrefix("#").lowercase()

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        BrandColours.PALETTE.forEach { hex ->
            val colour = parseColour(hex, MaterialTheme.colorScheme.primary)
            val isSelected = hex.removePrefix("#").lowercase() == normalisedSelection

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colour)
                    .border(
                        width = if (isSelected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                        shape = CircleShape,
                    )
                    .clickable { onSelect(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.Black.copy(alpha = 0.75f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

internal data class CyclePreset(val label: String, val unit: String, val count: Int) {
    fun matches(unit: String, count: Int) = unit == this.unit && count == this.count
}

/**
 * The four cycles nearly every subscription actually uses.
 *
 * This was eleven chips — fortnightly, every 4 weeks, every 2 months, every 18
 * months, every 3 years and so on. Every one of them was already a number and a
 * unit away, so the row was two lines of reading that mostly restated the
 * controls underneath it, and the genuinely odd cycles still were not covered.
 * Four chips and a "Custom" that opens the general case is the same reach with
 * a fraction of the surface.
 */
internal val CYCLE_PRESETS = listOf(
    CyclePreset("Weekly", "week", 1),
    CyclePreset("Monthly", "month", 1),
    CyclePreset("Quarterly", "month", 3),
    CyclePreset("Yearly", "year", 1),
)

/**
 * "Custom" stays selected while its controls are open even if the number and
 * unit happen to spell a preset, so exactly one chip is ever lit — a form where
 * both "Monthly" and "Custom" look active leaves the user unsure which one the
 * save button will believe.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CyclePresets(
    unit: String,
    count: Int,
    customOpen: Boolean,
    onPick: (String, Int) -> Unit,
    onCustom: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CYCLE_PRESETS.forEach { preset ->
            FilterChip(
                selected = !customOpen && preset.matches(unit, count),
                onClick = { onPick(preset.unit, preset.count) },
                label = { Text(preset.label) },
            )
        }
        FilterChip(
            selected = customOpen,
            onClick = onCustom,
            label = { Text("Custom") },
        )
    }
}

/**
 * FlowRow, not Row: six category chips overflow the width of a phone screen and
 * the ones past the edge become unreachable.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option.replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}

/**
 * The same amount written the way another currency writes it: 18.00 becomes 18
 * in yen, and 18 becomes 18.00 in pounds.
 *
 * Text that is not a number yet is handed back untouched, so switching currency
 * part-way through typing does not rewrite what the user is still entering.
 */
internal fun restateAmount(text: String, from: String, to: String): String {
    val fromDigits = Money.minorDigits(from)
    val toDigits = Money.minorDigits(to)
    if (fromDigits == toDigits) return text
    val minor = Money.parseOrNull(text, from) ?: return text
    // Currencies here have either 0 or 2 minor digits, so this is the whole
    // conversion. Rounded away from zero, matching how the amount reads aloud.
    val rescaled = if (fromDigits > toDigits) {
        val magnitude = (abs(minor) + 50) / 100
        if (minor < 0) -magnitude else magnitude
    } else {
        minor * 100
    }
    return Money.formatBare(rescaled, to)
}
