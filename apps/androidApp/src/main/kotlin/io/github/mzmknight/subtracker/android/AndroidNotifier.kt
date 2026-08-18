package io.github.mzmknight.subtracker.android

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.mzmknight.subtracker.R
import io.github.mzmknight.subtracker.core.Money
import io.github.mzmknight.subtracker.core.Reminder
import io.github.mzmknight.subtracker.data.Notifier

/**
 * Local notifications, scheduled by the device itself.
 *
 * One alarm at a time, not one per subscription: the alarm fires, posts whatever
 * is due, and books the next one. Android caps how many alarms an app may hold
 * and drops them on force-stop, so a self-renewing chain is both cheaper and
 * easier to make correct than a queue that has to be kept in sync with the data.
 */
class AndroidNotifier(private val context: Context) : Notifier {

    override val canNotify: Boolean
        get() = NotificationManagerCompat.from(context).areNotificationsEnabled()

    override fun requestPermission() {
        // Below 33 notifications need no permission; above it the prompt can only
        // be raised from an Activity, which MainActivity does on first launch.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val activity = context as? android.app.Activity ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE,
        )
    }

    override fun post(reminders: List<Reminder>, currency: String) {
        if (reminders.isEmpty() || !canNotify) return
        ensureChannel()

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Several renewals on the same morning become one notification with the
        // detail inside it. Six separate ones is precisely the clutter that makes
        // people turn reminders off.
        val notification = if (reminders.size == 1) {
            val only = reminders.single()
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(only.title())
                .setContentText(only.body())
                .build(open)
        } else {
            val total = reminders.sumOf { it.amountMinor }
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("${reminders.size} renewals coming up")
                .setContentText(
                    reminders.joinToString(", ") { it.name } + " · " + Money.format(total, currency),
                )
                .setStyle(
                    NotificationCompat.InboxStyle().also { style ->
                        reminders.take(6).forEach { style.addLine("${it.name} — ${it.body()}") }
                        style.setSummaryText(Money.format(total, currency))
                    }
                )
                .build(open)
        }

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun NotificationCompat.Builder.build(open: PendingIntent): Notification =
        // The app's own mark rather than the stock Android reminder bell, so a
        // renewal notice is identifiable in the status bar at a glance.
        setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    override fun scheduleWakeUp(epochMillis: Long?) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        alarms.cancel(pending)
        if (epochMillis == null) return

        // Inexact deliberately. An exact alarm needs SCHEDULE_EXACT_ALARM, which
        // Android asks users to justify and can revoke; a renewal reminder that
        // arrives within a few minutes of nine is entirely fine, and this variant
        // still fires in Doze.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, epochMillis, pending)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Renewal reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Tells you before a subscription renews." }
        )
    }

    companion object {
        const val CHANNEL_ID = "renewals"
        const val NOTIFICATION_ID = 1001
        private const val ALARM_REQUEST = 2001
        const val REQUEST_CODE = 3001
    }
}

/**
 * Wakes on the alarm, and again after a reboot — Android drops every scheduled
 * alarm on restart, so without the boot case reminders stop dead the first time
 * the phone is turned off and nobody finds out until a renewal is missed.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ReminderWork.runAndReschedule(context.applicationContext) { pending.finish() }
    }
}

/** Rebooking the alarm after a restart, which is the only thing that survives it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ReminderWork.runAndReschedule(context.applicationContext) { pending.finish() }
    }
}
