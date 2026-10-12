package com.impeesa.finance.core.model.analytics

import com.impeesa.finance.core.model.Money
import java.time.LocalDate

/** Credit-card cycle dates and utilisation (IMPLEMENTATION_PLAN.md §5.7, §10.1). Days 29–31 clamp to short months. */
object CardCycle {
    private fun onDay(month: LocalDate, day: Int): LocalDate = month.withDayOfMonth(minOf(day.coerceIn(1, 31), month.lengthOfMonth()))

    /** The latest statement date on or before [today]. */
    fun lastStatementDate(today: LocalDate, statementDay: Int): LocalDate {
        val thisMonth = onDay(today, statementDay)
        return if (thisMonth.isAfter(today)) onDay(today.minusMonths(1).withDayOfMonth(1), statementDay) else thisMonth
    }

    /** The current cycle: the day after the last statement up to the next statement date. */
    fun currentCycle(today: LocalDate, statementDay: Int): Period {
        val last = lastStatementDate(today, statementDay)
        val next = onDay(last.plusMonths(1).withDayOfMonth(1), statementDay)
        return Period(last.plusDays(1), next)
    }

    /** The next due date on or after [today]. */
    fun nextDueDate(today: LocalDate, dueDay: Int): LocalDate {
        val thisMonth = onDay(today, dueDay)
        return if (thisMonth.isBefore(today)) onDay(today.plusMonths(1).withDayOfMonth(1), dueDay) else thisMonth
    }

    /** Owed ÷ limit, 0..1+; null without a limit. Above 30 % starts to hurt a credit score. */
    fun utilisation(owed: Money, limit: Money?): Float? =
        limit?.takeIf { it.paise > 0 }?.let { owed.paise.coerceAtLeast(0).toFloat() / it.paise }

    const val HEALTHY_UTILISATION = 0.3f
}
