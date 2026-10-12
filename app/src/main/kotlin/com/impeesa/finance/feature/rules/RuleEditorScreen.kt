package com.impeesa.finance.feature.rules

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
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.AccountRepository
import com.impeesa.finance.core.data.CategorizationRepository
import com.impeesa.finance.core.data.CategoryRepository
import com.impeesa.finance.core.data.RuleDraft
import com.impeesa.finance.core.engine.category.RuleAction
import com.impeesa.finance.core.engine.category.RuleMatch
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.AmountInput
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.model.CategoryKind
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the user is typing; amounts stay text until saved. */
@Immutable
data class RuleForm(
    val id: Long = 0,
    val match: RuleMatch = RuleMatch.MERCHANT,
    val text: String = "",
    val accountId: Long? = null,
    val minText: String = "",
    val maxText: String = "",
    val action: RuleAction = RuleAction.SET_CATEGORY,
    val categoryId: Long? = null,
    val applyToHistory: Boolean = true,
) {
    fun toDraft(accounts: List<Account>): RuleDraft = RuleDraft(
        id = id,
        match = match,
        pattern = if (match in TEXT_MATCHES) text.trim() else "",
        label = when (match) {
            RuleMatch.ACCOUNT -> accounts.firstOrNull { it.id == accountId }?.displayName.orEmpty()
            else -> text.trim()
        },
        accountId = accountId.takeIf { match == RuleMatch.ACCOUNT },
        minPaise = AmountInput.parse(minText)?.paise.takeIf { match == RuleMatch.AMOUNT_RANGE },
        maxPaise = AmountInput.parse(maxText)?.paise.takeIf { match == RuleMatch.AMOUNT_RANGE },
        action = action,
        categoryId = categoryId.takeIf { action == RuleAction.SET_CATEGORY },
    )

    companion object {
        val TEXT_MATCHES = setOf(RuleMatch.MERCHANT, RuleMatch.VPA, RuleMatch.CONTAINS)
    }
}

@Immutable
data class RuleEditorState(
    val form: RuleForm = RuleForm(),
    val editing: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val expense: List<Category> = emptyList(),
    val income: List<Category> = emptyList(),
    val valid: Boolean = false,
    val historyCount: Int = 0,
)

