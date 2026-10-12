package com.impeesa.finance.feature.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.AppPrefs
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.AnalyticsRepository
import com.impeesa.finance.core.data.CategoryTotal
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.analytics.Flows
import com.impeesa.finance.core.model.analytics.Insight
import com.impeesa.finance.core.model.analytics.Period
import com.impeesa.finance.core.model.analytics.PeriodFlow
import com.impeesa.finance.core.model.analytics.PeriodKind
import com.impeesa.finance.core.model.analytics.Periods
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.component.DonutChart
import com.impeesa.finance.core.ui.component.DonutSlice
import com.impeesa.finance.core.ui.component.EmptyState
import com.impeesa.finance.core.ui.component.GroupedBarChart
import com.impeesa.finance.core.ui.component.ProgressBar
import com.impeesa.finance.core.ui.component.SegmentedControl
import com.impeesa.finance.core.ui.component.SurfaceCard
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.ui.analytics.MoneyOverview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

@Immutable
data class InsightsUiState(
    val loading: Boolean = true,
    val kind: PeriodKind = PeriodKind.MONTH,
    val rangeLabel: String = "",
    val total: Money = Money.ZERO,
    /** Like-for-like change vs the previous period, as a fraction; null without a previous period. */
    val change: Double? = null,
    val categories: List<CategoryTotal> = emptyList(),
    val cashFlow: List<PeriodFlow> = emptyList(),
    val insights: List<Insight> = emptyList(),
)

