package com.impeesa.finance.core.model.analytics

import com.impeesa.finance.core.model.DateKey
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** An inclusive run of days. */
data class Period(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "Period ends before it starts" }
    }

    val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1

    /** yyyyMMdd keys, for queries. */
    val keys: IntRange get() = DateKey.of(start)..DateKey.of(end)

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    /** Days from the start up to and including [today], clamped to the period. */
    fun elapsedDays(today: LocalDate): Int = when {
        today.isBefore(start) -> 0
        today.isAfter(end) -> days
        else -> ChronoUnit.DAYS.between(start, today).toInt() + 1
    }

    /** Days left including [today]; 0 once the period is over. */
    fun daysLeft(today: LocalDate): Int = days - elapsedDays(today) + if (today in this) 1 else 0

    fun dates(): Sequence<LocalDate> = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
}

/** How a period is chosen on Insights. */
enum class PeriodKind { WEEK, MONTH, YEAR }

/**
 * Budget periods follow the user's month start day (salary cycle, IMPLEMENTATION_PLAN.md §10.1):
 * from the start day to the day before the next one. Days 29–31 clamp to a short month's last day,
 * so 31 means "the last day of every month".
 */
object Periods {
    const val LAST_DAY = 31

    private fun startIn(year: Int, month: Int, startDay: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        return first.withDayOfMonth(minOf(startDay.coerceIn(1, LAST_DAY), first.lengthOfMonth()))
    }

    /** The budget period that contains [today]. */
    fun budget(today: LocalDate, startDay: Int): Period {
        val thisStart = startIn(today.year, today.monthValue, startDay)
        val start = if (today.isBefore(thisStart)) {
            val prev = today.minusMonths(1)
            startIn(prev.year, prev.monthValue, startDay)
        } else {
            thisStart
        }
        val next = start.plusMonths(1)
        val nextStart = startIn(next.year, next.monthValue, startDay)
        return Period(start, nextStart.minusDays(1))
    }

    /** The budget period [offset] periods before (negative) or after (positive) the one containing [today]. */
    fun budget(today: LocalDate, startDay: Int, offset: Int): Period {
        var period = budget(today, startDay)
        repeat(kotlin.math.abs(offset)) {
            period = if (offset < 0) budget(period.start.minusDays(1), startDay) else budget(period.end.plusDays(1), startDay)
        }
        return period
    }

    /** Monday to Sunday. */
    fun week(today: LocalDate, offset: Int = 0): Period {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset.toLong())
        return Period(monday, monday.plusDays(6))
    }

    fun year(today: LocalDate, offset: Int = 0): Period {
        val y = today.year + offset
        return Period(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))
    }

    fun of(kind: PeriodKind, today: LocalDate, startDay: Int, offset: Int = 0): Period = when (kind) {
        PeriodKind.WEEK -> week(today, offset)
        PeriodKind.MONTH -> budget(today, startDay, offset)
        PeriodKind.YEAR -> year(today, offset)
    }

    /**
     * Like-for-like comparison: the same number of days into [previous] as have passed in [current]
     * (never month-to-date against a whole month).
     */
    fun likeForLike(current: Period, previous: Period, today: LocalDate): Period {
        val elapsed = current.elapsedDays(today).coerceAtLeast(1)
        val end = previous.start.plusDays((elapsed - 1).toLong())
        return Period(previous.start, if (end.isAfter(previous.end)) previous.end else end)
    }
}