class RuleEditorViewModel(
    private val ruleId: Long,
    private val categorization: CategorizationRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val undo: UndoBus,
) : ViewModel() {
    private val form = MutableStateFlow(RuleForm())
    private val closeChannel = Channel<Unit>(Channel.BUFFERED)
    val close: Flow<Unit> = closeChannel.receiveAsFlow()
    private var accountList: List<Account> = emptyList()

    init {
        if (ruleId != 0L) {
            viewModelScope.launch {
                categorization.rule(ruleId)?.let { d ->
                    form.value = RuleForm(
                        id = d.id,
                        match = d.match,
                        text = if (d.match == RuleMatch.MERCHANT || d.match == RuleMatch.VPA) d.label.ifEmpty { d.pattern } else d.pattern,
                        accountId = d.accountId,
                        minText = d.minPaise?.let { (it / 100).toString() }.orEmpty(),
                        maxText = d.maxPaise?.let { (it / 100).toString() }.orEmpty(),
                        action = d.action,
                        categoryId = d.categoryId,
                        applyToHistory = false,
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val historyCount: Flow<Int> = form.mapLatest { f ->
        delay(250) // let typing settle before scanning history
        categorization.countHistoryMatches(f.toDraft(accountList))
    }

    val state: StateFlow<RuleEditorState> = combine(
        form,
        accounts.observeAccounts(),
        categories.observe(CategoryKind.EXPENSE),
        categories.observe(CategoryKind.INCOME),
        historyCount,
    ) { f, accts, expense, income, count ->
        accountList = accts
        RuleEditorState(
            form = f,
            editing = ruleId != 0L,
            accounts = accts,
            expense = expense,
            income = income,
            valid = f.toDraft(accts).isValid,
            historyCount = count,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RuleEditorState(editing = ruleId != 0L))

    fun update(transform: (RuleForm) -> RuleForm) = form.update(transform)

    fun save() {
        val f = form.value
        val draft = f.toDraft(accountList)
        if (!draft.isValid) return
        viewModelScope.launch {
            val id = categorization.saveRule(draft)
            val snapshots = if (f.applyToHistory) categorization.applyRuleToHistory(id) else emptyList()
            if (snapshots.isEmpty()) {
                undo.post(UndoEvent("Rule saved"))
            } else {
                undo.post(UndoEvent("Rule saved · updated ${snapshots.size} transactions") { categorization.restore(snapshots) })
            }
            closeChannel.send(Unit)
        }
    }

    fun delete() {
        viewModelScope.launch {
            val removed = categorization.deleteRule(ruleId)
            if (removed != null) undo.post(UndoEvent("Rule deleted") { categorization.restoreRule(removed) })
            closeChannel.send(Unit)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(ruleId: Long, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel {
        RuleEditorViewModel(ruleId, container.categorizationRepository, container.accountRepository, container.categoryRepository, container.undo)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.close.collect { onBack() } }
    val colors = ImpeesaTheme.colors
    val form = state.form

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(title = if (state.editing) "Edit rule" else "New rule", onBack = onBack) {
            if (state.editing) CircleIconButton(icon = UiR.drawable.ic_trash, contentDescription = "Delete rule", onClick = viewModel::delete, tint = colors.coral)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("When")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    RuleMatch.MERCHANT to "Merchant",
                    RuleMatch.VPA to "UPI ID",
                    RuleMatch.CONTAINS to "Message contains",
                    RuleMatch.ACCOUNT to "Account",
                    RuleMatch.AMOUNT_RANGE to "Amount",
                ).forEach { (match, label) ->
                    ImpeesaChip(label = label, selected = form.match == match, onClick = { viewModel.update { it.copy(match = match) } })
                }
            }
        }

        when (form.match) {
            RuleMatch.MERCHANT -> ImpeesaTextField(form.text, { t -> viewModel.update { it.copy(text = t) } }, label = "Merchant name", placeholder = "e.g. Swiggy")
            RuleMatch.VPA -> ImpeesaTextField(form.text, { t -> viewModel.update { it.copy(text = t) } }, label = "UPI ID", placeholder = "e.g. rahul.m@okaxis", keyboardType = KeyboardType.Email)
            RuleMatch.CONTAINS -> ImpeesaTextField(form.text, { t -> viewModel.update { it.copy(text = t) } }, label = "Text in the message or payee", placeholder = "e.g. PETROL")
            RuleMatch.ACCOUNT -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Paid with")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.accounts.forEach { account ->
                        ImpeesaChip(label = account.displayName, selected = form.accountId == account.id, onClick = { viewModel.update { it.copy(accountId = account.id) } })
                    }
                }
            }
            RuleMatch.AMOUNT_RANGE -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ImpeesaTextField(form.minText, { t -> viewModel.update { it.copy(minText = t) } }, label = "From", placeholder = "Any", keyboardType = KeyboardType.Decimal, prefix = MoneyFormat.symbol.trim(), modifier = Modifier.weight(1f))
                ImpeesaTextField(form.maxText, { t -> viewModel.update { it.copy(maxText = t) } }, label = "Up to", placeholder = "Any", keyboardType = KeyboardType.Decimal, prefix = MoneyFormat.symbol.trim(), modifier = Modifier.weight(1f))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Then")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    RuleAction.SET_CATEGORY to "Set category",
                    RuleAction.MARK_TRANSFER to "Mark as transfer",
                    RuleAction.EXCLUDE to "Leave out of spending",
                    RuleAction.ASK to "Ask me each time",
                ).forEach { (action, label) ->
                    ImpeesaChip(label = label, selected = form.action == action, onClick = { viewModel.update { it.copy(action = action) } })
                }
            }
        }

        if (form.action == RuleAction.SET_CATEGORY) {
            CategoryChips("Spending", state.expense, form.categoryId) { id -> viewModel.update { it.copy(categoryId = id) } }
            CategoryChips("Income", state.income, form.categoryId) { id -> viewModel.update { it.copy(categoryId = id) } }
        }

        if (form.action != RuleAction.ASK) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(colors.card).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Also apply to earlier transactions", style = ImpeesaTheme.type.body, color = colors.textPrimary)
                    Text(
                        when (state.historyCount) {
                            0 -> "Nothing earlier matches"
                            1 -> "1 earlier transaction matches"
                            else -> "${state.historyCount} earlier transactions match"
                        } + ". Ones you set by hand are never changed.",
                        style = ImpeesaTheme.type.caption,
                        color = colors.textTertiary,
                    )
                }
                Switch(
                    checked = form.applyToHistory && state.historyCount > 0,
                    enabled = state.historyCount > 0,
                    onCheckedChange = { on -> viewModel.update { it.copy(applyToHistory = on) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = colors.lime, checkedThumbColor = colors.ink),
                )
            }
        } else {
            Text(
                "Matching transactions wait in Review so you can pick the category each time.",
                style = ImpeesaTheme.type.caption,
                color = colors.textTertiary,
            )
        }

        PrimaryButton(text = "Save rule", enabled = state.valid, onClick = viewModel::save)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(label: String, categories: List<Category>, selected: Long?, onPick: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { category ->
                ImpeesaChip(label = category.name, selected = category.id == selected, onClick = { onPick(category.id) }, dotColor = Color(category.color))
            }
        }
    }
}
