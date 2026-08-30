package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable

/**
 * Wraps a screen in whatever "check for changes now" gesture the platform has.
 *
 * Pulling a list down to refresh it is the phone's idiom and nothing else is,
 * but a mouse has no such gesture — dragging a desktop window's content downward
 * means nothing to anyone. So the desktop renders the content plain and gets a
 * button in the page header instead, which is the same instruction in the form
 * that platform actually uses.
 */
@Composable
expect fun Refreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
)

/**
 * Whether [Refreshable] provides the gesture itself.
 *
 * False means the platform needs a visible control instead, and the page header
 * supplies one — without this the desktop would have no way to ask at all.
 */
expect val pullToRefreshIsNative: Boolean
