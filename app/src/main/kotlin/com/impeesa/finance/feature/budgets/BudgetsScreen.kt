package com.impeesa.finance.feature.budgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.impeesa.finance.core.data.BudgetRepository
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.analytics.Periods
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.component.CircleIconButton
import com.impeesa.finance.core.ui.component.EmptyState
import com.impeesa.finance.core.ui.component.ImpeesaChip
import com.impeesa.finance.core.ui.component.ProgressBar
import com.impeesa.finance.core.ui.component.ProgressRing
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.UndoEvent
import com.impeesa.finance.ui.analytics.BudgetLine
import com.impeesa.finance.ui.analytics.BudgetOverview
import com.impeesa.finance.ui.analytics.MoneyOverview
import com.impeesa.finance.ui.analytics.Rebalance
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

@Immutable
data class BudgetsUiState(
    val loading: Boolean = true,
    val overview: BudgetOverview? = null,
    val startDay: Int = 1,
    val showRebalance: Boolean = false,
)

class BudgetsViewModel(
    overview: MoneyOverview,
    private val budgets: BudgetRepository,
    private val prefs: AppPrefs,
    private val undo: UndoBus,
    private val clock: Clock,
) : ViewModel() {
    private val today = LocalDate.now(clock)

    val state: StateFlow<BudgetsUiState> = combine(
        overview.budgets(today, prefs.monthStartDay),
        prefs.monthStartDay,
        prefs.dismissedInsights,
    ) { o, start, dismissed ->
        BudgetsUiState(
            loading = false,
            overview = o,
            startDay = start,
            showRebalance = o.rebalance != null && (dismissed[o.rebalance.key] ?: 0L) <= clock.millis(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState())

    fun setStartDay(day: Int) {
        val before = prefs.monthStartDay.value
        prefs.setMonthStartDay(day)
        viewModelScope.launch { undo.post(UndoEvent("Budget month now starts ${startDayLabel(day)}") { prefs.setMonthStartDay(before) }) }
    }

    fun applyRebalance(r: Rebalance) {
        viewModelScope.launch {
            val before = listOfNotNull(r.raise.budget, r.trim?.budget)
            budgets.save(r.raise.budget.copy(amount = r.to))
            if (r.trim != null && r.trimTo != null) budgets.save(r.trim.budget.copy(amount = r.trimTo))
            undo.post(UndoEvent("Budgets updated") { before.forEach { budgets.save(it) } })
        }
    }

    fun dismissRebalance(r: Rebalance) = prefs.dismissInsight(r.key, clock.millis() + 30L * 24 * 60 * 60 * 1000)
}

fun startDayLabel(day: Int): String = when {
    day >= Periods.LAST_DAY -> "on the last day"
    else -> "on the " + day + when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetsScreen(onBack: () -> Unit, onNew: () -> Unit, onEdit: (Long) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { BudgetsViewModel(container.moneyOverview, container.budgetRepository, container.prefs, container.undo, container.clock) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickStart by rememberSaveable { mutableStateOf(false) }
    val colors = ImpeesaTheme.colors
    val o = state.overview

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            ScreenHeader(title = "Budgets", onBack = onBack) {
                CircleIconButton(icon = UiR.drawable.ic_plus, contentDescription = "New budget", onClick = onNew)
            }
        }
        if (state.loading || o == null) return@LazyColumn

        item(key = "overall") { OverallCard(o, onNew = onNew, onEdit = onEdit) }

        if (state.showRebalance) {
            o.rebalance?.let { r ->
                item(key = "rebalance") { RebalanceCard(r, onApply = { viewModel.applyRebalance(r) }, onDismiss = { viewModel.dismissRebalance(r) }) }
            }
        }

        if (o.categories.isNotEmpty()) {
            item(key = "cats-label") { SectionLabel("Categories") }
            item(key = "cats") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.card).padding(horizontal = 18.dp, vertical = 6.dp)) {
                    o.categories.forEachIndexed { i, line ->
                        CategoryBudgetRow(line, o.paceFraction ?: (o.period.elapsedDays(o.today).toFloat() / o.period.days), onClick = { onEdit(line.budget.id) })
                        if (i < o.categories.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.raised))
                    }
                }
            }
        } else if (o.overall != null) {
            item(key = "cats-empty") {
                Text(
                    "Add category budgets to see where the month goes — tap + to start with your biggest category.",
                    style = ImpeesaTheme.type.caption,
                    color = colors.textTertiary,
                )
            }
        }

        item(key = "start") {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.card).clickable(role = Role.Button) { pickStart = true }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Budget month starts ${startDayLabel(state.startDay)}", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                    Text(
                        o.recurring.salaryDay?.takeIf { it != state.startDay }?.let { "Your salary usually arrives ${startDayLabel(it)}. Tap to match it." }
                            ?: "Match it to your salary day so budgets follow your pay cycle.",
                        style = ImpeesaTheme.type.caption,
                        color = colors.textSecondary,
                    )
                }
                Text("Change", style = ImpeesaTheme.type.label, color = colors.lime)
            }
        }
    }

    if (pickStart) {
        ModalBottomSheet(onDismissRequest = { pickStart = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
            StartDayPicker(
                selected = state.startDay,
                salaryDay = o?.recurring?.salaryDay,
                onPick = { day ->
                    pickStart = false
                    viewModel.setStartDay(day)
                },
            )
        }
    }
}

