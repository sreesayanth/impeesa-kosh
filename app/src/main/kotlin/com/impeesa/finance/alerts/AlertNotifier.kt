package com.impeesa.finance.alerts

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.impeesa.finance.MainActivity
import com.impeesa.finance.R
import com.impeesa.finance.capture.CaptureNotifier
import com.impeesa.finance.core.model.alerts.AlertCandidate
import com.impeesa.finance.core.model.alerts.AlertKind

/**
 * One notification channel per kind of alert, so people can tune them in system settings too
 * (IMPLEMENTATION_PLAN.md §5.10). Everything is local.
 */
class AlertNotifier(private val context: Context, private val capture: CaptureNotifier) {

    private data class ChannelSpec(val id: String, val name: String, val description: String, val importance: Int)

    private fun channelFor(kind: AlertKind): ChannelSpec = when (kind) {
        AlertKind.CARD_DUE -> ChannelSpec("bills", "Card bills", "Reminders before a card bill is due", NotificationManager.IMPORTANCE_HIGH)
        AlertKind.BUDGET -> ChannelSpec("budgets", "Budgets", "When a budget is nearly or fully used", NotificationManager.IMPORTANCE_DEFAULT)
        AlertKind.REVIEW -> ChannelSpec("review", "Needs review", "When a transaction needs you to confirm it", NotificationManager.IMPORTANCE_DEFAULT)
        AlertKind.LARGE_TRANSACTION, AlertKind.UNUSUAL_DAY -> ChannelSpec("activity", "Unusual activity", "Large payments and unusual spending days", NotificationManager.IMPORTANCE_DEFAULT)
        AlertKind.SUBSCRIPTION -> ChannelSpec("subscriptions", "Subscriptions", "Renewals and price changes", NotificationManager.IMPORTANCE_DEFAULT)
        AlertKind.NEW_ACCOUNT, AlertKind.LOW_BALANCE, AlertKind.BALANCE_SYNC -> ChannelSpec("accounts", "Accounts", "New accounts found, low and updated balances", NotificationManager.IMPORTANCE_DEFAULT)
        AlertKind.WEEKLY_SUMMARY -> ChannelSpec("summary", "Weekly summary", "How last week went, on Monday mornings", NotificationManager.IMPORTANCE_LOW)
        AlertKind.CAPTURE_HEALTH -> ChannelSpec("health", "Auto-capture health", "When your phone stops Kosh reading payment alerts", NotificationManager.IMPORTANCE_DEFAULT)
    }

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        AlertKind.entries.map(::channelFor).distinctBy { it.id }.forEach { spec ->
            manager.createNotificationChannel(NotificationChannel(spec.id, spec.name, spec.importance).apply { description = spec.description })
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun show(alert: AlertCandidate) {
        // The review reminder is one updating notification, shared with capture.
        if (alert.kind == AlertKind.REVIEW) {
            capture.refreshReviewReminder()
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val id = alert.dedupeKey.hashCode()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, alert.link)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, channelFor(alert.kind).id)
            .setSmallIcon(R.drawable.ic_stat_impeesa)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.body))
            .setContentIntent(PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }
}
