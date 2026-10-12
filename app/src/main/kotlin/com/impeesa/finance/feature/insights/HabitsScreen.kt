package com.impeesa.finance.feature.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.AppPrefs
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.AnalyticsRepository
import com.impeesa.finance.core.data.MerchantTotal
import com.impeesa.finance.core.data.RecurringSummary
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.analytics.Flows
import com.impeesa.finance.core.model.analytics.Heatmap
import com.impeesa.finance.core.model.analytics.Period
import com.impeesa.finance.core.model.analytics.Periods
import com.impeesa.finance.core.model.analytics.RecurringKind
import com.impeesa.finance.core.model.analytics.RecurringSeries
import com.impeesa.finance.core.ui.component.BarChart
import com.impeesa.finance.core.ui.component.CalendarHeatmap
import com.impeesa.finance.core.ui.component.HeatCell
import com.impeesa.finance.core.ui.component.LetterTile
import com.impeesa.finance.core.ui.component.ProgressBar
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SurfaceCard
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.feature.budgets.startDayLabel
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.UndoEvent
import com.impeesa.finance.ui.analytics.MoneyOverview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Immutable
data class HabitsUiState(
    val loading: Boolean = true,
    val periodLabel: String = "",
    val cells: List<HeatCell?> = emptyList(),
    val heatDescription: String = "",
    val biggestDays: String? = null,
    val weekdayAverages: List<Long> = emptyList(),
    val weekday: Money = Money.ZERO,
    val weekend: Money = Money.ZERO,
    val foodOrders: Int = 0,
    val lateFoodOrders: Int = 0,
    val recurring: RecurringSummary = RecurringSummary(emptyList(), null),
    val topMerchants: List<MerchantTotal> = emptyList(),
    val startDay: Int = 1,
)

