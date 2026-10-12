package com.impeesa.finance.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.impeesa.finance.AppContainer
import com.impeesa.finance.ImpeesaApp
import com.impeesa.finance.capture.CaptureHealth
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.EntryKind
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.alerts.AlertInputs
import com.impeesa.finance.core.model.alerts.AlertRules
import com.impeesa.finance.core.model.alerts.BalanceSyncInput
import com.impeesa.finance.core.model.alerts.BigDayInput
import com.impeesa.finance.core.model.alerts.BudgetInput
import com.impeesa.finance.core.model.alerts.CardDueInput
import com.impeesa.finance.core.model.alerts.LargeTransactionInput
import com.impeesa.finance.core.model.alerts.LowBalanceInput
import com.impeesa.finance.core.model.alerts.NewAccountInput
import com.impeesa.finance.core.model.alerts.RenewalInput
import com.impeesa.finance.core.model.alerts.WeeklyInput
import com.impeesa.finance.core.model.analytics.CardCycle
import com.impeesa.finance.core.model.analytics.Flows
import com.impeesa.finance.core.model.analytics.InsightKind
import com.impeesa.finance.core.model.analytics.Periods
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Gathers today's facts, asks [AlertRules] what is due and posts what is new (IMPLEMENTATION_PLAN.md §5.10).
 * Safe to run any number of times: the alerts table refuses a dedupe key it has seen.
 */
class AlertEngine(private val context: Context, private val container: AppContainer) {
    private val running = Mutex()

    suspend fun run(capturedTransactionId: Long? = null) = running.withLock {
        val clock = container.clock
        val today = LocalDate.now(clock)
        val ledger = container.ledgerRepository
        val accounts = container.accountRepository.observeAccounts().first()
        val overview = container.moneyOverview.budgets(today, flowOf(container.prefs.monthStartDay.value)).first()

        val statements = ledger.latestStatements().associateBy { it.accountId }
        val cards = accounts.filter { it.type == AccountType.CARD }.mapNotNull { card ->
            val statement = statements[card.id]
            if (statement != null && !statement.dueDate.isBefore(today)) {
                CardDueInput(card.id, card.name, statement.dueDate, statement.remaining ?: statement.totalDue, statement.minDue, statement.isPaid)
            } else {
                // No bill message yet: use the due day the user set and what Kosh thinks is owed.
                val dueDay = ledger.observeSettings(card.id).first()?.dueDay ?: return@mapNotNull null
                CardDueInput(card.id, card.name, CardCycle.nextDueDate(today, dueDay), card.owed.takeIf { it.paise > 0 }, null, paid = card.owed.paise <= 0)
            }
        }

        val budgets = listOfNotNull(overview.overall) + overview.categories
        val large = capturedTransactionId?.let { id ->
            val detail = container.transactionRepository.observeDetail(id).first() ?: return@let null
            if (detail.pendingReview || detail.kind != EntryKind.EXPENSE) return@let null
            LargeTransactionInput(id, detail.counterparty ?: detail.note ?: "Payment", detail.amount, detail.accountId?.let { ledger.medianDebit(it) })
        }

        val weekly = if (today.dayOfWeek == java.time.DayOfWeek.MONDAY) {
            val last = Periods.week(today, -1)
            val before = Periods.week(today, -2)
            val days = container.analyticsRepository.observeDaily(before.start, last.end).first()
            WeeklyInput(Flows.spending(days, last), Flows.spending(days, before))
        } else {
            null
        }

        val bigDay = container.moneyOverview.insights(today, flowOf(overview), flowOf(emptyMap())) { clock.millis() }.first()
            .firstOrNull { it.kind == InsightKind.BIG_DAY }

        val names = accounts.associate { it.id to it.name }
        val inputs = AlertInputs(
            today = today,
            cards = cards,
            budgets = budgets.map { BudgetInput(it.budget.id, it.name, overview.period.start, it.status.spent, it.status.available, it.budget.alertPercent) },
            renewals = overview.recurring.series.filter { !it.isCredit }.map { RenewalInput(it.key, it.label, it.nextDate, it.amount, it.priceChange, it.lastDate) },
            reviewCount = container.reviewRepository.count(),
            largeTransactions = listOfNotNull(large),
            lowBalances = accounts.mapNotNull { a -> a.lowBalance?.let { LowBalanceInput(a.id, a.name, a.balance, it) } },
            newAccounts = container.accountRepository.observeSuggestions().first().map { NewAccountInput(it.id, "${it.institution} ••${it.mask}", it.sightings) },
            balanceSyncs = ledger.recentBalanceDifferences(clock.millis() - Duration.ofDays(2).toMillis(), Money(500_00))
                .map { BalanceSyncInput(it.snapshotId, it.accountId, names[it.accountId] ?: "Your account", it.difference) },
            bigDay = bigDay?.let { BigDayInput(it.key, it.text) },
            weekly = weekly,
            captureHealthy = !CaptureHealth.listenerPaused(context),
        )

        AlertRules.evaluate(inputs, container.prefs.enabledAlerts.value).forEach { alert ->
            if (ledger.postAlert(alert.kind.name, alert.dedupeKey, alert.title, alert.body, alert.link)) {
                container.alertNotifier.show(alert)
            }
        }
        ledger.purgeOldAlerts()
    }
}

/** Runs the alert engine: daily around 9 AM, at app start and after each capture. */
class AlertsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ImpeesaApp).container
        val txn = inputData.getLong(KEY_TXN, 0L).takeIf { it > 0 }
        runCatching { container.alertEngine.run(txn) }
        return Result.success()
    }

    companion object {
        private const val KEY_TXN = "txn"

        fun scheduleDaily(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(9, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<AlertsWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("alerts-daily", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** After a capture: budgets and large payments shouldn't wait for tomorrow. */
        fun runNow(context: Context, transactionId: Long? = null) {
            val request = OneTimeWorkRequestBuilder<AlertsWorker>().setInputData(workDataOf(KEY_TXN to (transactionId ?: 0L))).build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (transactionId != null) "alerts-txn-$transactionId" else "alerts-now",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
