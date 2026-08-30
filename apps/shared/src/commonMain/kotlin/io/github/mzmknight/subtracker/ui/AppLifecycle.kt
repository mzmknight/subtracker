package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable

/**
 * Returning to the app after it was in the background.
 *
 * The sync on open lives in a `LaunchedEffect(Unit)`, which runs once when the
 * composition is created and never again — and neither platform recreates the
 * composition when you come back to it. On the phone that means every return
 * from the background, and on the desktop a window left open for days, ran the
 * launch sync once and then never checked again.
 *
 * "Resumed" is the nearest each platform has to the same idea: Android's
 * ON_RESUME, and the desktop window regaining focus. Both fire once on the way
 * in as well, which is why the handler is throttled rather than assumed to be
 * rare.
 */
@Composable
expect fun OnAppResumed(onResumed: () -> Unit)