class HabitsViewModel(
    private val analytics: AnalyticsRepository,
    private val prefs: AppPrefs,
    private val undo: UndoBus,
    clock: Clock,
) : ViewModel() {
    private val today = LocalDate.now(clock)
    private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<HabitsUiState> = prefs.monthStartDay.flatMapLatest { startDay ->
        val period = Periods.budget(today, startDay)
        val eightWeeks = Period(Periods.week(today, -8).start, Periods.week(today, -1).end)
        val from = minOf(period.start, eightWeeks.start, today.minusDays(90))
        combine(
            analytics.observeDaily(from, today),
            analytics.observeFoodOrderHours(period),
            analytics.observeRecurring(today),
            analytics.observeTopMerchants(period, limit = 5),
        ) { days, hours, recurring, merchants ->
            val daily = Flows.dailySpending(days)
            val thresholds = Heatmap.thresholds((1..90L).mapNotNull { daily[today.minusDays(it)] })
            val leading = (period.start.dayOfWeek.value - 1)
            val cells = List<HeatCell?>(leading) { null } + period.dates().map { d ->
                val amount = daily[d] ?: Money.ZERO
                HeatCell(
                    day = d.dayOfMonth,
                    level = Heatmap.level(amount, thresholds),
                    isToday = d == today,
                    isFuture = d.isAfter(today),
                    description = "${d.format(dayFormat)}: ${MoneyFormat.spoken(amount)}",
                )
            }
            val pastDates = period.dates().filter { !it.isAfter(today) }.toList()
            val top2 = pastDates.mapNotNull { d -> daily[d]?.let { d to it } }.sortedByDescending { it.second }.take(2)
            val (weekday, weekend) = Flows.weekdayWeekend(days, Period(period.start, minOf(today, period.end)), today)
            val byWeekday = DayOfWeek.entries.map { dow ->
                val dates = eightWeeks.dates().filter { it.dayOfWeek == dow }.toList()
                dates.sumOf { daily[it]?.paise ?: 0L } / dates.size.coerceAtLeast(1)
            }
            HabitsUiState(
                loading = false,
                periodLabel = MoneyOverview.periodLabel(period, startDay) + ", so far",
                cells = cells,
                heatDescription = "Daily spending calendar. " + top2.joinToString(". ") { (d, m) -> "${d.format(dayFormat)} ${MoneyFormat.spoken(m)}" },
                biggestDays = top2.takeIf { it.size == 2 }?.let { (a, b) ->
                    "Your two biggest days were ${a.first.format(dayFormat)} (${MoneyFormat.format(a.second)}) and ${b.first.format(dayFormat)} (${MoneyFormat.format(b.second)})."
                },
                weekdayAverages = byWeekday,
                weekday = weekday,
                weekend = weekend,
                foodOrders = hours.size,
                lateFoodOrders = hours.count { it >= 21 || it < 4 },
                recurring = recurring,
                topMerchants = merchants,
                startDay = startDay,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HabitsUiState())

    fun hide(series: RecurringSeries) {
        viewModelScope.launch {
            analytics.hideRecurring(series.key)
            undo.post(UndoEvent("${series.label} won't be shown as recurring") { analytics.unhideRecurring(series.key) })
        }
    }

    fun useSalaryDay(day: Int) {
        val before = prefs.monthStartDay.value
        prefs.setMonthStartDay(day)
        viewModelScope.launch { undo.post(UndoEvent("Budget month now starts ${startDayLabel(day)}") { prefs.setMonthStartDay(before) }) }
    }
}

@Composable
fun HabitsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { HabitsViewModel(container.analyticsRepository, container.prefs, container.undo, container.clock) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ImpeesaTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ScreenHeader(title = "Spending habits", onBack = onBack)
                Text(state.periodLabel, style = ImpeesaTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(start = 56.dp))
            }
        }
        if (state.loading) return@LazyColumn

        item(key = "heat") {
            SurfaceCard(padding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Daily spend", style = ImpeesaTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Text("Less → More", style = ImpeesaTheme.type.caption, color = colors.textTertiary)
                }
                CalendarHeatmap(state.cells, state.heatDescription, Modifier.padding(top = 14.dp))
                state.biggestDays?.let { Note(it) }
            }
        }

        item(key = "patterns") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PatternTile(
                    title = "WEEKENDS",
                    tint = colors.shopping,
                    headline = weekendHeadline(state.weekday, state.weekend),
                    detail = "${MoneyFormat.format(state.weekend)} vs ${MoneyFormat.format(state.weekday)} a day",
                    modifier = Modifier.weight(1f),
                )
                PatternTile(
                    title = "LATE NIGHTS",
                    tint = colors.food,
                    headline = if (state.foodOrders == 0) "No food orders yet" else "${state.lateFoodOrders} of ${state.foodOrders} food orders after 9 PM",
                    detail = "Food & dining, this period",
                    modifier = Modifier.weight(1f),
                )
            }
        }

        item(key = "weekday") {
            SurfaceCard(padding = 18.dp) {
                val names = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
                val top = state.weekdayAverages.indices.maxByOrNull { state.weekdayAverages[it] }?.takeIf { state.weekdayAverages[it] > 0 }
                Text("By day of the week", style = ImpeesaTheme.type.title, color = colors.textPrimary)
                Text(
                    top?.let { "${DayOfWeek.entries[it].getDisplayName(TextStyle.FULL, Locale.ENGLISH)}s are your biggest day · last 8 weeks" } ?: "Average per day · last 8 weeks",
                    style = ImpeesaTheme.type.caption,
                    color = colors.textSecondary,
                )
                BarChart(
                    values = state.weekdayAverages,
                    labels = names.map { it.take(1) },
                    description = "Average spending by weekday: " + names.indices.joinToString(", ") { "${names[it]} ${MoneyFormat.spoken(Money(state.weekdayAverages.getOrElse(it) { 0 }))}" },
                    highlight = top,
                    highlightLabel = top?.let { MoneyFormat.compact(Money(state.weekdayAverages[it])) },
                    height = 120.dp,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }

        item(key = "recurring") { RecurringCard(state, onHide = viewModel::hide, onUseSalaryDay = viewModel::useSalaryDay) }

        if (state.topMerchants.isNotEmpty()) {
            item(key = "merchants") {
                SurfaceCard(padding = 18.dp) {
                    Text("Top merchants", style = ImpeesaTheme.type.title, color = colors.textPrimary)
                    val max = state.topMerchants.maxOf { it.total.paise }.coerceAtLeast(1)
                    val tints = listOf(colors.food, colors.shopping, colors.travel, colors.groceries, colors.fun_)
                    state.topMerchants.forEachIndexed { i, m ->
                        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Text(m.label, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                                Text(" · ${m.count} ${if (m.count == 1) "payment" else "payments"}", style = ImpeesaTheme.type.body, color = colors.textTertiary, modifier = Modifier.weight(1f))
                                Text(MoneyFormat.format(m.total), style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                            }
                            ProgressBar(fraction = m.total.paise.toFloat() / max, color = tints[i % tints.size])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    val colors = ImpeesaTheme.colors
    Row(
        Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.raised).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("✦", style = ImpeesaTheme.type.label, color = colors.amber)
        Text(text, style = ImpeesaTheme.type.caption, color = colors.textPrimary)
    }
}

@Composable
private fun PatternTile(title: String, tint: Color, headline: String, detail: String, modifier: Modifier) {
    val colors = ImpeesaTheme.colors
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(colors.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = ImpeesaTheme.type.caption, color = tint)
        Text(headline, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
        Text(detail, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
    }
}

@Composable
private fun RecurringCard(state: HabitsUiState, onHide: (RecurringSeries) -> Unit, onUseSalaryDay: (Int) -> Unit) {
    val colors = ImpeesaTheme.colors
    val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    val payments = state.recurring.series.filter { !it.isCredit }
    val salary = state.recurring.series.firstOrNull { it.kind == RecurringKind.SALARY }
    SurfaceCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Subscriptions & bills", style = ImpeesaTheme.type.title, color = colors.textPrimary)
                Text("Found from repeating payments", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
            if (payments.isNotEmpty()) {
                Text(MoneyFormat.format(state.recurring.subscriptionsMonthly) + " /mo", style = ImpeesaTheme.type.title, color = colors.textPrimary)
            }
        }
        if (payments.isEmpty()) {
            Text(
                "Nothing yet. Payments that repeat at a steady interval (3 or more) show up here with their next date.",
                style = ImpeesaTheme.type.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        payments.forEach { s ->
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LetterTile(letter = s.label.take(1).uppercase(), color = if (s.kind == RecurringKind.BILL) colors.bills else colors.fun_, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(s.label, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1)
                    val change = s.priceChange
                    if (change != null) {
                        Text("Price ${if (change.isNegative) "down" else "up"} ${MoneyFormat.format(change.abs())}", style = ImpeesaTheme.type.caption, color = colors.amber)
                    } else {
                        Text("${s.cadence.label} · next ${s.nextDate.format(dateFormat)}", style = ImpeesaTheme.type.caption, color = colors.textTertiary)
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(MoneyFormat.format(s.amount), style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                    Text(
                        "Not recurring",
                        style = ImpeesaTheme.type.caption,
                        color = colors.textTertiary,
                        modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClickLabel = "Not a recurring payment") { onHide(s) }.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            }
        }
        if (salary != null) {
            Box(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.lime.copy(alpha = 0.08f)).padding(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Salary: ${MoneyFormat.format(salary.amount)} from ${salary.label}, next around ${salary.nextDate.format(dateFormat)}", style = ImpeesaTheme.type.caption, color = colors.textPrimary)
                    val day = state.recurring.salaryDay
                    if (day != null && day != state.startDay) {
                        Text(
                            "Start budget months ${startDayLabel(day)}",
                            style = ImpeesaTheme.type.label,
                            color = colors.lime,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onUseSalaryDay(day) }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Daily averages within 15% of each other count as alike. */
private fun weekendHeadline(weekday: Money, weekend: Money): String {
    fun times(a: Money, b: Money) = "%.1f".format(Locale.ENGLISH, a.paise.toDouble() / b.paise)
    return when {
        weekday.paise <= 0 && weekend.paise <= 0 -> "Not enough spending yet"
        weekday.paise <= 0 -> "Your spending falls on Sat–Sun"
        weekend.paise <= 0 -> "Your spending falls on weekdays"
        weekend.paise * 100 > weekday.paise * 115 -> "You spend ${times(weekend, weekday)}× more on Sat–Sun"
        weekday.paise * 100 > weekend.paise * 115 -> "You spend ${times(weekday, weekend)}× more on weekdays"
        else -> "Weekdays and weekends look alike"
    }
}