class InsightsViewModel(
    private val analytics: AnalyticsRepository,
    overviewSource: MoneyOverview,
    prefs: AppPrefs,
    private val clock: Clock,
) : ViewModel() {
    private val today = LocalDate.now(clock)
    private val kind = MutableStateFlow(PeriodKind.MONTH)

    private val overview = overviewSource.budgets(today, prefs.monthStartDay).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    private val insights = overviewSource.insights(today, overview, prefs.dismissedInsights) { clock.millis() }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<InsightsUiState> = combine(kind, prefs.monthStartDay) { k, s -> k to s }.flatMapLatest { (k, startDay) ->
        val period = Periods.of(k, today, startDay)
        val previous = Periods.of(k, today, startDay, -1)
        val cashPeriods = (5 downTo 0).map { Periods.budget(today, startDay, -it) }
        val from = minOf(previous.start, cashPeriods.first().start)
        combine(
            analytics.observeCategoryTotals(period),
            analytics.observeDaily(from, today),
            insights,
        ) { totals, days, list ->
            val soFar = Period(period.start, minOf(today, period.end))
            val current = Flows.spending(days, soFar)
            val before = Flows.spending(days, Periods.likeForLike(period, previous, today))
            InsightsUiState(
                loading = false,
                kind = k,
                rangeLabel = rangeLabel(k, period, startDay),
                total = current,
                change = if (before.paise > 0) (current.paise - before.paise).toDouble() / before.paise else null,
                categories = totals,
                cashFlow = cashPeriods.map { Flows.flow(days, it) },
                insights = list,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState())

    fun pick(k: PeriodKind) {
        kind.value = k
    }

    private fun rangeLabel(k: PeriodKind, period: Period, startDay: Int): String = when (k) {
        PeriodKind.WEEK -> "${period.start.format(SHORT)} – ${period.end.format(SHORT)}"
        PeriodKind.MONTH -> MoneyOverview.periodLabel(period, startDay)
        PeriodKind.YEAR -> period.start.year.toString()
    }

    private companion object {
        val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    }
}

@Composable
fun InsightsScreen(onHabits: () -> Unit, onBudgets: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { InsightsViewModel(container.analyticsRepository, container.moneyOverview, container.prefs, container.clock) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ImpeesaTheme.colors

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Insights", style = ImpeesaTheme.type.display, color = colors.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
                Text(
                    state.rangeLabel,
                    style = ImpeesaTheme.type.label,
                    color = colors.textPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(colors.card).border(1.dp, colors.line, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        item(key = "tabs") {
            SegmentedControl(listOf("Week", "Month", "Year"), state.kind.ordinal, { viewModel.pick(PeriodKind.entries[it]) })
        }
        if (state.loading) return@LazyColumn

        item(key = "donut") { CategoryCard(state) }
        item(key = "cash") { CashFlowCard(state.cashFlow) }

        if (state.insights.isNotEmpty()) {
            items(state.insights, key = { it.key }) { insight ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.card).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("✦", style = ImpeesaTheme.type.label, color = if (insight.positive) colors.lime else colors.amber)
                    Text(insight.text, style = ImpeesaTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                }
            }
        }

        item(key = "habits") { LinkCard("Your spending habits", "Daily calendar, patterns, subscriptions and top merchants", colors.shopping, onHabits) }
        item(key = "budgets") { LinkCard("Budgets", "Limits for the month and each category", colors.lime, onBudgets) }
    }
}

@Composable
private fun CategoryCard(state: InsightsUiState) {
    val colors = ImpeesaTheme.colors
    if (state.categories.isEmpty()) {
        EmptyState(title = "Nothing spent yet", body = "Spending for this period appears here by category.", actionLabel = null, onAction = {})
        return
    }
    val total = state.categories.sumOf { it.total.paise }.coerceAtLeast(1)
    val max = state.categories.maxOf { it.total.paise }.coerceAtLeast(1)
    val top = state.categories.take(7)
    SurfaceCard(padding = 20.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            DonutChart(
                slices = state.categories.map { DonutSlice(it.total.paise.coerceAtLeast(0).toFloat(), it.color?.let(::Color) ?: colors.other) },
                description = "Spent ${MoneyFormat.spoken(state.total)}. " + top.joinToString(". ") { "${it.name} ${it.total.paise * 100 / total} percent" },
                size = 210.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Total spent", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                    Text(MoneyFormat.format(state.total), style = ImpeesaTheme.type.headline, color = colors.textPrimary)
                    state.change?.let { change ->
                        val down = change < 0
                        Text(
                            "${if (down) "↓" else "↑"} ${(abs(change) * 100).toInt()}% vs last",
                            style = ImpeesaTheme.type.caption,
                            color = if (down) colors.lime else colors.coral,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.raised).padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                top.forEach { c ->
                    val tint = c.color?.let(::Color) ?: colors.other
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(tint))
                            Text(c.name, style = ImpeesaTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                            Text("${c.total.paise * 100 / total}%", style = ImpeesaTheme.type.caption, color = colors.textTertiary, modifier = Modifier.width(40.dp))
                            Text(MoneyFormat.format(c.total), style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                        }
                        ProgressBar(fraction = c.total.paise.toFloat() / max, color = tint, height = 6.dp)
                    }
                }
                if (state.categories.size > top.size) {
                    Text("+ ${state.categories.size - top.size} more categories", style = ImpeesaTheme.type.caption, color = colors.textTertiary)
                }
            }
        }
    }
}

@Composable
private fun CashFlowCard(flows: List<PeriodFlow>) {
    val colors = ImpeesaTheme.colors
    if (flows.isEmpty()) return
    val saved = Money(flows.sumOf { it.saved.paise })
    val income = flows.sumOf { it.income.paise }
    val rate = if (income > 0) saved.paise * 100 / income else null
    SurfaceCard(padding = 20.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Cash flow", style = ImpeesaTheme.type.title, color = colors.textPrimary)
                Text("Last 6 periods", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
            Legend(colors.lime, "Income")
            Legend(colors.coral, "Spent")
        }
        GroupedBarChart(
            pairs = flows.map { it.income.paise to it.spent.paise.coerceAtLeast(0) },
            labels = flows.map { it.period.end.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) },
            description = "Cash flow, last 6 periods: " + flows.joinToString("; ") {
                it.period.end.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " income " + MoneyFormat.spoken(it.income) + ", spent " + MoneyFormat.spoken(it.spent)
            },
            modifier = Modifier.padding(top = 16.dp),
        )
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Saved in 6 periods", MoneyFormat.format(saved), Modifier.weight(1f), if (saved.isNegative) colors.coral else colors.textPrimary)
            Stat("Savings rate", rate?.let { "$it%" } ?: "—", Modifier.weight(1f), colors.lime)
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = ImpeesaTheme.type.caption, color = ImpeesaTheme.colors.textSecondary)
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, valueColor: Color) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(ImpeesaTheme.colors.raised).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = ImpeesaTheme.type.caption, color = ImpeesaTheme.colors.textSecondary)
        Text(value, style = ImpeesaTheme.type.title, color = valueColor)
    }
}

@Composable
internal fun LinkCard(title: String, subtitle: String, tint: Color, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.card).border(1.dp, colors.line, RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(painterResource(UiR.drawable.ic_insights), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
            Text(subtitle, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
        }
        Icon(painterResource(UiR.drawable.ic_back), contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp).rotate(180f))
    }
}
