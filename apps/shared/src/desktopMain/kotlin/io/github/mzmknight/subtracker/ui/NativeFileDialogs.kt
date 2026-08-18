package io.github.mzmknight.subtracker.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * File dialogs that look like the operating system's, not like Java's.
 *
 * `JFileChooser` is drawn by Swing itself, so on Windows it renders as a
 * Windows-2000-era box with none of the sidebar, search, or pinned folders
 * people navigate by. `java.awt.FileDialog` calls the platform's own dialog
 * instead — the real Explorer one on Windows, the real Finder one on macOS —
 * and gets overwrite warnings and shell folders for free.
 *
 * The exception is Linux, where AWT has no native dialog to call and falls back
 * to something markedly worse than Swing's. There, `JFileChooser` wins.
 */
private val useNativeDialog: Boolean = System.getProperty("os.name").orEmpty().let { os ->
    os.startsWith("Windows", ignoreCase = true) || os.startsWith("Mac", ignoreCase = true)
}

/** Must be called on the event dispatch thread; both dialogs block until dismissed. */
internal fun chooseFileToOpen(title: String, extensions: List<String>): File? =
    if (useNativeDialog) {
        FileDialog(null as Frame?, title, FileDialog.LOAD).run {
            // Windows ignores setFilenameFilter entirely and filters on this
            // instead; every other platform ignores this and uses the filter.
            file = extensions.joinToString(";") { "*.$it" }
            filenameFilter = FilenameFilter { _, name ->
                extensions.any { name.endsWith(".$it", ignoreCase = true) }
            }
            isVisible = true
            directory?.let { dir -> this.file?.let { chosen -> File(dir, chosen) } }
        }
    } else {
        JFileChooser().run {
            dialogTitle = title
            fileFilter = FileNameExtensionFilter(
                extensions.joinToString("/") { it.uppercase() }, *extensions.toTypedArray(),
            )
            if (showOpenDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile else null
        }
    }

/**
 * Returns the chosen path with [extension] guaranteed.
 *
 * Typing a name without an extension is the normal case in a save dialog, and a
 * file called "subtracker-2026-08-10" does not open in a spreadsheet by
 * double-click.
 */
internal fun chooseFileToSave(title: String, suggestedName: String, extension: String): File? {
    val chosen = if (useNativeDialog) {
        FileDialog(null as Frame?, title, FileDialog.SAVE).run {
            file = suggestedName
            isVisible = true
            directory?.let { dir -> this.file?.let { name -> File(dir, name) } }
        }
    } else {
        JFileChooser().run {
            dialogTitle = title
            selectedFile = File(suggestedName)
            fileFilter = FileNameExtensionFilter(extension.uppercase(), extension)
            if (showSaveDialog(null) == JFileChooser.APPROVE_OPTION) selectedFile else null
        }
    } ?: return null

    return if (chosen.name.contains('.')) chosen else File(chosen.parentFile, "${chosen.name}.$extension")
}
