package io.github.mzmknight.subtracker.ui

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.github.mzmknight.subtracker.core.Money
import io.github.mzmknight.subtracker.core.Rates
import io.github.mzmknight.subtracker.core.ReminderRule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import io.github.mzmknight.subtracker.data.describeAge
import io.github.mzmknight.subtracker.sync.PeerProtocol
import io.github.mzmknight.subtracker.sync.QrScannerView
import io.github.mzmknight.subtracker.sync.encodeQr
import io.github.mzmknight.subtracker.sync.isQrScanningSupported

@Composable
fun DevicesScreen(state: AppState) {
    // The scanner takes the whole screen while it is open — a camera preview in
    // a card is unusable.
    if (state.scannerOpen) {
        Box(modifier = Modifier.fillMaxSize()) {
            QrScannerView(
                onScanned = { state.pairFromLink(it) },
                onUnavailable = { state.reportScannerProblem(it) },
            )
            TextButton(
                onClick = { state.closeScanner() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            ) {
                Text("Cancel")
            }
        }
        return
    }

    // Restore overwrites what is already here and can resurrect deleted records,
    // so it asks first and says exactly what it found in the file.
    state.pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = { state.cancelRestore() },
            title = { Text("Restore from this file?") },
            text = {
                Text(
                    "It holds ${pending.summary}.\n\n" +
                        "These will replace what's on this device where they overlap, and " +
                        "anything you deleted will come back. Subscriptions added since the " +
                        "backup are left alone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { state.confirmRestore() }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { state.cancelRestore() }) { Text("Cancel") }
            },
        )
    }

    var name by remember { mutableStateOf(state.deviceName) }
    var manualHost by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf(PeerProtocol.DEFAULT_PORT.toString()) }
    var manualCode by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(state.serverAddress.ifBlank { "http://" }) }
    var serverEmail by remember { mutableStateOf("") }
    var serverPassword by remember { mutableStateOf("") }

    // Scanning is only worth doing while this screen is visible; leaving mDNS
    // running in the background is a needless battery and radio cost.
    DisposableEffect(Unit) {
        state.scanForPeers()
        onDispose { state.stopScanning() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SyncCard("This device") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Device name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { state.renameDevice(name) },
                        enabled = name.isNotBlank() && name != state.deviceName,
                    ) { Text("Save name") }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.serverPort > 0) {
                            "Listening on port ${state.serverPort}"
                        } else {
                            "Not listening"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColour(),
                    )
                }
            }
        }

        item {
            SyncCard(
                title = "Add a device",
                subtitle = "Show this code on one device and type it into the other. " +
                    "It works once and expires after three minutes.",
            ) {
                Spacer(Modifier.height(12.dp))
                val code = state.pairingCode
                if (code == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { state.showPairingCode() }) { Text("Show pairing code") }
                        if (isQrScanningSupported()) {
                            OutlinedButton(onClick = { state.openScanner() }) { Text("Scan a code") }
                        }
                    }
                } else {
                    val link = state.pairingLink()
                    if (link != null) {
                        // The QR carries the address as well as the code, which is
                        // the part that is tedious and error-prone to type.
                        val qr = remember(link) { encodeQr(link.encode(), 640) }
                        if (qr != null) {
                            Spacer(Modifier.height(4.dp))
                            Image(
                                bitmap = qr,
                                contentDescription = "Pairing QR code",
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(MaterialTheme.shapes.medium),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Scan this with the other device, or type the code below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedColour(),
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    Text(
                        code.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall,
                        fontSize = 44.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.pairingAddress?.let { "This device is at $it:${state.serverPort}" }
                            ?: "No network address found — is this device on wifi?",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColour(),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { state.hidePairingCode() }) { Text("Done") }
                }
            }
        }

        item {
            SyncCard(
                title = "Nearby",
                subtitle = when {
                    !state.discoverySupported -> "This device can't search the network. Use the address below."
                    state.discoveredPeers.isEmpty() -> "Looking for devices running SubTracker on this network…"
                    else -> "Tap a device to pair with it."
                },
            ) {
                if (state.discoveredPeers.isNotEmpty()) Spacer(Modifier.height(10.dp))
                state.discoveredPeers.forEach { peer ->
                    val known = state.knownPeers.firstOrNull { it.id == peer.deviceId }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(peer.deviceName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${peer.host}:${peer.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedColour(),
                            )
                        }
                        if (known != null) {
                            Button(
                                onClick = { state.syncWith(peer.host, peer.port, peer.deviceId) },
                                enabled = !state.busy,
                            ) { Text("Sync") }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    manualHost = peer.host
                                    manualPort = peer.port.toString()
                                },
                            ) { Text("Pair") }
                        }
                    }
                }
            }
        }

        item {
            SyncCard(
                title = "By address",
                subtitle = "Network discovery is blocked by plenty of routers and firewalls, " +
                    "so this always works: type the other device's address and its code.",
            ) {
                Spacer(Modifier.height(12.dp))
                Row {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        label = { Text("Address") },
                        placeholder = { Text("192.0.2.42") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = manualPort,
                        onValueChange = { manualPort = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(110.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = manualCode,
                    onValueChange = { manualCode = it.filter(Char::isDigit).take(6) },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        state.pairWith(
                            manualHost.trim(),
                            manualPort.toIntOrNull() ?: PeerProtocol.DEFAULT_PORT,
                            manualCode,
                        )
                        manualCode = ""
                    },
                    enabled = !state.busy && manualHost.isNotBlank() && manualCode.length == 6,
                ) { Text("Pair and sync") }
            }
        }

        item {
            SyncCard(
                title = "Server",
                subtitle = if (state.serverSignedIn) {
                    "Connected to ${state.serverAddress}. It's treated as another " +
                        "peer, so if it goes down you carry on and catch it up later."
                } else {
                    "Optional. A server is just a peer that's always on — handy when " +
                        "your devices are rarely awake at the same time."
                },
            ) {
                Spacer(Modifier.height(12.dp))
                if (state.serverSignedIn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { state.syncWithServer() }, enabled = !state.busy) {
                            Text("Sync now")
                        }
                        TextButton(onClick = { state.signOutOfServer() }) { Text("Sign out") }
                    }
                } else {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("Server address") },
                        placeholder = { Text("http://100.x.y.z:8090") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverEmail,
                        onValueChange = { serverEmail = it },
                        label = { Text("Email") },
                        singleLine = true,
                        // Puts @ on the primary keyboard and turns autocorrect
                        // off, which otherwise "fixes" the local part of an
                        // address at the dot and produces a silent sign-in failure.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverPassword,
                        onValueChange = { serverPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            state.signInToServer(serverUrl, serverEmail, serverPassword)
                            serverPassword = ""
                        },
                        enabled = !state.busy && serverUrl.length > 8 &&
                            serverEmail.isNotBlank() && serverPassword.isNotBlank(),
                    ) { Text("Connect and sync") }
                }
            }
        }

        if (state.knownPeers.isNotEmpty()) {
            item {
                SyncCard(
                    title = "Paired devices",
                    subtitle = "These sync automatically when you open the app. " +
                        "Tap Sync to fetch changes now.",
                ) {
                    Spacer(Modifier.height(8.dp))
                    state.knownPeers.forEach { peer ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    peer.name.ifBlank { "Unnamed device" },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    buildString {
                                        append("Last synced ")
                                        append(describeAge(peer.lastSyncAt))
                                        if (peer.hasAddress) {
                                            append(" · ")
                                            append(peer.host)
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = mutedColour(),
                                )
                            }
                            Button(
                                onClick = { state.syncWithKnownPeer(peer) },
                                enabled = !state.busy && peer.hasAddress,
                            ) { Text("Sync") }
                            TextButton(onClick = { state.forgetPeer(peer.id) }) {
                                Text("Forget", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = { state.syncAllKnownPeers() },
                        enabled = !state.busy && state.knownPeers.any { it.hasAddress },
                    ) { Text("Sync all") }
                }
            }
        }

        item {
            var rateFor by remember { mutableStateOf<String?>(null) }
            var rateText by remember { mutableStateOf("") }

            SyncCard(
                title = "Currency",
                subtitle = "Every subscription keeps its own currency. Totals are shown in one, " +
                    "converted using European Central Bank rates fetched automatically — or " +
                    "your own figure, which is never overwritten. Both the currency you total " +
                    "in and any rate you type follow you to your other devices.",
            ) {
                Spacer(Modifier.height(12.dp))
                Text("Show totals in", style = MaterialTheme.typography.labelMedium, color = mutedColour())
                Spacer(Modifier.height(6.dp))
                ChipRow(Money.KNOWN_CURRENCIES, state.homeCurrency) { state.changeHomeCurrency(it) }

                val needed = state.currenciesNeedingRates
                if (needed.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No rate for ${needed.joinToString(", ")} — until you set one, those " +
                            "subscriptions are added to totals as though they were already in " +
                            "${state.homeCurrency}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { state.refreshRates(force = true) },
                        enabled = !state.refreshingRates,
                    ) {
                        Text(if (state.refreshingRates) "Updating…" else "Update rates now")
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (state.rates.fetchedAt == 0L) {
                            "Not fetched yet"
                        } else {
                            "Updated ${describeAge(state.rates.fetchedAt)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColour(),
                    )
                }

                val inUse = (state.subscriptions.map { it.currency } + state.rates.known.keys)
                    .distinct().filter { it != state.homeCurrency }.sorted()

                if (inUse.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    inUse.forEach { code ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("1 $code =", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            val rate = state.rates.rateFor(code)
                            val pinned = state.rates.isManual(code)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (rate != null) {
                                        "${Rates.formatRate(rate)} ${state.homeCurrency}"
                                    } else {
                                        "not set"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (rate != null) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                )
                                if (rate != null) {
                                    Text(
                                        if (pinned) "yours" else "updated automatically",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = mutedColour(),
                                    )
                                }
                            }
                            if (pinned) {
                                TextButton(onClick = { state.clearRate(code) }) { Text("Use live") }
                            }
                            TextButton(onClick = {
                                rateFor = code
                                rateText = rate?.let { Rates.formatRate(it) }.orEmpty()
                            }) {
                                Text(if (pinned) "Change" else "Set your own")
                            }
                        }
                    }
                }
            }

            rateFor?.let { code ->
                AlertDialog(
                    onDismissRequest = { rateFor = null },
                    title = { Text("1 $code in ${state.homeCurrency}") },
                    text = {
                        Column {
                            OutlinedTextField(
                                value = rateText,
                                onValueChange = { rateText = it },
                                label = { Text("Rate") },
                                placeholder = { Text("0.79") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Your own rate is never overwritten by an update — remove it to " +
                                    "go back to the live one. One rate covers all history, so " +
                                    "changing it moves what past months appear to have cost. " +
                                    "Amounts on the subscription itself always stay in $code.",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedColour(),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = { state.setRate(code, rateText); rateFor = null },
                            enabled = Rates.parseRate(rateText) != null,
                        ) { Text("Save") }
                    },
                    dismissButton = {
                        TextButton(onClick = { rateFor = null }) { Text("Cancel") }
                    },
                )
            }
        }

        item {
            SyncCard(
                title = "Renewal reminders",
                subtitle = "Set here for everything; any subscription can have its own, or none. " +
                    "Whether this device reminds you at all is its own choice — your phone can " +
                    "nag without your laptop doing the same — but how far ahead, and at what " +
                    "time, follow you to your other devices.",
            ) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.remindersEnabled,
                        onCheckedChange = { state.toggleReminders(it) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (state.remindersEnabled) "On" else "Off",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (state.remindersEnabled) {
                    if (!state.canNotify) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This device is not allowing notifications yet — check its " +
                                "notification settings for SubTracker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    ReminderRuleEditor(
                        rule = state.reminderRule,
                        onChange = { state.updateReminderRule(it) },
                    )

                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { state.resetAllRemindersToGlobal() }) {
                        Text("Use this for every subscription")
                    }
                    Text(
                        "Subscriptions without their own setting already follow this. That " +
                            "button clears the ones that do have their own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColour(),
                    )

                    val next = state.upcomingReminders
                    if (next.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text("Next up", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        next.forEach { reminder ->
                            Text(
                                "${reminder.name} — ${reminder.fireDate.formatLong()} " +
                                    "at ${ReminderRule(0, reminder.fireMinute).formatTime()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = mutedColour(),
                            )
                        }
                    }
                }
            }
        }

        item {
            val saveCsv = rememberCsvSaver { state.backupSaved(it) }
            val importCsv = rememberCsvOpener { state.importBackup(it) }
            val restoreCsv = rememberCsvOpener { state.prepareRestore(it) }

            SyncCard(
                title = "Backup",
                subtitle = "One CSV holding every subscription, its full price history, any " +
                    "charges you adjusted, and your reminder and currency settings. It opens " +
                    "in a spreadsheet.",
            ) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { state.buildBackup { name, content -> saveCsv(name, content) } },
                        enabled = !state.busy && state.subscriptions.isNotEmpty(),
                    ) {
                        Text("Export")
                    }
                    OutlinedButton(onClick = importCsv, enabled = !state.busy) {
                        Text("Import")
                    }
                    TextButton(onClick = restoreCsv, enabled = !state.busy) {
                        Text("Restore")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Import merges: newer changes on this device win, so an old backup can't " +
                        "undo them — but it also can't bring back anything you deleted since. " +
                        "Restore makes the file win, which is the one that recovers a deletion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = mutedColour(),
                )

                // Logos are not in the file, so an import lands with none. This
                // runs itself afterwards; the button is for re-running it when
                // some failed, or after adding a few by hand.
                val search = state.logoSearch
                val missing = state.subscriptionsMissingLogos

                if (search != null) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { search.done.toFloat() / search.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Looking for logos — ${search.done} of ${search.total}" +
                            (if (search.found > 0) ", ${search.found} found." else "."),
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColour(),
                    )
                } else if (missing > 0) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { state.findMissingLogos() }, enabled = !state.busy) {
                        Text("Find logos for $missing without one")
                    }
                }

                if (state.importProblems.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Skipped rows",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Capped: a badly mangled file can produce hundreds of these,
                    // and a wall of them buries the rest of the screen.
                    state.importProblems.take(8).forEach { problem ->
                        Text(problem, style = MaterialTheme.typography.bodySmall, color = mutedColour())
                    }
                    if (state.importProblems.size > 8) {
                        Text(
                            "…and ${state.importProblems.size - 8} more.",
                            style = MaterialTheme.typography.bodySmall,
                            color = mutedColour(),
                        )
                    }
                    TextButton(onClick = { state.dismissImportProblems() }) { Text("Dismiss") }
                }

                if (state.subscriptions.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Nothing to export yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = mutedColour(),
                    )
                }
            }
        }

        item {
            SyncCard(
                title = "Your data lives here",
                subtitle = "Everything is stored on this device and works with no server. " +
                    "${state.subscriptions.size} subscription" +
                    (if (state.subscriptions.size == 1) "" else "s") + " tracked" +
                    (if (state.lastSyncedAt == 0L) {
                        ", never synced."
                    } else {
                        ", last synced ${describeAge(state.lastSyncedAt)}."
                    }),
            ) {}
        }
    }
}

@Composable
private fun SyncCard(
    title: String,
    subtitle: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = mutedColour())
            }
            content()
        }
    }
}
