package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable
import javax.swing.SwingUtilities

@Composable
actual fun rememberCsvSaver(
    onSaved: (Result<Unit>) -> Unit,
): (fileName: String, content: String) -> Unit = { fileName, content ->
    SwingUtilities.invokeLater {
        chooseFileToSave("Save backup", fileName, "csv")?.let { target ->
            onSaved(runCatching { target.writeText(content) })
        }
    }
}

@Composable
actual fun rememberCsvOpener(onOpened: (Result<String>) -> Unit): () -> Unit = {
    SwingUtilities.invokeLater {
        chooseFileToOpen("Import backup", listOf("csv"))?.let { file ->
            onOpened(runCatching { file.readText() })
        }
    }
}
