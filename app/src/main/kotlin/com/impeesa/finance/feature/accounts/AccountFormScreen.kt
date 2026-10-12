package com.impeesa.finance.feature.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impeesa.finance.LocalAppContainer
import com.impeesa.finance.core.data.AccountDraft
import com.impeesa.finance.core.data.AccountSuggestion
import com.impeesa.finance.core.data.AccountRepository
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.AmountInput
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.ui.component.ImpeesaTextField
import com.impeesa.finance.core.ui.component.PrimaryButton
import com.impeesa.finance.core.ui.component.ScreenHeader
import com.impeesa.finance.core.ui.component.SectionLabel
import com.impeesa.finance.core.ui.component.SegmentedControl
import com.impeesa.finance.core.ui.theme.ImpeesaTheme
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.UndoEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val accountColors = listOf(
    0xFF2C5BD6, 0xFF1673B5, 0xFFB4462B, 0xFF8E2453, 0xFFC2283A, 0xFF1F8A84, 0xFF5F259F, 0xFF3A3F47,
).map { it.toInt() }

private val types = listOf(AccountType.BANK, AccountType.CARD, AccountType.WALLET, AccountType.CASH)

@Immutable
data class AccountFormState(
    val isEditing: Boolean = false,
    val type: AccountType = AccountType.BANK,
    val name: String = "",
    val mask: String = "",
    val balance: String = "",
    val limit: String = "",
    val color: Int = accountColors.first(),
    val statementDay: String = "",
    val dueDay: String = "",
    val error: String? = null,
    /** Accounts seen in messages, offered when adding (§5.7 discovery). */
    val suggestions: List<AccountSuggestion> = emptyList(),
)

class AccountFormViewModel(
    private val id: Long?,
    private val repository: AccountRepository,
    private val undo: UndoBus,
) : ViewModel() {
    private val form = MutableStateFlow(AccountFormState(isEditing = id != null))
    val state: StateFlow<AccountFormState> = form.asStateFlow()
    private val doneChannel = Channel<Unit>(Channel.BUFFERED)
    val done: Flow<Unit> = doneChannel.receiveAsFlow()

    init {
        if (id != null) {
            viewModelScope.launch {
                val draft = repository.draftFor(id) ?: return@launch
                form.value = AccountFormState(
                    isEditing = true,
                    type = draft.type,
                    name = draft.name,
                    mask = draft.mask.orEmpty(),
                    balance = AmountInput.of(draft.openingBalance).text,
                    limit = draft.creditLimit?.let { AmountInput.of(it).text }.orEmpty(),
                    color = draft.color,
                    statementDay = draft.statementDay?.toString().orEmpty(),
                    dueDay = draft.dueDay?.toString().orEmpty(),
                )
            }
        } else {
            viewModelScope.launch {
                repository.observeSuggestions().collect { list -> form.update { it.copy(suggestions = list) } }
            }
        }
    }

    fun setStatementDay(value: String) = form.update { it.copy(statementDay = value.filter(Char::isDigit).take(2), error = null) }
    fun setDueDay(value: String) = form.update { it.copy(dueDay = value.filter(Char::isDigit).take(2), error = null) }

    /** Adds an account found in messages, claiming the transactions already captured for it. */
    fun addSuggestion(suggestion: AccountSuggestion) {
        viewModelScope.launch {
            repository.acceptSuggestion(suggestion.id)
            undo.post(UndoEvent("Added ${suggestion.institution} ••${suggestion.mask}"))
            doneChannel.send(Unit)
        }
    }

    fun setType(type: AccountType) = form.update { it.copy(type = type, error = null) }
    fun setName(value: String) = form.update { it.copy(name = value.take(40), error = null) }
    fun setMask(value: String) = form.update { it.copy(mask = value.filter(Char::isDigit).take(4)) }
    fun setBalance(value: String) = form.update { it.copy(balance = value.take(16), error = null) }
    fun setLimit(value: String) = form.update { it.copy(limit = value.take(16), error = null) }
    fun setColor(value: Int) = form.update { it.copy(color = value) }

    fun save() {
        val s = form.value
        val balance = if (s.balance.isBlank()) Money.ZERO else AmountInput.parse(s.balance)
        val limit = if (s.limit.isBlank()) null else AmountInput.parse(s.limit)
        val statementDay = s.statementDay.toIntOrNull()
        val dueDay = s.dueDay.toIntOrNull()
        val error = when {
            s.name.isBlank() -> "Give the account a name"
            balance == null -> "Check the balance amount"
            s.limit.isNotBlank() && limit == null -> "Check the credit limit"
            statementDay != null && statementDay !in 1..31 -> "Statement day is a day of the month (1–31)"
            dueDay != null && dueDay !in 1..31 -> "Due day is a day of the month (1–31)"
            else -> null
        }
        if (error != null) {
            form.update { it.copy(error = error) }
            return
        }
        viewModelScope.launch {
            repository.save(
                AccountDraft(
                    id = id,
                    type = s.type,
                    name = s.name,
                    mask = s.mask.takeIf { s.type != AccountType.CASH },
                    color = s.color,
                    openingBalance = balance!!,
                    creditLimit = limit,
                    statementDay = statementDay,
                    dueDay = dueDay,
                ),
            )
            doneChannel.send(Unit)
        }
    }

    fun archive() {
        val accountId = id ?: return
        viewModelScope.launch {
            repository.setArchived(accountId, true)
            undo.post(UndoEvent("Account archived") { repository.setArchived(accountId, false) })
            doneChannel.send(Unit)
        }
    }

    private val mergedChannel = Channel<Long>(Channel.BUFFERED)
    /** The account this one was merged into; this one no longer exists. */
    val merged: Flow<Long> = mergedChannel.receiveAsFlow()

    suspend fun mergeTargets(): List<Account> = id?.let { repository.mergeTargets(it) }.orEmpty()

    fun mergeInto(target: Account) {
        val accountId = id ?: return
        viewModelScope.launch {
            repository.merge(accountId, target.id)
            undo.post(UndoEvent("Merged into ${target.displayName}"))
            mergedChannel.send(target.id)
        }
    }
}

