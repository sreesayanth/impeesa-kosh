package com.impeesa.finance.feature.budgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.AppPrefs
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.AnalyticsRepository
import com.impeesa.finance.core.data.Budget
import com.impeesa.finance.core.data.BudgetRepository
import com.impeesa.finance.core.data.CategoryRepository
import com.impeesa.finance.core.model.AmountInput
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.model.CategoryKind
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.analytics.BudgetMath
import com.impeesa.finance.core.model.analytics.Periods
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.component.CircleIconButton
import com.impeesa.finance.core.ui.component.ImpeesaChip
import com.impeesa.finance.core.ui.component.ImpeesaTextField
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.UndoEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Clock

@Immutable
data class BudgetForm(
    val id: Long = 0,
    /** Null: the overall monthly budget. */
    val categoryId: Long? = null,
    val amountText: String = "",
    val alertPercent: Int = 80,
    val rollover: Boolean = false,
)

@Immutable
data class BudgetEditorState(
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val form: BudgetForm = BudgetForm(),
    val categories: List<Category> = emptyList(),
    /** Categories (or null for overall) that already have a budget, other than this one. */
    val taken: Set<Long?> = emptySet(),
    val suggestion: Money? = null,
    val lastPeriods: List<Money> = emptyList(),
)

class BudgetEditorViewModel(
    private val budgetId: Long,
    private val budgets: BudgetRepository,
    private val analytics: AnalyticsRepository,
    categories: CategoryRepository,
    private val prefs: AppPrefs,
    private val undo: UndoBus,
    clock: Clock,
) : ViewModel() {
    private val today = LocalDate.now(clock)
    private val form = MutableStateFlow(BudgetForm())
    private val loaded = MutableStateFlow(budgetId == 0L)
    private val closeChannel = Channel<Unit>(Channel.BUFFERED)
    val close: Flow<Unit> = closeChannel.receiveAsFlow()

    init {
        if (budgetId != 0L) {
            viewModelScope.launch {
                budgets.get(budgetId)?.let { b ->
                    form.value = BudgetForm(b.id, b.categoryId, (b.amount.paise / 100).toString(), b.alertPercent, b.rollover)
                }
                loaded.value = true
            }
        }
    }

    /** Spending in the last three periods for the chosen category, for the suggestion (§5.8). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val history: Flow<List<Money>> = form.map { it.categoryId }.distinctUntilChanged().mapLatest { categoryId ->
        val start = prefs.monthStartDay.value
        (3 downTo 1).map { back ->
            val totals = analytics.categoryTotals(Periods.budget(today, start, -back))
            if (categoryId == null) Money(totals.sumOf { it.total.paise }) else totals.firstOrNull { it.categoryId == categoryId }?.total ?: Money.ZERO
        }
    }

    val state: StateFlow<BudgetEditorState> = combine(form, loaded, categories.observe(CategoryKind.EXPENSE), budgets.observe(), history) { f, l, cats, all, hist ->
        BudgetEditorState(
            loaded = l,
            editing = budgetId != 0L,
            form = f,
            categories = cats,
            taken = all.filter { it.id != budgetId }.map { it.categoryId }.toSet(),
            suggestion = BudgetMath.suggest(hist),
            lastPeriods = hist,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetEditorState())

    fun update(transform: (BudgetForm) -> BudgetForm) = form.update(transform)

    fun save() {
        val f = form.value
        val amount = AmountInput.parse(f.amountText) ?: return
        viewModelScope.launch {
            val before = if (budgetId != 0L) budgets.get(budgetId) else null
            val id = budgets.save(Budget(f.id, f.categoryId, amount, f.alertPercent, f.rollover))
            undo.post(
                UndoEvent(if (before == null) "Budget added" else "Budget saved") {
                    if (before == null) budgets.delete(id) else budgets.save(before)
                },
            )
            closeChannel.send(Unit)
        }
    }

    fun delete() {
        viewModelScope.launch {
            budgets.delete(budgetId)?.let { removed -> undo.post(UndoEvent("Budget deleted") { budgets.restore(removed) }) }
            closeChannel.send(Unit)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BudgetEditorScreen(budgetId: Long, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel {
        BudgetEditorViewModel(budgetId, container.budgetRepository, container.analyticsRepository, container.categoryRepository, container.prefs, container.undo, container.clock)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.close.collect { onBack() } }
    val colors = ImpeesaTheme.colors
    val form = state.form
    val amount = AmountInput.parse(form.amountText)
    val clash = form.categoryId in state.taken

    Column(
        Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(title = if (state.editing) "Edit budget" else "New budget", onBack = onBack) {
            if (state.editing) CircleIconButton(icon = UiR.drawable.ic_trash, contentDescription = "Delete budget", onClick = viewModel::delete, tint = colors.coral)
        }
        if (!state.loaded) return@Column

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("For")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ImpeesaChip(label = "Whole month", selected = form.categoryId == null, onClick = { viewModel.update { it.copy(categoryId = null) } }, dotColor = colors.lime)
                state.categories.forEach { c ->
                    ImpeesaChip(label = c.name, selected = form.categoryId == c.id, onClick = { viewModel.update { it.copy(categoryId = c.id) } }, dotColor = Color(c.color))
                }
            }
            if (clash) Text("This already has a budget. Saving replaces it.", style = ImpeesaTheme.type.caption, color = colors.amber)
        }

        ImpeesaTextField(
            value = form.amountText,
            onValueChange = { t -> viewModel.update { it.copy(amountText = t.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }) } },
            label = "Amount per month",
            placeholder = "e.g. 10,000",
            keyboardType = KeyboardType.Decimal,
            prefix = MoneyFormat.symbol.trim(),
        )
        state.suggestion?.let { s ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ImpeesaChip(label = "Use ${MoneyFormat.format(s)}", selected = amount == s, onClick = { viewModel.update { it.copy(amountText = (s.paise / 100).toString()) } }, dotColor = colors.lime)
                Text(
                    "Based on your last 3 periods: " + state.lastPeriods.joinToString(", ") { MoneyFormat.format(it) },
                    style = ImpeesaTheme.type.caption,
                    color = colors.textTertiary,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Warn me at")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(50, 80, 100).forEach { pct ->
                    ImpeesaChip(label = "$pct%", selected = form.alertPercent == pct, onClick = { viewModel.update { it.copy(alertPercent = pct) } })
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.card).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Carry over what's left", style = ImpeesaTheme.type.body, color = colors.textPrimary)
                Text("Unspent money adds to next period. Overspending is not carried.", style = ImpeesaTheme.type.caption, color = colors.textTertiary)
            }
            Switch(
                checked = form.rollover,
                onCheckedChange = { on -> viewModel.update { it.copy(rollover = on) } },
                colors = SwitchDefaults.colors(checkedTrackColor = colors.lime, checkedThumbColor = colors.ink),
            )
        }

        PrimaryButton(text = "Save budget", enabled = amount != null && amount.paise > 0, onClick = viewModel::save)
    }
}
