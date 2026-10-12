package com.impeesa.finance.capture

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
import com.impeesa.finance.core.data.ReviewRepository
import com.impeesa.finance.core.data.capture.IngestResult
import com.impeesa.finance.core.model.Direction
import com.impeesa.finance.core.model.MoneyFormat

/**
 * Local notifications for capture: a quiet "Logged ₹486 · Swiggy" per auto-logged transaction and one
 * updating reminder for the review queue (IMPLEMENTATION_PLAN.md §5.10). Nothing is sent anywhere.
 */
class CaptureNotifier(private val context: Context, private val review: () -> ReviewRepository) {

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LOGGED, "Logged transactions", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet note each time Impeesa logs a transaction for you"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_REVIEW, "Needs review", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a transaction needs you to confirm it"
            },
        )
    }

    suspend fun onCaptured(result: IngestResult) {
        when (result) {
            is IngestResult.Logged -> if (result.needsReview) refreshReviewReminder() else showLogged(result)
            // A second source can confirm an item that was waiting for review.
            is IngestResult.Merged -> refreshReviewReminder()
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun showLogged(result: IngestResult.Logged) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val amount = MoneyFormat.format(if (result.direction == Direction.DEBIT) -result.amount else result.amount, signed = true)
        val title = listOfNotNull(amount, result.title).joinToString(" · ")
        val notification = NotificationCompat.Builder(context, CHANNEL_LOGGED)
            .setSmallIcon(R.drawable.ic_stat_impeesa)
            .setContentTitle("Logged $title")
            .setContentText("Tap to check or change the category")
            .setContentIntent(openIntent("txn:${result.transactionId}", result.transactionId.toInt()))
            .setAutoCancel(true)
            .setGroup(GROUP_LOGGED)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(result.transactionId.toInt(), notification)
    }

    @SuppressLint("MissingPermission")
    suspend fun refreshReviewReminder() {
        val manager = NotificationManagerCompat.from(context)
        val count = review().count()
        if (count == 0) {
            manager.cancel(REVIEW_ID)
            return
        }
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_REVIEW)
            .setSmallIcon(R.drawable.ic_stat_impeesa)
            .setContentTitle(if (count == 1) "1 transaction to review" else "$count transactions to review")
            .setContentText("Impeesa wasn't sure about these. Confirm them in a tap.")
            .setContentIntent(openIntent("review", REVIEW_ID))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        manager.notify(REVIEW_ID, notification)
    }

    private fun openIntent(target: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, target)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        const val CHANNEL_LOGGED = "logged"
        const val CHANNEL_REVIEW = "review"
        const val GROUP_LOGGED = "logged-group"
        const val REVIEW_ID = 1
    }
}
