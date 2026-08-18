package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable

/**
 * Save text to a file the user chooses.
 *
 * The returned function takes the suggested name and the content. [onSaved] is
 * called with success or the failure; it is *not* called if the user backs out,
 * because cancelling is not an outcome anyone needs to be told about.
 *
 * Both platforms hand back a location the app has been granted access to for
 * this one file, so neither needs a storage permission.
 */
@Composable
expect fun rememberCsvSaver(onSaved: (Result<Unit>) -> Unit): (fileName: String, content: String) -> Unit

/** Read a file the user chooses. Not called if they back out. */
@Composable
expect fun rememberCsvOpener(onOpened: (Result<String>) -> Unit): () -> Unit
