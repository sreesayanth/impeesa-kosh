package com.impeesa.finance.feature.accounts

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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.AccountRepository
import com.impeesa.finance.core.data.AccountSettings
import com.impeesa.finance.core.data.CardStatement
import com.impeesa.finance.core.data.CategoryTotal
import com.impeesa.finance.core.data.LedgerRepository
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.AmountInput
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.analytics.CardCycle
import com.impeesa.finance.core.model.analytics.Period
import com.impeesa.finance.core.ui.R as UiR
import com.impeesa.finance.core.ui.component.AmountText
import com.impeesa.finance.core.ui.component.CircleIconButton
import com.impeesa.finance.core.ui.component.ImpeesaTextField
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.component.ProgressBar
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.component.SurfaceCard
import com.impeesa.finance.core.ui.component.TxnRow
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.ui.TxnUi
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.UndoEvent
import com.impeesa.finance.ui.toUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Immutable
data class AccountDetailState(
    val loading: Boolean = true,
    val account: Account? = null,
    val settings: AccountSettings? = null,
    val statements: List<CardStatement> = emptyList(),
    val cycle: Period? = null,
    val cycleSpend: List<CategoryTotal> = emptyList(),
    val recent: List<TxnUi> = emptyList(),
    val today: LocalDate = LocalDate.now(),
)

