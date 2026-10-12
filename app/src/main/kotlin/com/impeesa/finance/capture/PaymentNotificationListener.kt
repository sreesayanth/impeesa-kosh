package com.impeesa.finance.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.impeesa.finance.ImpeesaApp
import com.impeesa.finance.core.engine.MessageEnvelope
import com.impeesa.finance.core.engine.MessageSource
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Reads payment-app notifications (GPay, PhonePe, Paytm…) — only from apps on the allowlist that
 * the user has left switched on. Everything else is ignored without being read further
 * (IMPLEMENTATION_PLAN.md §6.2).
 */
class PaymentNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        CaptureHealth.setListenerConnected(this, true)
    }

    override fun onListenerDisconnected() {
        CaptureHealth.setListenerConnected(this, false)
        // Some phones unbind listeners to save battery; ask to be bound again.
        requestRebind(ComponentName(this, PaymentNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val container = (applicationContext as ImpeesaApp).container
        if (!container.captureRepository.isWatched(sbn.packageName)) return

        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString(" ")
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: lines
            ?: return
        val envelope = MessageEnvelope(
            source = MessageSource.NOTIFICATION,
            sender = sbn.packageName,
            body = text,
            receivedAt = Instant.ofEpochMilli(sbn.postTime),
            title = title,
        )
        container.ioScope.launch {
            runCatching {
                val result = container.captureRepository.ingest(envelope)
                container.notifier.onCaptured(result)
            }
        }
    }
}

/** Whether capture can work right now, for the Home banner and the Auto-capture screen. */
object CaptureHealth {
    private const val PREFS = "capture_health"
    private const val KEY_CONNECTED = "listener_connected"

    fun notificationAccessGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun setListenerConnected(context: Context, connected: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONNECTED, connected).apply()
    }

    /** Access granted but the system has stopped the listener (common on aggressive battery savers). */
    fun listenerPaused(context: Context): Boolean =
        notificationAccessGranted(context) &&
            !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONNECTED, true)

    fun requestRebind(context: Context) {
        if (notificationAccessGranted(context)) {
            NotificationListenerService.requestRebind(ComponentName(context, PaymentNotificationListener::class.java))
        }
    }
}
