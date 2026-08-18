package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable

/** The desktop has no system back button, so there is nothing to intercept. */
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