class AccountDetailViewModel(
    private val id: Long,
    accounts: AccountRepository,
    private val ledger: LedgerRepository,
    private val undo: UndoBus,
    private val clock: Clock,
) : ViewModel() {
    private val today = LocalDate.now(clock)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cycleSpend = ledger.observeSettings(id).flatMapLatest { s ->
        if (s?.type != AccountType.CARD) return@flatMapLatest flowOf(null to emptyList())
        val cycle = s.statementDay?.let { CardCycle.currentCycle(today, it) } ?: Period(today.minusDays(29), today)
        ledger.observeCycleSpend(id, cycle).map { cycle to it }
    }

    val state: StateFlow<AccountDetailState> = combine(
        accounts.observeAccounts().map { list -> list.firstOrNull { it.id == id } },
        ledger.observeSettings(id),
        ledger.observeStatements(id),
        cycleSpend,
        ledger.observeTransactions(id, 20),
    ) { account, settings, statements, (cycle, spend), rows ->
        AccountDetailState(
            loading = false,
            account = account,
            settings = settings,
            statements = statements,
            cycle = cycle,
            cycleSpend = spend,
            recent = rows.map { it.toUi(clock.zone, today) },
            today = today,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailState())

    fun setBalance(amount: Money, owedOnCard: Boolean) {
        viewModelScope.launch {
            ledger.setBalance(id, if (owedOnCard) -amount else amount)
            undo.post(UndoEvent(if (owedOnCard) "Amount owed updated" else "Balance updated"))
        }
    }

    fun setLowBalance(threshold: Money?) {
        viewModelScope.launch {
            ledger.setLowBalance(id, threshold)
            undo.post(UndoEvent(if (threshold == null) "Low-balance alert off" else "We'll alert you below ${MoneyFormat.format(threshold)}"))
        }
    }

    fun markPaid(statement: CardStatement) {
        viewModelScope.launch {
            val before = ledger.markPaid(statement.id) ?: return@launch
            undo.post(UndoEvent("Bill marked as paid") { ledger.restoreStatement(before) })
        }
    }

    fun addBill(total: Money, min: Money?, dueDay: Int) {
        viewModelScope.launch {
            val due = CardCycle.nextDueDate(today, dueDay)
            val newId = ledger.addStatement(id, total, min, due)
            undo.post(UndoEvent("Bill added · due ${due.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))}") { ledger.deleteStatement(newId) })
        }
    }
}

private enum class Sheet { BALANCE, LOW, BILL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(id: Long, onBack: () -> Unit, onEdit: () -> Unit, onOpenTransaction: (Long) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { AccountDetailViewModel(id, container.accountRepository, container.ledgerRepository, container.undo, container.clock) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<Sheet?>(null) }
    val colors = ImpeesaTheme.colors
    val account = state.account

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(colors.ink).statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            ScreenHeader(title = account?.name ?: "Account", onBack = onBack) {
                if (account != null) CircleIconButton(icon = UiR.drawable.ic_edit, contentDescription = "Edit account", onClick = onEdit)
            }
        }
        if (state.loading) return@LazyColumn
        if (account == null) {
            item(key = "gone") { Text("This account was archived.", style = ImpeesaTheme.type.body, color = colors.textSecondary) }
            return@LazyColumn
        }
        val isCard = account.type == AccountType.CARD

        item(key = "balance") {
            if (isCard) CardSummary(account, onSetOwed = { sheet = Sheet.BALANCE }) else BalanceSummary(account, onSet = { sheet = Sheet.BALANCE }, onLow = { sheet = Sheet.LOW })
        }

        if (isCard) {
            item(key = "bill") {
                BillCard(state, onMarkPaid = viewModel::markPaid, onAdd = { sheet = Sheet.BILL })
            }
            if (state.cycleSpend.isNotEmpty()) {
                item(key = "cycle") {
                    SurfaceCard(padding = 18.dp) {
                        val cycle = state.cycle
                        Text("This cycle by category", style = ImpeesaTheme.type.title, color = colors.textPrimary)
                        if (cycle != null) Text("${cycle.start.format(SHORT)} – ${cycle.end.format(SHORT)}", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                        val max = state.cycleSpend.maxOf { it.total.paise }.coerceAtLeast(1)
                        state.cycleSpend.take(6).forEach { c ->
                            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row {
                                    Text(c.name, style = ImpeesaTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                                    Text(MoneyFormat.format(c.total), style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                                }
                                ProgressBar(fraction = c.total.paise.toFloat() / max, color = c.color?.let(::Color) ?: colors.other, height = 6.dp)
                            }
                        }
                    }
                }
            }
        }

        if (state.recent.isNotEmpty()) {
            item(key = "recent-label") { SectionLabel("Recent") }
            item(key = "recent") {
                SurfaceCard(padding = 14.dp) {
                    state.recent.forEach { txn ->
                        TxnRow(
                            title = txn.title,
                            subtitle = txn.subtitle,
                            amount = txn.amount,
                            tileColor = txn.color?.let { Color(it) } ?: colors.other,
                            neutral = txn.isTransfer,
                            onClick = { onOpenTransaction(txn.id) },
                        )
                    }
                }
            }
        }
    }

    sheet?.let { which ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
            when (which) {
                Sheet.BALANCE -> AmountSheet(
                    title = when (account?.type) {
                        AccountType.CARD -> "Amount owed now"
                        AccountType.CASH -> "Count your cash"
                        else -> "Current balance"
                    },
                    hint = if (account?.type == AccountType.CARD) "From your card app or statement. Impeesa keeps counting from here." else "From your bank app or passbook. Impeesa keeps counting from here.",
                    initial = account?.let { if (it.type == AccountType.CARD) it.owed else it.balance },
                    button = "Save",
                ) { amount ->
                    sheet = null
                    amount?.let { viewModel.setBalance(it, account?.type == AccountType.CARD) }
                }
                Sheet.LOW -> AmountSheet(
                    title = "Alert me when below",
                    hint = "Leave empty to turn this off. Turn on Low balance in Alert settings too.",
                    initial = account?.lowBalance,
                    button = "Save",
                    allowEmpty = true,
                ) { amount ->
                    sheet = null
                    viewModel.setLowBalance(amount)
                }
                Sheet.BILL -> BillSheet(defaultDueDay = state.settings?.dueDay) { total, min, dueDay ->
                    sheet = null
                    viewModel.addBill(total, min, dueDay)
                }
            }
        }
    }
}

private val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val SYNCED: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH)