@Composable
fun AccountFormScreen(id: Long?, onBack: () -> Unit, onMerged: (Long) -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { AccountFormViewModel(id, container.accountRepository, container.undo) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.done.collect { onBack() } }
    LaunchedEffect(viewModel) { viewModel.merged.collect(onMerged) }
    val colors = ImpeesaTheme.colors
    var mergeChoices by remember { mutableStateOf<List<Account>?>(null) }
    var mergeTarget by remember { mutableStateOf<Account?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.ink)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ScreenHeader(title = if (state.isEditing) "Edit account" else "New account", onBack = onBack)

        if (state.suggestions.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.lime.copy(alpha = 0.07f)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Found in your messages", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                state.suggestions.forEach { suggestion ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${suggestion.institution} ••${suggestion.mask}", style = ImpeesaTheme.type.body, color = colors.textPrimary)
                            Text(
                                (if (suggestion.looksLikeCard) "Card" else "Bank account") + " · seen ${suggestion.sightings} " + if (suggestion.sightings == 1) "time" else "times",
                                style = ImpeesaTheme.type.caption,
                                color = colors.textTertiary,
                            )
                        }
                        Text(
                            "Add",
                            style = ImpeesaTheme.type.label,
                            color = colors.ink,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.lime).clickable(role = Role.Button) { viewModel.addSuggestion(suggestion) }.padding(horizontal = 16.dp, vertical = 9.dp),
                        )
                    }
                }
                Text("Or add one by hand below.", style = ImpeesaTheme.type.caption, color = colors.textSecondary)
            }
        }

        SegmentedControl(
            options = listOf("Bank", "Card", "Wallet", "Cash"),
            selectedIndex = types.indexOf(state.type),
            onSelect = { viewModel.setType(types[it]) },
        )

        ImpeesaTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = "Name",
            placeholder = when (state.type) {
                AccountType.BANK -> "e.g. HDFC Savings"
                AccountType.CARD -> "e.g. ICICI Credit Card"
                AccountType.WALLET -> "e.g. Paytm Wallet"
                AccountType.CASH -> "e.g. Cash"
            },
        )

        if (state.type != AccountType.CASH) {
            ImpeesaTextField(
                value = state.mask,
                onValueChange = viewModel::setMask,
                label = "Last 4 digits",
                placeholder = "Helps match bank SMS later",
                keyboardType = KeyboardType.NumberPassword,
            )
        }

        ImpeesaTextField(
            value = state.balance,
            onValueChange = viewModel::setBalance,
            label = when {
                state.isEditing -> if (state.type == AccountType.CARD) "Owed when added" else "Starting balance"
                state.type == AccountType.CARD -> "Amount owed today"
                else -> "Current balance"
            },
            placeholder = "0",
            keyboardType = KeyboardType.Decimal,
            prefix = MoneyFormat.symbol.trim(),
        )
        if (state.isEditing) {
            Text(
                "Used until your bank's SMS tells Kosh the balance. To correct today's balance, use “Set balance” on the account.",
                style = ImpeesaTheme.type.caption,
                color = colors.textTertiary,
            )
        }

        if (state.type == AccountType.CARD) {
            ImpeesaTextField(
                value = state.limit,
                onValueChange = viewModel::setLimit,
                label = "Credit limit",
                placeholder = "Optional",
                keyboardType = KeyboardType.Decimal,
                prefix = MoneyFormat.symbol.trim(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ImpeesaTextField(
                    value = state.statementDay,
                    onValueChange = viewModel::setStatementDay,
                    label = "Statement day",
                    placeholder = "e.g. 20",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                ImpeesaTextField(
                    value = state.dueDay,
                    onValueChange = viewModel::setDueDay,
                    label = "Due day",
                    placeholder = "e.g. 8",
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
            }
            Text("With a due day, Kosh reminds you 3 days before and on the day, even before a statement SMS arrives.", style = ImpeesaTheme.type.caption, color = colors.textTertiary)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Colour")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                accountColors.forEach { color ->
                    val selected = color == state.color
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .border(if (selected) 3.dp else 0.dp, colors.textPrimary, CircleShape)
                            .semantics { this.selected = selected; contentDescription = "Colour" }
                            .clickable(role = Role.RadioButton) { viewModel.setColor(color) },
                    )
                }
            }
        }

        state.error?.let { Text(it, style = ImpeesaTheme.type.caption, color = colors.coral) }

        PrimaryButton(text = if (state.isEditing) "Save changes" else "Add account", onClick = viewModel::save)

        if (state.isEditing) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.card)
                    .clickable(role = Role.Button, onClick = viewModel::archive),
                contentAlignment = Alignment.Center,
            ) {
                Text("Archive account", style = ImpeesaTheme.type.bodyStrong, color = colors.coral)
            }
            Text(
                "Archived accounts keep their history but disappear from lists and totals.",
                style = ImpeesaTheme.type.caption,
                color = colors.textTertiary,
            )
            if (state.type != AccountType.CASH) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.card)
                        .clickable(role = Role.Button) { scope.launch { mergeChoices = viewModel.mergeTargets() } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Merge with another account", style = ImpeesaTheme.type.bodyStrong, color = colors.textPrimary)
                }
                Text(
                    "Showing up twice? Merge moves this account's transactions, bills and balance history into the other one.",
                    style = ImpeesaTheme.type.caption,
                    color = colors.textTertiary,
                )
            }
        }
    }

    mergeChoices?.let { choices ->
        AlertDialog(
            onDismissRequest = { mergeChoices = null },
            title = { Text("Merge into…") },
            text = {
                if (choices.isEmpty()) {
                    Text("There's no other account of this type to merge with.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        choices.forEach { account ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Button) {
                                        mergeChoices = null
                                        mergeTarget = account
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(account.displayName, style = ImpeesaTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                                Text(MoneyFormat.format(account.balance), style = ImpeesaTheme.type.caption, color = colors.textSecondary)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { mergeChoices = null }) { Text("Cancel") } },
            containerColor = colors.card,
        )
    }

    mergeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { mergeTarget = null },
            title = { Text("Merge into ${target.displayName}?") },
            text = {
                val self = state.name + state.mask.takeIf { it.isNotBlank() }?.let { " ••$it" }.orEmpty()
                Text("“$self” will be removed and everything on it moves to ${target.displayName}. This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    mergeTarget = null
                    viewModel.mergeInto(target)
                }) { Text("Merge", color = colors.lime) }
            },
            dismissButton = { TextButton(onClick = { mergeTarget = null }) { Text("Cancel") } },
            containerColor = colors.card,
        )
    }
}