@Composable
private fun OverallCard(o: BudgetOverview, onNew: () -> Unit, onEdit: (Long) -> Unit) {
    val colors = ImpeesaTheme.colors
    val overall = o.overall
    if (overall == null) {
        EmptyState(
            title = "No monthly budget yet",
            body = "Set one amount for the month and Kosh shows what's safe to spend each day, keeping money aside for bills it has spotted.",
            actionLabel = "Set a budget",
            onAction = onNew,
        )
        return
    }
    val pct = (overall.status.usedFraction * 100).toInt()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(colors.card).clickable(role = Role.Button) { onEdit(overall.budget.id) }.padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ProgressRing(
            fraction = overall.status.usedFraction,
            description = "$pct% of the monthly budget used; ${(o.paceFraction ?: 0f).times(100).toInt()}% of the period has passed",
            color = if (overall.status.over) colors.coral else colors.lime,
            marker = o.paceFraction,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$pct%", style = ImpeesaTheme.type.display, color = colors.textPrimary)
                Text("used", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (overall.status.over) "Over by" else "Left this period", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                Text(MoneyFormat.format(overall.status.remaining.abs()), style = ImpeesaTheme.type.headline, color = if (overall.status.over) colors.coral else colors.textPrimary)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Monthly budget", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                Text(
                    MoneyFormat.format(overall.budget.amount) + if (overall.status.carried.paise > 0) " + ${MoneyFormat.format(overall.status.carried)} carried" else "",
                    style = ImpeesaTheme.type.bodyStrong,
                    color = colors.textPrimary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(width = 10.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(colors.textPrimary))
                Text("Today's pace: ${((o.paceFraction ?: 0f) * 100).toInt()}%", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun RebalanceCard(r: Rebalance, onApply: () -> Unit, onDismiss: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(colors.amber.copy(alpha = 0.08f)).border(1.dp, colors.amber.copy(alpha = 0.28f), RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "You've gone over ${r.raise.name} in each of the last 3 periods. Raise it to ${MoneyFormat.format(r.to)}" +
                (r.trim?.let { " and trim ${it.name} to ${MoneyFormat.format(r.trimTo!!)} to keep the total the same?" } ?: "?"),
            style = ImpeesaTheme.type.body,
            color = colors.textPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Apply",
                style = ImpeesaTheme.type.label,
                color = colors.ink,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(colors.amber).clickable(role = Role.Button, onClick = onApply).padding(horizontal = 16.dp, vertical = 9.dp),
            )
            Text(
                "Not now",
                style = ImpeesaTheme.type.label,
                color = colors.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).border(1.dp, colors.line, RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun CategoryBudgetRow(line: BudgetLine, pace: Float, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    val s = line.status
    val tint = line.color?.let { Color(it) } ?: colors.other
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(tint))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.name, style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                Text("${MoneyFormat.format(s.spent)} of ${MoneyFormat.format(s.available)}", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
            when {
                s.over -> Text(
                    "${MoneyFormat.format(s.remaining.abs())} over",
                    style = ImpeesaTheme.type.caption,
                    color = colors.ink,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.coral).padding(horizontal = 9.dp, vertical = 4.dp),
                )
                s.reached(line.budget.alertPercent) -> Text("${MoneyFormat.format(s.remaining)} left", style = ImpeesaTheme.type.label, color = colors.amber)
                else -> Text("${MoneyFormat.format(s.remaining)} left", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
        }
        ProgressBar(fraction = s.usedFraction, color = if (s.over) colors.coral else tint, marker = pace)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartDayPicker(selected: Int, salaryDay: Int?, onPick: (Int) -> Unit) {
    val colors = ImpeesaTheme.colors
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Budget month starts on", style = ImpeesaTheme.type.title, color = colors.textPrimary)
        Text("Budgets, Home and Insights all follow this. Days after the 28th use the last day of short months.", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
        if (salaryDay != null && salaryDay != selected) {
            ImpeesaChip(label = "Salary day · ${startDayLabel(salaryDay).removePrefix("on the ")}", selected = false, onClick = { onPick(salaryDay) }, dotColor = colors.lime)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..28).forEach { day ->
                ImpeesaChip(label = day.toString(), selected = selected == day, onClick = { onPick(day) })
            }
            ImpeesaChip(label = "Last day", selected = selected >= Periods.LAST_DAY, onClick = { onPick(Periods.LAST_DAY) })
        }
    }
}