@Composable
private fun syncLabel(account: Account): String {
    val zone = LocalAppContainer.current.clock.zone
    val at = account.syncedAt ?: return "Calculated from your starting balance and transactions"
    val time = Instant.ofEpochMilli(at).atZone(zone).format(SYNCED)
    return if (account.syncedFromBank) "From your bank's SMS · $time" else "Set by you · $time"
}

@Composable
private fun BalanceSummary(account: Account, onSet: () -> Unit, onLow: () -> Unit) {
    val colors = ImpeesaTheme.colors
    SurfaceCard(padding = 20.dp) {
        Text(if (account.type == AccountType.CASH) "Cash in hand" else "Balance", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
        AmountText(money = account.balance, style = ImpeesaTheme.type.hero, color = if (account.balance.isNegative) colors.coral else colors.textPrimary, modifier = Modifier.padding(top = 4.dp))
        Text(syncLabel(account), style = ImpeesaTheme.type.caption, color = if (account.syncedFromBank) colors.lime else colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (account.type == AccountType.CASH) "Count cash" else "Set balance", onSet)
            Pill(account.lowBalance?.let { "Alert below ${MoneyFormat.format(it)}" } ?: "Low-balance alert", onLow)
        }
    }
}

@Composable
private fun CardSummary(account: Account, onSetOwed: () -> Unit) {
    val colors = ImpeesaTheme.colors
    val utilisation = CardCycle.utilisation(account.owed, account.creditLimit)
    SurfaceCard(padding = 20.dp) {
        Text("Owed", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
        AmountText(money = account.owed, style = ImpeesaTheme.type.hero, modifier = Modifier.padding(top = 4.dp))
        Text(syncLabel(account), style = ImpeesaTheme.type.caption, color = if (account.syncedFromBank) colors.lime else colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
        val limit = account.creditLimit
        if (limit != null && utilisation != null) {
            Row(Modifier.padding(top = 16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Available", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                    Text(MoneyFormat.format(Money((limit.paise - account.owed.paise).coerceAtLeast(0))), style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Limit", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                    Text(MoneyFormat.format(limit), style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                }
            }
            ProgressBar(
                fraction = utilisation,
                color = if (utilisation > CardCycle.HEALTHY_UTILISATION) colors.amber else colors.lime,
                marker = CardCycle.HEALTHY_UTILISATION,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                "${(utilisation * 100).toInt()}% used" + if (utilisation > CardCycle.HEALTHY_UTILISATION) " · keeping it under 30% helps your credit score" else " · healthy",
                style = ImpeesaTheme.type.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            Text("Add the credit limit (Edit) to see how much is available.", style = ImpeesaTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(top = 12.dp))
        }
        Row(Modifier.padding(top = 16.dp)) { Pill("Set amount owed", onSetOwed) }
    }
}

@Composable
private fun BillCard(state: AccountDetailState, onMarkPaid: (CardStatement) -> Unit, onAdd: () -> Unit) {
    val colors = ImpeesaTheme.colors
    val current = state.statements.firstOrNull()
    SurfaceCard(padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bill", style = ImpeesaTheme.type.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text("Add bill", style = ImpeesaTheme.type.label, color = colors.lime, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onAdd).padding(6.dp))
        }
        if (current == null) {
            Text(
                state.settings?.dueDay?.let { "No statement yet. Next due date: ${CardCycle.nextDueDate(state.today, it).format(SHORT)}." }
                    ?: "No statement yet. Bills appear here when your card's statement SMS arrives, or add one.",
                style = ImpeesaTheme.type.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@SurfaceCard
        }
        val days = ChronoUnit.DAYS.between(state.today, current.dueDate)
        val (status, tint) = when {
            current.isPaid -> "Paid" to colors.lime
            days < 0 -> "Overdue by ${-days} ${if (days == -1L) "day" else "days"}" to colors.coral
            days == 0L -> "Due today" to colors.coral
            days <= 3 -> "Due in $days ${if (days == 1L) "day" else "days"}" to colors.amber
            else -> "Due ${current.dueDate.format(SHORT)}" to colors.textSecondary
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(current.totalDue?.let { MoneyFormat.format(it) } ?: "Amount not known", style = ImpeesaTheme.type.headline, color = colors.textPrimary)
                current.minDue?.let { Text("Minimum ${MoneyFormat.format(it)}", style = ImpeesaTheme.type.caption, color = colors.textSecondary) }
            }
            Text(
                status,
                style = ImpeesaTheme.type.caption,
                color = if (current.isPaid) colors.ink else tint,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (current.isPaid) colors.lime else tint.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (!current.isPaid && current.paid.paise > 0) {
            Text("${MoneyFormat.format(current.paid)} paid so far", style = ImpeesaTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
        }
        if (!current.isPaid) {
            Box(
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, colors.line, RoundedCornerShape(14.dp)).clickable(role = Role.Button) { onMarkPaid(current) }.padding(12.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Mark as paid", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary) }
            Text("Payments to this card are matched automatically when their SMS arrives.", style = ImpeesaTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(top = 8.dp))
        }
        val older = state.statements.drop(1)
        if (older.isNotEmpty()) {
            SectionLabel("Earlier", Modifier.padding(top = 16.dp))
            older.forEach { s ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.isPaid) colors.lime else colors.coral))
                    Text("  Due ${s.dueDate.format(SHORT)}", style = ImpeesaTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(s.totalDue?.let { MoneyFormat.format(it) } ?: "—", style = ImpeesaTheme.type.body, color = colors.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    val colors = ImpeesaTheme.colors
    Text(
        label,
        style = ImpeesaTheme.type.label,
        color = colors.textPrimary,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(colors.raised).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun AmountSheet(title: String, hint: String, initial: Money?, button: String, allowEmpty: Boolean = false, onDone: (Money?) -> Unit) {
    val colors = ImpeesaTheme.colors
    var text by rememberSaveable { mutableStateOf(initial?.takeIf { !it.isZero }?.let { AmountInput.of(it).text }.orEmpty()) }
    val amount = AmountInput.parse(text)
    Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = ImpeesaTheme.type.title, color = colors.textPrimary)
        ImpeesaTextField(value = text, onValueChange = { text = it.take(16) }, label = "Amount", placeholder = "0", keyboardType = KeyboardType.Decimal, prefix = "₹")
        Text(hint, style = ImpeesaTheme.type.caption, color = colors.textSecondary)
        PrimaryButton(text = button, enabled = amount != null || (allowEmpty && text.isBlank()), onClick = { onDone(amount) })
    }
}

@Composable
private fun BillSheet(defaultDueDay: Int?, onDone: (Money, Money?, Int) -> Unit) {
    val colors = ImpeesaTheme.colors
    var total by rememberSaveable { mutableStateOf("") }
    var min by rememberSaveable { mutableStateOf("") }
    var day by rememberSaveable { mutableStateOf(defaultDueDay?.toString().orEmpty()) }
    val totalAmount = AmountInput.parse(total)
    val dueDay = day.toIntOrNull()?.takeIf { it in 1..31 }
    Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Add a bill", style = ImpeesaTheme.type.title, color = colors.textPrimary)
        ImpeesaTextField(value = total, onValueChange = { total = it.take(16) }, label = "Total due", placeholder = "0", keyboardType = KeyboardType.Decimal, prefix = "₹")
        ImpeesaTextField(value = min, onValueChange = { min = it.take(16) }, label = "Minimum due", placeholder = "Optional", keyboardType = KeyboardType.Decimal, prefix = "₹")
        ImpeesaTextField(value = day, onValueChange = { day = it.filter(Char::isDigit).take(2) }, label = "Due on day", placeholder = "e.g. 18", keyboardType = KeyboardType.Number)
        PrimaryButton(text = "Add bill", enabled = totalAmount != null && dueDay != null, onClick = { onDone(totalAmount!!, AmountInput.parse(min), dueDay!!) })
    }
}

