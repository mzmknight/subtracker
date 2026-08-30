package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable

/**
 * No gesture on the desktop — the page header carries a refresh button instead,
 * so the content passes straight through untouched.
 */
@Composable
actual fun Refreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    content()
}

actual val pullToRefreshIsNative: Boolean = false
