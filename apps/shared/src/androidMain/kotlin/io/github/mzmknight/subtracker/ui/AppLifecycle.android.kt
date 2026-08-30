package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
actual fun OnAppResumed(onResumed: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    // Kept up to date so the observer, which outlives a recomposition, never
    // calls a stale lambda closed over an old AppState.
    val callback by rememberUpdatedState(onResumed)

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) callback()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}
