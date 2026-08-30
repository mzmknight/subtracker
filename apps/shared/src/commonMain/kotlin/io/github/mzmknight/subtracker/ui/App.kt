package io.github.mzmknight.subtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * Widest the content is ever allowed to get, so a 1900px monitor gets margins
 * rather than letterboxed cards.
 */
private val MaxContentWidth = 1080.dp

/** Above this, a side rail reads better than a bottom bar spread across the window. */
private val RailBreakpoint = 900.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val scope: CoroutineScope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    val snackbars = remember { SnackbarHostState() }

    // AppState reads the date itself at construction; this only catches a
    // rollover past midnight while the app stayed open.
    // Listening starts with the app so a peer can initiate a sync to us.
    LaunchedEffect(Unit) {
        state.setToday(systemToday())
        state.startSyncing()
        // Throttled inside — a launch does not mean a request. Reference rates
        // are published once a day, so anything more eager is pure noise.
        state.refreshRates()
    }

    /**
     * Reminders, checked while the app is open.
     *
     * On Android an alarm covers the app being closed; on the desktop there is
     * nothing to wake, so this loop is the only mechanism there — which is why
     * the settings screen says so plainly rather than implying otherwise.
     *
     * A minute's granularity: reminder times are set to the minute, and the work
     * is a projection over a handful of records.
     */
    LaunchedEffect(Unit) {
        while (true) {
            state.refreshReminders()
            delay(60_000)
        }
    }

    // Coming back to the app is when its numbers are most likely to be stale.
    // Throttled inside, and every other sweep resets that throttle, so the first
    // ON_RESUME after launch does not repeat the sync that just ran.
    OnAppResumed { state.syncOnResume() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbars.showSnackbar(it)
            state.dismissError()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbars.showSnackbar(it)
            state.dismissNotice()
        }
    }

    SubTrackerTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val screen = state.screen

            // Back moves within the app; only the top-level Dashboard lets the
            // system take it and close the app.
            PlatformBackHandler(enabled = screen !is Screen.Dashboard) {
                state.go(
                    when (screen) {
                        is Screen.Detail, is Screen.Edit -> Screen.Subscriptions
                        else -> Screen.Dashboard
                    }
                )
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useRail = maxWidth >= RailBreakpoint

                // The status bar, plus a display cutout in landscape. The window
                // is edge-to-edge on Android, so without this the page title
                // draws underneath the clock. Only the content is inset: the
                // navigation bar and rail already pad themselves, and letting
                // the Scaffold do it too would double the gap under them.
                val contentInsets = WindowInsets.safeDrawing.only(
                    if (useRail) WindowInsetsSides.Top + WindowInsetsSides.End
                    else WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )

                Scaffold(
                    // Shrinks the whole app when the keyboard opens. Without it
                    // the viewport keeps its full height, so Compose believes a
                    // focused field is already visible and never scrolls it up —
                    // the last fields on the edit form end up typed into blind.
                    modifier = Modifier.imePadding(),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    snackbarHost = { SnackbarHost(snackbars) },
                    bottomBar = {
                        if (!useRail) {
                            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                NavDestinations.forEach { destination ->
                                    NavigationBarItem(
                                        selected = destination.matches(screen),
                                        onClick = { state.go(destination.screen) },
                                        icon = { Icon(destination.icon, contentDescription = null) },
                                        label = { Text(destination.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                        if (useRail) {
                            NavigationRail(
                                containerColor = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxHeight(),
                            ) {
                                Spacer(Modifier.height(12.dp))
                                NavDestinations.forEach { destination ->
                                    NavigationRailItem(
                                        selected = destination.matches(screen),
                                        onClick = { state.go(destination.screen) },
                                        icon = { Icon(destination.icon, contentDescription = null) },
                                        label = { Text(destination.label) },
                                    )
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }

                        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(contentInsets)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .widthIn(max = MaxContentWidth)
                                    .align(Alignment.TopCenter),
                            ) {
                                PageHeader(state, screen)
                                Box(modifier = Modifier.fillMaxSize()) {
                                    // Only the three "what do I have" screens
                                    // pull to refresh. A form must not sync out
                                    // from under a half-typed edit, and Devices
                                    // has explicit controls of its own that say
                                    // what they do.
                                    if (screen.isRefreshable) {
                                        Refreshable(
                                            refreshing = state.refreshing,
                                            onRefresh = { state.refreshFromPeers() },
                                        ) {
                                            when (screen) {
                                                is Screen.Subscriptions -> SubscriptionsScreen(state)
                                                is Screen.History -> HistoryScreen(state)
                                                else -> DashboardScreen(state)
                                            }
                                        }
                                    } else {
                                        when (screen) {
                                            is Screen.Detail -> DetailScreen(state)
                                            is Screen.Edit -> EditScreen(state, screen.id)
                                            is Screen.Devices -> DevicesScreen(state)
                                            else -> Unit
                                        }
                                    }

                                    if (state.busy && state.figures == null) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.align(Alignment.Center),
                                        )
                                    }
                                }
                            }

                            if (state.busy && state.figures != null) {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHeader(state: AppState, screen: Screen) {
    val goingBack = screen is Screen.Detail || screen is Screen.Edit

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (goingBack) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = { state.go(Screen.Subscriptions) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(titleFor(screen), style = MaterialTheme.typography.headlineLarge)
            subtitleFor(state, screen)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = mutedColour())
            }
        }

        // Where there is no pull gesture, the same request needs a control.
        if (!pullToRefreshIsNative && screen.isRefreshable) {
            IconButton(
                onClick = { state.refreshFromPeers() },
                enabled = !state.refreshing,
            ) {
                if (state.refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Check other devices")
                }
            }
        }
    }
}

private class Destination(
    val label: String,
    val icon: ImageVector,
    val screen: Screen,
    val matches: (Screen) -> Boolean,
)

private val NavDestinations = listOf(
    Destination("Dashboard", Icons.Default.Dashboard, Screen.Dashboard) { it is Screen.Dashboard },
    // "Subs" rather than "Subscriptions": with four destinations the label has to
    // survive a narrow phone without wrapping or ellipsing to nothing.
    Destination("Subs", Icons.AutoMirrored.Filled.List, Screen.Subscriptions) {
        it is Screen.Subscriptions || it is Screen.Detail || it is Screen.Edit
    },
    Destination("History", Icons.Default.History, Screen.History) { it is Screen.History },
    Destination("Devices", Icons.Default.Devices, Screen.Devices) { it is Screen.Devices },
)

private fun titleFor(screen: Screen): String = when (screen) {
    is Screen.Dashboard -> "Dashboard"
    is Screen.Subscriptions -> "Subscriptions"
    is Screen.Detail -> "Details"
    is Screen.Edit -> if (screen.id == null) "New subscription" else "Edit subscription"
    is Screen.History -> "History"
    is Screen.Devices -> "Devices"
}

@Composable
private fun subtitleFor(state: AppState, screen: Screen): String? = when (screen) {
    is Screen.Dashboard -> state.today.formatLong()
    is Screen.Subscriptions -> {
        val live = state.subscriptions.count { it.isLive }
        if (state.subscriptions.isEmpty()) null else "$live active of ${state.subscriptions.size}"
    }
    is Screen.History -> {
        val recorded = state.history.count { !it.isFuture }
        if (recorded == 0) null else "$recorded month${if (recorded == 1) "" else "s"} recorded"
    }
    is Screen.Devices -> "This device is \"${state.deviceName}\""
    else -> null
}
