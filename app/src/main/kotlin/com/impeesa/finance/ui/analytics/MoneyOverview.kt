package com.impeesa.finance.ui.analytics

import androidx.compose.runtime.Immutable
import com.impeesa.finance.core.data.AnalyticsRepository
import com.impeesa.finance.core.data.Budget
import com.impeesa.finance.core.data.BudgetRepository
import com.impeesa.finance.core.data.CategoryRepository
import com.impeesa.finance.core.data.CategoryTotal
import com.impeesa.finance.core.data.RecurringSummary
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.analytics.BudgetMath
import com.impeesa.finance.core.model.analytics.BudgetStatus
import com.impeesa.finance.core.model.analytics.CategoryChange
import com.impeesa.finance.core.model.analytics.DayFlow
import com.impeesa.finance.core.model.analytics.Flows
import com.impeesa.finance.core.model.analytics.Insight
import com.impeesa.finance.core.model.analytics.InsightInputs
import com.impeesa.finance.core.model.analytics.InsightRules
import com.impeesa.finance.core.model.analytics.Period
import com.impeesa.finance.core.model.analytics.Periods
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** One budget in the current period, with the last three periods for streaks and suggestions. */
@Immutable
data class BudgetLine(
    val budget: Budget,
    val name: String,
    val color: Int?,
    val status: BudgetStatus,
    /** Oldest first. */
    val history: List<BudgetStatus>,
    val suggestion: Money?,
) {
    val streak: Int get() = BudgetMath.overrunStreak(history)
}

/** "Raise Food to ₹11,500 and trim Shopping to keep the total the same?" (§5.8 smart suggestion). */
@Immutable
data class Rebalance(val raise: BudgetLine, val to: Money, val trim: BudgetLine?, val trimTo: Money?) {
    val key: String get() = "BUDGET_REBALANCE:${raise.budget.categoryId}:${to.paise}"
}

@Immutable
data class BudgetOverview(
    val period: Period,
    val startDay: Int,
    val periodLabel: String,
    val today: LocalDate,
    val spent: Money,
    val overall: BudgetLine?,
    val categories: List<BudgetLine>,
    val upcomingBills: Money,
    val safePerDay: Money?,
    /** Share of the overall budget the straight-line pace expects by today. */
    val paceFraction: Float?,
    val rebalance: Rebalance?,
    val recurring: RecurringSummary,
)

/**
 * Builds budget and insight state from the repositories, for Home, Budgets and Insights.
 * Everything numeric comes from the pure functions in core:model (unit-tested to the paisa).
 */
