package io.github.mzmknight.subtracker.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The Storage Access Framework, so no storage permission is needed and the file
 * can land anywhere the user can reach — including Drive or a USB stick.
 *
 * CreateDocument takes the name up front and returns the destination later, so
 * the content has to wait somewhere in between; that is what [pending] is for.
 */
@Composable
actual fun rememberCsvSaver(
    onSaved: (Result<Unit>) -> Unit,
): (fileName: String, content: String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val content = pending
        pending = null
        if (uri == null || content == null) return@rememberLauncherForActivityResult

        onSaved(
            runCatching {
                val stream = context.contentResolver.openOutputStream(uri)
                    ?: error("Couldn't open that location for writing.")
                stream.use { it.write(content.encodeToByteArray()) }
            }
        )
    }

    return { fileName, content ->
        pending = content
        launcher.launch(fileName)
    }
}

@Composable
actual fun rememberCsvOpener(onOpened: (Result<String>) -> Unit): () -> Unit {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        onOpened(
            runCatching {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: error("Couldn't open that file.")
                stream.use { it.readBytes().decodeToString() }
            }
        )
    }

    // Anything, rather than "text/csv": the MIME type a CSV is filed under
    // varies by where it came from, and a filter that is too tight hides the
    // user's own backup from them in the picker.
    return { launcher.launch(arrayOf("*/*")) }
}
