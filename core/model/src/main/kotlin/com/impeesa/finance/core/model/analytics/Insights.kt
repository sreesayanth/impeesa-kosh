package com.impeesa.finance.core.model.analytics

import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

enum class InsightKind(val cooldownDays: Int) {
    BIG_DAY(7),
    WEEKEND_PATTERN(30),
    LATE_NIGHT(30),
    CATEGORY_TREND(14),
    BUDGET_REBALANCE(30),
    PRICE_CHANGE(30),
    STREAK(14),
}

/** A finding shown on Home and Insights, filled into a fixed template (§10.3). */
data class Insight(
    val kind: InsightKind,
    /** Stable per occurrence, for "dismissed" and cooldowns, e.g. "BIG_DAY:20261009". */
    val key: String,
    val text: String,
    /** Higher shows first. */
    val priority: Int,
    /** Good news is shown in lime, warnings in amber. */
    val positive: Boolean = false,
)

data class CategoryChange(val name: String, val current: Money, val previous: Money)

/** What the insight rules look at. Everything is pre-computed by the caller. */
data class InsightInputs(
    val today: LocalDate,
    /** Net spending per day over at least the last 90 days. */
    val daily: Map<LocalDate, Money>,
    /** Weekday and weekend average per week, oldest first, for complete recent weeks. */
    val weeklyWeekdayWeekend: List<Pair<Money, Money>> = emptyList(),
    /** Food-delivery orders in the period and how many were after 21:00. */
    val foodOrders: Int = 0,
    val lateFoodOrders: Int = 0,
    /** Per category, like-for-like this period vs last. */
    val categoryChanges: List<CategoryChange> = emptyList(),
    /** Category names over budget 3 periods in a row, with a suggested new amount. */
    val rebalance: List<Pair<String, Money>> = emptyList(),
    val priceChanges: List<RecurringSeries> = emptyList(),
    /** Safe-to-spend per day, when there is a budget. */
    val safePerDay: Money? = null,
)

object InsightRules {
    fun evaluate(inputs: InsightInputs): List<Insight> = buildList {
        bigDay(inputs)?.let(::add)
        weekendPattern(inputs)?.let(::add)
        lateNight(inputs)?.let(::add)
        addAll(categoryTrends(inputs))
        inputs.rebalance.forEach { (name, amount) ->
            add(Insight(InsightKind.BUDGET_REBALANCE, "BUDGET_REBALANCE:$name", "You've gone over $name 3 periods in a row. A budget of ${MoneyFormat.format(amount)} would fit how you spend.", 70))
        }
        inputs.priceChanges.forEach { s ->
            val change = s.priceChange ?: return@forEach
            val verb = if (change.isNegative) "down" else "up"
            add(Insight(InsightKind.PRICE_CHANGE, "PRICE_CHANGE:${s.key}:${s.lastDate}", "${s.label} went $verb ${MoneyFormat.format(change.abs())} to ${MoneyFormat.format(s.amount)}.", 80))
        }
        streak(inputs)?.let(::add)
    }.sortedByDescending { it.priority }

    /** Day total > 2.5 × the median daily spend (90 days) and > ₹1,000, in the last week. */
    internal fun bigDay(inputs: InsightInputs): Insight? {
        val window = (1..90L).map { inputs.today.minusDays(it) }
        val values = window.map { inputs.daily[it]?.paise ?: 0L }.filter { it > 0 }.sorted()
        if (values.size < 10) return null
        val median = values[values.size / 2]
        val recent = (0..6L).map { inputs.today.minusDays(it) }
            .mapNotNull { d -> inputs.daily[d]?.let { d to it } }
            .filter { (_, m) -> m.paise > median * 5 / 2 && m.paise > 1_000_00 }
            .maxByOrNull { it.second }
            ?: return null
        val (date, amount) = recent
        val times = amount.paise.toDouble() / median
        val day = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return Insight(
            InsightKind.BIG_DAY,
            "BIG_DAY:$date",
            "$day was ${"%.1f".format(Locale.ENGLISH, times).removeSuffix(".0")}× your usual day — ${MoneyFormat.format(amount)} spent.",
            60,
        )
    }

    private fun weekendPattern(inputs: InsightInputs): Insight? {
        val weeks = inputs.weeklyWeekdayWeekend.takeLast(3)
        if (weeks.size < 3) return null
        if (!weeks.all { (wd, we) -> wd.paise > 0 && we.paise * 10 > wd.paise * 18 }) return null
        val weekday = Money(weeks.sumOf { it.first.paise } / weeks.size)
        val weekend = Money(weeks.sumOf { it.second.paise } / weeks.size)
        val times = weekend.paise.toDouble() / weekday.paise
        return Insight(
            InsightKind.WEEKEND_PATTERN,
            "WEEKEND_PATTERN:${inputs.today.withDayOfMonth(1)}",
            "You spend ${"%.1f".format(Locale.ENGLISH, times)}× more on weekend days — ${MoneyFormat.format(weekend)} vs ${MoneyFormat.format(weekday)} a day.",
            40,
        )
    }

    private fun lateNight(inputs: InsightInputs): Insight? {
        if (inputs.foodOrders < 4 || inputs.lateFoodOrders * 2 < inputs.foodOrders) return null
        return Insight(
            InsightKind.LATE_NIGHT,
            "LATE_NIGHT:${inputs.today.withDayOfMonth(1)}",
            "${inputs.lateFoodOrders} of your ${inputs.foodOrders} food orders this period were after 9 PM.",
            30,
        )
    }

    /** Up or down > 25 % like-for-like and > ₹1,000 difference. */
    private fun categoryTrends(inputs: InsightInputs): List<Insight> = inputs.categoryChanges
        .sortedByDescending { kotlin.math.abs(it.current.paise - it.previous.paise) }
        .mapNotNull { c ->
        val diff = c.current.paise - c.previous.paise
        if (c.previous.paise <= 0 || kotlin.math.abs(diff) <= 1_000_00 || kotlin.math.abs(diff) * 4 <= c.previous.paise) return@mapNotNull null
        val pct = kotlin.math.abs(diff) * 100 / c.previous.paise
        val up = diff > 0
        Insight(
            InsightKind.CATEGORY_TREND,
            "CATEGORY_TREND:${c.name}:${inputs.today.withDayOfMonth(1)}",
            "${c.name} is ${if (up) "up" else "down"} $pct% on last period so far (${MoneyFormat.format(Money(kotlin.math.abs(diff)))} ${if (up) "more" else "less"}).",
            if (up) 50 else 20,
            positive = !up,
        )
    }.take(2)

    /** Under the daily safe-to-spend for the last 5 days. */
    private fun streak(inputs: InsightInputs): Insight? {
        val safe = inputs.safePerDay ?: return null
        if (safe.paise <= 0) return null
        val last5 = (1..5L).map { inputs.today.minusDays(it) }
        if (!last5.all { (inputs.daily[it]?.paise ?: 0L) <= safe.paise }) return null
        return Insight(InsightKind.STREAK, "STREAK:${inputs.today}", "5 days in a row under your daily safe-to-spend. Nice.", 10, positive = true)
    }

    /** Biggest spending weekday in [daily] over the given dates, for "Fridays are your biggest day". */
    fun biggestWeekday(daily: Map<LocalDate, Money>, dates: List<LocalDate>): DayOfWeek? =
        dates.groupBy { it.dayOfWeek }.mapValues { (_, list) -> list.sumOf { daily[it]?.paise ?: 0L } }
            .filterValues { it > 0 }.maxByOrNull { it.value }?.key
}
