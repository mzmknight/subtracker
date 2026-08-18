package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable

/**
 * System back navigation.
 *
 * Android has a hardware/gesture back that users expect to move *within* the
 * app; without handling it, pressing back on a detail or edit screen closes the
 * whole app instead of returning to the list. The desktop has no equivalent, so
 * its implementation does nothing.
 *
 * Found by driving the real app on an emulator — no unit test would have caught
 * it, because the bug is the absence of a handler rather than a wrong one.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
