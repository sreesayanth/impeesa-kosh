package com.impeesa.finance.core.model.analytics

import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Money
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One day's money, in paise. [spent] is spending-category expenses; [refunds] are refunds received
 * (netted out of spending, §10.1); [income] excludes transfers and refunds.
 */
data class DayFlow(val date: Int, val spent: Long, val refunds: Long, val income: Long) {
    val netSpent: Long get() = spent - refunds
}

data class PeriodFlow(val period: Period, val spent: Money, val income: Money) {
    val saved: Money get() = income - spent

    /** Saved ÷ income, or null when there was no income (§10.1). */
    val savingsRate: Double? get() = if (income.paise > 0) saved.paise.toDouble() / income.paise else null
}

object Flows {
    /** Spending in [period]: expenses minus refunds received (§10.1). */
    fun spending(days: List<DayFlow>, period: Period): Money = Money(days.filter { it.date in period.keys }.sumOf { it.netSpent })

    fun income(days: List<DayFlow>, period: Period): Money = Money(days.filter { it.date in period.keys }.sumOf { it.income })

    fun flow(days: List<DayFlow>, period: Period) = PeriodFlow(period, spending(days, period), income(days, period))

    /** Net spending per date, for heatmaps and bar charts. Days without spending are absent. */
    fun dailySpending(days: List<DayFlow>): Map<LocalDate, Money> =
        days.filter { it.netSpent != 0L }.associate { DateKey.toLocalDate(it.date) to Money(it.netSpent) }

    /**
     * Average spending per weekday-day vs weekend-day over the days of [period] up to [today] (§10.1).
     * Returns (weekday average, weekend average); a side with no days is zero.
     */
    fun weekdayWeekend(days: List<DayFlow>, period: Period, today: LocalDate): Pair<Money, Money> {
        val daily = dailySpending(days)
        val counted = period.dates().filter { !it.isAfter(today) }.toList()
        val (weekend, weekday) = counted.partition { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
        fun avg(list: List<LocalDate>) = if (list.isEmpty()) Money.ZERO else Money(list.sumOf { daily[it]?.paise ?: 0L } / list.size)
        return avg(weekday) to avg(weekend)
    }
}

/** A budget in one period. [carried] is last period's unspent money when carry-over is on. */
data class BudgetStatus(val budget: Money, val spent: Money, val carried: Money = Money.ZERO) {
    val available: Money get() = budget + carried
    val remaining: Money get() = available - spent
    val over: Boolean get() = spent > available
    val usedFraction: Float get() = if (available.paise <= 0) (if (spent.paise > 0) 1f else 0f) else spent.paise.toFloat() / available.paise

    /** Has [warnPercent] of the budget been used? */
    fun reached(warnPercent: Int): Boolean = available.paise > 0 && spent.paise * 100 >= available.paise * warnPercent
}

object BudgetMath {
    /** Where spending "should" be by [today] on a straight line (§10.1, v1 linear). */
    fun pace(budget: Money, period: Period, today: LocalDate): Money =
        Money(budget.paise * period.elapsedDays(today) / period.days)

    /** `max(0, budget − spent − upcoming bills) ÷ days left including today` (§10.1). */
    fun safeToSpendPerDay(available: Money, spent: Money, upcomingBills: Money, period: Period, today: LocalDate): Money {
        val left = period.daysLeft(today)
        if (left <= 0) return Money.ZERO
        val free = (available - spent - upcomingBills).paise.coerceAtLeast(0)
        return Money(free / left)
    }

    /** Unspent money from the previous period; overspending is not carried as debt. */
    fun carryOver(previousBudget: Money, previousSpent: Money): Money = Money((previousBudget - previousSpent).paise.coerceAtLeast(0))

    /** Suggested budget: the median of recent periods, rounded up to the next ₹500. Null without history. */
    fun suggest(history: List<Money>): Money? {
        val values = history.map { it.paise }.filter { it > 0 }.sorted()
        if (values.isEmpty()) return null
        val median = if (values.size % 2 == 1) values[values.size / 2] else (values[values.size / 2 - 1] + values[values.size / 2]) / 2
        val step = 500_00L
        return Money(((median + step - 1) / step) * step)
    }

    /** How many of the most recent periods in a row went over budget (oldest first in [history]). */
    fun overrunStreak(history: List<BudgetStatus>): Int = history.asReversed().takeWhile { it.over }.count()
}

/** Five-level daily heatmap (§10.1): 0 = no spending, 1–4 by quantiles of the last 90 days. */
object Heatmap {
    fun thresholds(last90Days: Collection<Money>): List<Long> {
        val values = last90Days.map { it.paise }.filter { it > 0 }.sorted()
        if (values.isEmpty()) return listOf(1, 1, 1)
        fun q(p: Double) = values[((values.size - 1) * p).toInt()]
        return listOf(q(0.25), q(0.5), q(0.75))
    }

    fun level(amount: Money, thresholds: List<Long>): Int {
        val v = amount.paise
        if (v <= 0) return 0
        return 1 + thresholds.count { v > it }
    }
}
