package com.impeesa.finance.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.impeesa.finance.ImpeesaApp
import com.impeesa.finance.core.data.capture.ImportProgress
import java.time.Duration
import java.util.concurrent.TimeUnit

object SmsPermission {
    fun granted(context: Context): Boolean =
        listOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS).all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
}

/** Imports past bank messages after onboarding or a rescan. Idempotent, cancellable, reports progress. */
class HistoryImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SmsPermission.granted(applicationContext)) return Result.failure()
        val months = inputData.getInt(KEY_MONTHS, 6).toLong()
        val since = System.currentTimeMillis() - Duration.ofDays(months * 30).toMillis()
        val container = (applicationContext as ImpeesaApp).container
        val final = container.captureRepository.importInbox(since) { progress -> setProgress(progress.toData()) }
        return Result.success(final.toData())
    }

    companion object {
        const val UNIQUE_NAME = "history-import"
        private const val KEY_MONTHS = "months"

        fun start(context: Context, months: Int) {
            val request = OneTimeWorkRequestBuilder<HistoryImportWorker>()
                .setInputData(workDataOf(KEY_MONTHS to months))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Picks up SMS that arrived while the app wasn't running. Runs on app start and once a day. */
class InboxReconcileWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SmsPermission.granted(applicationContext)) return Result.success()
        val container = (applicationContext as ImpeesaApp).container
        container.captureRepository.reconcileInbox()
        container.notifier.refreshReviewReminder()
        return Result.success()
    }

    companion object {
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "reconcile-now",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<InboxReconcileWorker>().build(),
            )
        }

        fun scheduleDaily(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "reconcile-daily",
                ExistingPeriodicWorkPolicy.KEEP,
                // The first run waits a day: app start already catches up, and an immediate run
                // would race the history import during setup.
                PeriodicWorkRequestBuilder<InboxReconcileWorker>(24, TimeUnit.HOURS).setInitialDelay(24, TimeUnit.HOURS).build(),
            )
        }
    }
}

object ProgressKeys {
    const val READ = "read"
    const val TOTAL = "total"
    const val LOGGED = "logged"
    const val REVIEW = "review"
    const val SKIPPED = "skipped"
    const val ACCOUNTS = "accounts"
}

private fun ImportProgress.toData(): Data = workDataOf(
    ProgressKeys.READ to read,
    ProgressKeys.TOTAL to total,
    ProgressKeys.LOGGED to logged,
    ProgressKeys.REVIEW to review,
    ProgressKeys.SKIPPED to skipped,
    ProgressKeys.ACCOUNTS to accounts,
)

fun Data.toProgress(done: Boolean) = ImportProgress(
    read = getInt(ProgressKeys.READ, 0),
    total = getInt(ProgressKeys.TOTAL, 0),
    logged = getInt(ProgressKeys.LOGGED, 0),
    review = getInt(ProgressKeys.REVIEW, 0),
    skipped = getInt(ProgressKeys.SKIPPED, 0),
    accounts = getInt(ProgressKeys.ACCOUNTS, 0),
    done = done,
)
