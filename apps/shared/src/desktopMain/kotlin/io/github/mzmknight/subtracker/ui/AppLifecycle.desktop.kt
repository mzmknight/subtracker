package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * The desktop has no lifecycle to observe, so window focus stands in for it:
 * coming back to SubTracker from another window is the same moment, and the
 * same reason to check whether the other device has moved on.
 */
@Composable
actual fun OnAppResumed(onResumed: () -> Unit) {
    val focused = LocalWindowInfo.current.isWindowFocused
    val callback by rememberUpdatedState(onResumed)

    LaunchedEffect(focused) {
        if (focused) callback()
    }
}
