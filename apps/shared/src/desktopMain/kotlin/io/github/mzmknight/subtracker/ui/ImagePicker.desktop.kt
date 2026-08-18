package io.github.mzmknight.subtracker.ui

import androidx.compose.runtime.Composable
import javax.swing.SwingUtilities

/**
 * invokeLater is not optional: the dialog blocks until the user chooses, and
 * calling it from Compose's own thread freezes the window it should appear over.
 */
@Composable
actual fun rememberImagePicker(onPicked: (ByteArray?) -> Unit): () -> Unit = {
    SwingUtilities.invokeLater {
        val file = chooseFileToOpen(
            title = "Choose a logo",
            extensions = listOf("png", "jpg", "jpeg", "gif", "bmp", "webp"),
        )
        onPicked(file?.let { runCatching { it.readBytes() }.getOrNull() })
    }
}
