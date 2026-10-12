package com.impeesa.finance.core.data

import androidx.room.withTransaction
import com.impeesa.finance.core.data.db.BudgetEntity
import com.impeesa.finance.core.data.db.CategoryTotalRow
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.data.db.RecurringPrefEntity
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Direction
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.analytics.DayFlow
import com.impeesa.finance.core.model.backup.Csv
import com.impeesa.finance.core.model.analytics.Occurrence
import com.impeesa.finance.core.model.analytics.Period
import com.impeesa.finance.core.model.analytics.RecurringDetector
import com.impeesa.finance.core.model.analytics.RecurringSeries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Spending in one category over a period, refunds netted. [categoryId] is null for uncategorised. */
data class CategoryTotal(val categoryId: Long?, val name: String, val color: Int?, val total: Money, val count: Int)

data class MerchantTotal(val key: String, val label: String, val total: Money, val count: Int)

/** Detected subscriptions, bills and income, and the salary's usual day (a month start suggestion). */
data class RecurringSummary(val series: List<RecurringSeries>, val salaryDay: Int?) {
    val subscriptionsMonthly: Money get() = Money(series.filter { !it.isCredit }.sumOf { it.monthlyCost.paise })
}

/** Read-only summaries for Home, Insights and Habits (IMPLEMENTATION_PLAN.md §10). */
class AnalyticsRepository internal constructor(
    private val db: ImpeesaDatabase,
    private val clock: Clock,
) {
    private val dao = db.analyticsDao()

    fun observeDaily(from: LocalDate, to: LocalDate): Flow<List<DayFlow>> =
        dao.observeDailyFlows(DateKey.of(from), DateKey.of(to)).map { rows -> rows.map { DayFlow(it.date, it.spent, it.refunds, it.income) } }

    fun observeCategoryTotals(period: Period): Flow<List<CategoryTotal>> =
        dao.observeCategoryTotals(period.keys.first, period.keys.last).map { rows -> rows.toTotals() }

    suspend fun categoryTotals(period: Period): List<CategoryTotal> = dao.categoryTotals(period.keys.first, period.keys.last).toTotals()

    fun observeTopMerchants(period: Period, limit: Int = 5): Flow<List<MerchantTotal>> =
        dao.observeTopMerchants(period.keys.first, period.keys.last, limit).map { rows ->
            rows.map { MerchantTotal(it.payeeKey, it.label ?: it.payeeKey, Money(it.total), it.count) }
        }

    /** Recurring payments seen in the last 13 months, minus the ones the user said are not recurring. */
    fun observeRecurring(today: LocalDate): Flow<RecurringSummary> = combine(
        dao.observeOccurrences(DateKey.of(today.minusMonths(13))),
        dao.observeRecurringPrefs(),
    ) { rows, prefs ->
        val hidden = prefs.filter { it.status == HIDDEN }.map { it.key }.toSet()
        val occurrences = rows.map {
            Occurrence(it.payeeKey, it.label ?: it.payeeKey, Money(it.amountPaise), DateKey.toLocalDate(it.localDate), it.direction == Direction.CREDIT)
        }
        val series = RecurringDetector.detect(occurrences, today).filterNot { it.key in hidden }
        RecurringSummary(series, RecurringDetector.salaryDay(series, occurrences))
    }

    /** CSV lines (with header) for every confirmed transaction in [period]. Amounts are signed rupees. */
    suspend fun exportCsv(period: Period): String {
        val rows = dao.exportRows(period.keys.first, period.keys.last)
        val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        return buildString {
            appendLine(Csv.row(listOf("Date", "Account", "Merchant", "Category", "Amount", "Type", "Note", "Source")))
            rows.forEach { r ->
                val signed = if (r.direction == Direction.DEBIT) -r.amountPaise else r.amountPaise
                val rupees = (if (signed < 0) "-" else "") + "${kotlin.math.abs(signed) / 100}.${(kotlin.math.abs(signed) % 100).toString().padStart(2, '0')}"
                appendLine(
                    Csv.row(
                        listOf(
                            Instant.ofEpochMilli(r.occurredAt).atZone(clock.zone).format(formatter),
                            r.account, r.counterparty, r.category, rupees,
                            r.type.lowercase().replaceFirstChar { it.uppercase() }, r.note,
                            r.source.lowercase().replaceFirstChar { it.uppercase() },
                        ),
                    ),
                )
            }
        }
    }

    /** "Not a subscription": hides a detected series. */
    suspend fun hideRecurring(key: String) = dao.setRecurringPref(RecurringPrefEntity(key, HIDDEN, clock.millis()))

    suspend fun unhideRecurring(key: String) = dao.clearRecurringPref(key)

    /** Hour of day of each food order in [period], for the late-night pattern. */
    fun observeFoodOrderHours(period: Period): Flow<List<Int>> =
        dao.observeFoodOrderTimes(period.keys.first, period.keys.last).map { times ->
            times.map { Instant.ofEpochMilli(it).atZone(clock.zone).hour }
        }

    private fun List<CategoryTotalRow>.toTotals() = filter { it.total != 0L }.map {
        CategoryTotal(it.categoryId, it.name ?: "Uncategorised", it.color, Money(it.total), it.count)
    }.sortedByDescending { it.total }

    private companion object {
        const val HIDDEN = "HIDDEN"
    }
}

/** A budget as screens use it. [categoryId] null means the overall monthly budget. */
data class Budget(
    val id: Long = 0,
    val categoryId: Long?,
    val amount: Money,
    val alertPercent: Int = 80,
    val rollover: Boolean = false,
)

class BudgetRepository internal constructor(
    private val db: ImpeesaDatabase,
    private val clock: Clock,
) {
    private val dao = db.analyticsDao()

    fun observe(): Flow<List<Budget>> = dao.observeBudgets().map { rows -> rows.map { it.toModel() } }

    suspend fun get(id: Long): Budget? = dao.budget(id)?.toModel()

    /** Saves a budget; there is at most one per category (and one overall). */
    suspend fun save(budget: Budget): Long {
        require(budget.amount.paise > 0) { "Budget must be more than zero" }
        require(budget.alertPercent in 1..100) { "Warn between 1 and 100 %" }
        return db.withTransaction {
            val existing = if (budget.id != 0L) dao.budget(budget.id) else dao.budgetFor(budget.categoryId)
            // Moving a budget onto a category that already has one replaces that one.
            dao.budgetFor(budget.categoryId)?.takeIf { it.id != existing?.id }?.let { dao.deleteBudget(it.id) }
            val entity = BudgetEntity(
                id = existing?.id ?: 0,
                categoryId = budget.categoryId,
                amountPaise = budget.amount.paise,
                alertPct = budget.alertPercent,
                rollover = budget.rollover,
                createdAt = existing?.createdAt ?: clock.millis(),
            )
            if (existing != null) dao.updateBudget(entity).let { entity.id } else dao.insertBudget(entity)
        }
    }

    /** Deletes a budget and returns it for undo. */
    suspend fun delete(id: Long): Budget? {
        val existing = dao.budget(id) ?: return null
        dao.deleteBudget(id)
        return existing.toModel()
    }

    suspend fun restore(budget: Budget) {
        dao.insertBudget(BudgetEntity(id = budget.id, categoryId = budget.categoryId, amountPaise = budget.amount.paise, alertPct = budget.alertPercent, rollover = budget.rollover, createdAt = clock.millis()))
    }

    private fun BudgetEntity.toModel() = Budget(id, categoryId, Money(amountPaise), alertPct, rollover)
}
