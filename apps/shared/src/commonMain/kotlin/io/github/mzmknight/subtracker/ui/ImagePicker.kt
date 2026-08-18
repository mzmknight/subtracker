package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable

/**
 * Returns a function that opens the platform's own file/photo picker.
 *
 * [onPicked] receives the raw file bytes, or null if the user backed out. It may
 * arrive on a different thread — writing the result straight into Compose state
 * is fine, but touching anything thread-hostile is not.
 *
 * Shaped as a remembered launcher rather than a suspending call because that is
 * what Android's activity-result API requires: the launcher has to be registered
 * during composition, before anyone presses anything.
 */
@Composable
expect fun rememberImagePicker(onPicked: (ByteArray?) -> Unit): () -> Unit