class MoneyOverview(
    private val analytics: AnalyticsRepository,
    private val budgets: BudgetRepository,
    private val categories: CategoryRepository,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun budgets(today: LocalDate, startDayFlow: Flow<Int>): Flow<BudgetOverview> = startDayFlow.flatMapLatest { startDay ->
        val period = Periods.budget(today, startDay)
        combine(
            budgets.observe(),
            analytics.observeCategoryTotals(period),
            analytics.observeRecurring(today),
            categories.observeAll(),
        ) { list, totals, recurring, cats -> Snapshot(list, totals, recurring, cats) }
            .mapLatest { s -> build(today, startDay, period, s) }
    }

    private data class Snapshot(val budgets: List<Budget>, val totals: List<CategoryTotal>, val recurring: RecurringSummary, val categories: List<Category>)

    private suspend fun build(today: LocalDate, startDay: Int, period: Period, s: Snapshot): BudgetOverview {
        val previous = (1..3).map { offset -> analytics.categoryTotals(Periods.budget(today, startDay, -offset)) }.reversed()
        fun spentIn(totals: List<CategoryTotal>, categoryId: Long?) =
            if (categoryId == null) Money(totals.sumOf { it.total.paise }) else totals.firstOrNull { it.categoryId == categoryId }?.total ?: Money.ZERO

        val byId = s.categories.associateBy { it.id }
        val lines = s.budgets.mapNotNull { budget ->
            val category = budget.categoryId?.let { byId[it] ?: return@mapNotNull null }
            val history = previous.map { BudgetStatus(budget.amount, spentIn(it, budget.categoryId)) }
            val carried = if (budget.rollover) BudgetMath.carryOver(budget.amount, history.last().spent) else Money.ZERO
            BudgetLine(
                budget = budget,
                name = category?.name ?: "Monthly budget",
                color = category?.color,
                status = BudgetStatus(budget.amount, spentIn(s.totals, budget.categoryId), carried),
                history = history,
                suggestion = BudgetMath.suggest(history.map { it.spent }),
            )
        }
        val overall = lines.firstOrNull { it.budget.categoryId == null }
        val categoryLines = lines.filter { it.budget.categoryId != null }
            .sortedWith(compareByDescending<BudgetLine> { it.status.over }.thenByDescending { it.status.usedFraction })
        val spent = spentIn(s.totals, null)
        val bills = Money(
            s.recurring.series.filter { !it.isCredit && it.nextDate in period && !it.nextDate.isBefore(today) }.sumOf { it.amount.paise },
        )
        val safe = overall?.let { BudgetMath.safeToSpendPerDay(it.status.available, spent, bills, period, today) }
        return BudgetOverview(
            period = period,
            startDay = startDay,
            periodLabel = periodLabel(period, startDay),
            today = today,
            spent = spent,
            overall = overall,
            categories = categoryLines,
            upcomingBills = bills,
            safePerDay = safe,
            paceFraction = overall?.let { period.elapsedDays(today).toFloat() / period.days },
            rebalance = rebalance(categoryLines),
            recurring = s.recurring,
        )
    }

    private fun rebalance(lines: List<BudgetLine>): Rebalance? {
        val raise = lines.filter { it.streak >= 3 && it.suggestion != null && it.suggestion > it.budget.amount }
            .maxByOrNull { it.streak } ?: return null
        val to = raise.suggestion!!
        val extra = to - raise.budget.amount
        // Take it from the budget that has gone most unused, if it can spare it.
        val trim = lines.filter { it !== raise }
            .map { it to Money(it.history.sumOf { h -> (h.budget - h.spent).paise } / it.history.size.coerceAtLeast(1)) }
            .filter { (_, spare) -> spare >= extra }
            .maxByOrNull { (_, spare) -> spare }?.first
        return Rebalance(raise, to, trim, trim?.let { it.budget.amount - extra })
    }

    /**
     * Insight rules (§10.3) over the last 90 days, minus dismissed ones still in their cooldown.
     * [overview] provides budgets (rebalance, safe-to-spend) and recurring price changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun insights(today: LocalDate, overview: Flow<BudgetOverview>, dismissed: Flow<Map<String, Long>>, nowMillis: () -> Long): Flow<List<Insight>> =
        overview.flatMapLatest { o ->
            combine(
                analytics.observeDaily(today.minusDays(120), today),
                analytics.observeFoodOrderHours(o.period),
                dismissed,
            ) { days, hours, hidden -> Triple(days, hours, hidden) }
                .mapLatest { (days, hours, hidden) ->
                    val likeForLike = Periods.likeForLike(o.period, Periods.budget(today, o.startDay, -1), today)
                    val previousTotals = analytics.categoryTotals(likeForLike).associateBy { it.categoryId }
                    val currentTotals = analytics.categoryTotals(Period(o.period.start, minOf(today, o.period.end)))
                    val inputs = InsightInputs(
                        today = today,
                        daily = Flows.dailySpending(days),
                        weeklyWeekdayWeekend = (3 downTo 1).map { back -> Flows.weekdayWeekend(days, Periods.week(today, -back), today) },
                        foodOrders = hours.size,
                        lateFoodOrders = hours.count { it >= 21 || it < 4 },
                        categoryChanges = currentTotals.filter { it.categoryId != null }.map { CategoryChange(it.name, it.total, previousTotals[it.categoryId]?.total ?: Money.ZERO) },
                        rebalance = listOfNotNull(o.rebalance?.let { it.raise.name to it.to }),
                        priceChanges = o.recurring.series.filter { it.priceChange != null && !it.lastDate.isBefore(today.minusDays(30)) },
                        safePerDay = o.safePerDay,
                    )
                    val now = nowMillis()
                    InsightRules.evaluate(inputs).filter { (hidden[it.key] ?: 0L) <= now }
                }
        }


    companion object {
        private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

        /** "October" for calendar months, "25 Sep – 24 Oct" for salary cycles. */
        fun periodLabel(period: Period, startDay: Int): String =
            if (startDay == 1) period.start.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            else "${period.start.format(shortDate)} – ${period.end.format(shortDate)}"

        fun weekdayShort(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

        /** Used by charts: net spending per day of [period]. */
        fun dailyValues(days: List<DayFlow>, period: Period): List<Long> {
            val map = Flows.dailySpending(days)
            return period.dates().map { map[it]?.paise ?: 0L }.toList()
        }
    }
}
