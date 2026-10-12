package com.impeesa.finance.core.model.analytics

import com.impeesa.finance.core.model.Money
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

/** One payment to or from a payee, as recurring detection sees it. */
data class Occurrence(
    /** Merchant key or UPI ID. */
    val key: String,
    val label: String,
    val amount: Money,
    val date: LocalDate,
    val isCredit: Boolean,
)

enum class Cadence(val days: Int, val range: IntRange, val tolerance: Int, val label: String) {
    WEEKLY(7, 6..8, 1, "Weekly"),
    MONTHLY(30, 27..33, 3, "Monthly"),
    QUARTERLY(91, 85..95, 5, "Every 3 months"),
    YEARLY(365, 355..375, 7, "Yearly"),
}

enum class RecurringKind { SUBSCRIPTION, BILL, SALARY, INCOME }

data class RecurringSeries(
    val key: String,
    val label: String,
    val kind: RecurringKind,
    val cadence: Cadence,
    /** The latest amount. */
    val amount: Money,
    val lastDate: LocalDate,
    val nextDate: LocalDate,
    val count: Int,
    /** Latest minus previous amount, when it changed by more than ₹1 and 2 % (§9.6 step 6). */
    val priceChange: Money?,
    val since: LocalDate,
) {
    val isCredit: Boolean get() = kind == RecurringKind.SALARY || kind == RecurringKind.INCOME

    /** What this costs per month, for the subscriptions total. */
    val monthlyCost: Money get() = Money(amount.paise * 30 / cadence.days)
}

/**
 * Finds subscriptions, bills and salary in transaction history (IMPLEMENTATION_PLAN.md §9.6):
 * 3+ payments to one payee at a steady interval with steady amounts.
 */
object RecurringDetector {
    private const val MIN_COUNT = 3
    private const val MAX_CV = 0.15
    private const val EXACT_CV = 0.02

    fun detect(occurrences: List<Occurrence>, today: LocalDate): List<RecurringSeries> {
        val found = occurrences
            .groupBy { it.key to it.isCredit }
            .mapNotNull { (_, list) -> series(list, today) }
        // The largest steady monthly credit is the salary (§9.6 step 7).
        val salary = found.filter { it.kind == RecurringKind.INCOME && it.cadence == Cadence.MONTHLY }.maxByOrNull { it.amount }
        return found.map { if (it === salary) it.copy(kind = RecurringKind.SALARY) else it }
            .sortedBy { it.nextDate }
    }

    private fun series(raw: List<Occurrence>, today: LocalDate): RecurringSeries? {
        // Several payments on one day (a split order) count once, as their total.
        val byDay = raw.groupBy { it.date }.map { (date, list) -> date to list.sumOf { it.amount.paise } }.sortedBy { it.first }
        if (byDay.size < MIN_COUNT) return null
        val intervals = byDay.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.first, b.first).toInt() }
        val medianInterval = median(intervals.map { it.toLong() }).toInt()
        val cadence = Cadence.entries.firstOrNull { medianInterval in it.range } ?: return null
        val mad = median(intervals.map { abs(it - medianInterval).toLong() })
        if (mad > cadence.tolerance) return null

        val amounts = byDay.map { it.second }
        val mean = amounts.average()
        if (mean <= 0) return null
        val cv = sqrt(amounts.sumOf { (it - mean) * (it - mean) } / amounts.size) / mean
        if (cv > MAX_CV) return null

        val last = byDay.last()
        val next = last.first.plusDays(medianInterval.toLong())
        // A series that has missed two cycles has ended.
        if (ChronoUnit.DAYS.between(next, today) > medianInterval + cadence.tolerance) return null

        val isCredit = raw.first().isCredit
        val previous = byDay[byDay.size - 2].second
        val change = last.second - previous
        val priceChange = change.takeIf { abs(it) > 100 && abs(it) * 100 > previous * 2 }?.let(::Money)
        return RecurringSeries(
            key = raw.first().key,
            label = raw.maxBy { it.date }.label,
            kind = when {
                isCredit -> RecurringKind.INCOME
                cv <= EXACT_CV -> RecurringKind.SUBSCRIPTION
                else -> RecurringKind.BILL
            },
            cadence = cadence,
            amount = Money(last.second),
            lastDate = last.first,
            nextDate = next,
            count = byDay.size,
            priceChange = priceChange,
            since = byDay.first().first,
        )
    }

    /** The salary's usual day of the month: a natural month start day to suggest. */
    fun salaryDay(series: List<RecurringSeries>, history: List<Occurrence>): Int? {
        val salary = series.firstOrNull { it.kind == RecurringKind.SALARY } ?: return null
        val days = history.filter { it.key == salary.key && it.isCredit }.map { it.date.dayOfMonth.toLong() }
        return if (days.isEmpty()) null else median(days).toInt()
    }

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
