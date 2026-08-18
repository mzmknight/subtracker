package io.github.mzmknight.subtracker.desktop

import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import io.github.mzmknight.subtracker.core.Money
import io.github.mzmknight.subtracker.core.Reminder
import io.github.mzmknight.subtracker.data.Notifier

/**
 * Desktop reminders, via the system tray.
 *
 * The honest limitation: this only fires while SubTracker is running. A desktop
 * app cannot wake itself at nine in the morning without installing a scheduled
 * task or a background service, and neither belongs in something that is meant
 * to be unzipped and run from a folder. The phone is the device that reliably
 * notifies; the desktop tells you what it missed the next time you open it.
 */
class DesktopNotifier : Notifier {

    private val tray: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) return@lazy null
        runCatching {
            // A one-pixel transparent image rather than no image: TrayIcon
            // requires one, and drawing a real icon would put a permanent
            // SubTracker item in the notification area for something that is
            // only ever used to show a balloon.
            val blank = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
            TrayIcon(blank, "SubTracker").apply {
                isImageAutoSize = true
                SystemTray.getSystemTray().add(this)
            }
        }.getOrNull()
    }

    override val canNotify: Boolean get() = SystemTray.isSupported()

    override fun requestPermission() = Unit

    override fun post(reminders: List<Reminder>, currency: String) {
        if (reminders.isEmpty()) return
        val icon = tray ?: return

        val (title, body) = if (reminders.size == 1) {
            val only = reminders.single()
            only.title() to only.body()
        } else {
            val total = reminders.sumOf { it.amountMinor }
            "${reminders.size} renewals coming up" to
                (reminders.joinToString(", ") { it.name } + " · " + Money.format(total, currency))
        }

        runCatching {
            icon.displayMessage(title, body, TrayIcon.MessageType.INFO)
            Toolkit.getDefaultToolkit().sync()
        }
    }

    /**
     * Nothing to schedule: the in-app timer handles it while the window is open,
     * and there is nothing to wake when it is not.
     */
    override fun scheduleWakeUp(epochMillis: Long?) = Unit
}
