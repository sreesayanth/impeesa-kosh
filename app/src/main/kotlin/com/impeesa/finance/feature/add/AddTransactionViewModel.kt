package com.impeesa.finance.feature.add

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impeesa.finance.core.data.AccountRepository
import com.impeesa.finance.core.data.CategoryRepository
import com.impeesa.finance.core.data.TransactionDraft
import com.impeesa.finance.core.data.TransactionRepository
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.AmountInput
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.model.CategoryKind
import com.impeesa.finance.core.model.EntryKind
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** A one-tap preset for common cash spends. */
data class QuickPreset(val name: String, val amount: Money, val categoryName: String) {
    /** "Chai ₹20", in the user's currency. */
    val label: String get() = "$name ${MoneyFormat.format(amount)}"
}

@Immutable
data class AddUiState(
    val isEditing: Boolean = false,
    val kind: EntryKind = EntryKind.EXPENSE,
    val input: AmountInput = AmountInput(),
    val categories: List<Category> = emptyList(),
    val categoryId: Long? = null,
    val accounts: List<Account> = emptyList(),
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
    val presets: List<QuickPreset> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
) {
    val selectedAccount: Account? get() = accounts.firstOrNull { it.id == accountId }
    val selectedToAccount: Account? get() = accounts.firstOrNull { it.id == toAccountId }

    val saveLabel: String
        get() {
            if (isEditing) return "Save changes"
            val amount = MoneyFormat.format(input.money, showPaise = input.text.contains('.'))
            return when (kind) {
                EntryKind.TRANSFER -> "Move $amount" + (selectedToAccount?.let { " to ${it.name}" } ?: "")
                EntryKind.INCOME -> "Save $amount income"
                EntryKind.EXPENSE -> "Save $amount" + (selectedAccount?.let { " · ${it.name}" } ?: "")
            }
        }

    val hint: String
        get() = when {
            kind == EntryKind.TRANSFER -> "Moves money between your accounts — not counted as spending"
            selectedAccount?.type == AccountType.CASH && kind == EntryKind.EXPENSE ->
                "Cash spend · wallet goes to " + MoneyFormat.format(selectedAccount!!.balance - input.money)
            else -> categories.firstOrNull { it.id == categoryId }?.name.orEmpty()
        }
}

/** The parts of the form the user changes; everything else is derived. */
private data class Form(
    val kind: EntryKind = EntryKind.EXPENSE,
    val input: AmountInput = AmountInput(),
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val toAccountId: Long? = null,
    val date: LocalDate,
    val time: LocalTime,
    val note: String = "",
    val error: String? = null,
    val saving: Boolean = false,
    /** Editing a captured transaction whose account isn't known: keep it unassigned until the user picks one. */
    val keepAccountEmpty: Boolean = false,
    /** Same for a captured transaction not sorted yet: saving other changes mustn't pick a category for it. */
    val keepCategoryEmpty: Boolean = false,
)

class AddTransactionViewModel(
    private val editId: Long?,
    private val transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val clock: Clock,
) : ViewModel() {

    private val now = LocalDateTime.now(clock)
    private val form = MutableStateFlow(Form(date = now.toLocalDate(), time = now.toLocalTime()))
    private val savedChannel = Channel<Unit>(Channel.BUFFERED)
    val saved: Flow<Unit> = savedChannel.receiveAsFlow()

    private val presets = listOf(
        QuickPreset("Chai", Money.ofRupees(20), "Food & dining"),
        QuickPreset("Auto", Money.ofRupees(120), "Travel"),
        QuickPreset("Vegetables", Money.ofRupees(350), "Groceries"),
        QuickPreset("Parking", Money.ofRupees(40), "Travel"),
    )

    private val expenseCategories = categories.observe(CategoryKind.EXPENSE)
    private val incomeCategories = categories.observe(CategoryKind.INCOME)
    private var latestExpense: List<Category> = emptyList()

    val state: StateFlow<AddUiState> = combine(
        form,
        accounts.observeAccounts(),
        expenseCategories,
        incomeCategories,
    ) { f, accountList, expense, income ->
        latestExpense = expense
        val cats = when (f.kind) {
            EntryKind.EXPENSE -> expense
            EntryKind.INCOME -> income
            EntryKind.TRANSFER -> emptyList()
        }
        val usable = if (f.kind == EntryKind.INCOME) accountList.filter { it.type != AccountType.CARD } else accountList
        val accountId = f.accountId?.takeIf { id -> usable.any { it.id == id } }
            ?: if (f.keepAccountEmpty && f.kind != EntryKind.TRANSFER) null else defaultAccount(f.kind, usable)
        val toAccountId = if (f.kind == EntryKind.TRANSFER) {
            f.toAccountId?.takeIf { id -> id != accountId && accountList.any { it.id == id } }
                ?: accountList.firstOrNull { it.type == AccountType.CASH && it.id != accountId }?.id
                ?: accountList.firstOrNull { it.id != accountId }?.id
        } else null
        AddUiState(
            isEditing = editId != null,
            kind = f.kind,
            input = f.input,
            categories = cats,
            categoryId = f.categoryId?.takeIf { id -> cats.any { it.id == id } } ?: if (f.keepCategoryEmpty) null else cats.firstOrNull()?.id,
            accounts = usable,
            accountId = accountId,
            toAccountId = toAccountId,
            date = f.date,
            note = f.note,
            presets = if (f.kind == EntryKind.EXPENSE && editId == null) presets else emptyList(),
            error = f.error,
            saving = f.saving,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddUiState(isEditing = editId != null))

    init {
        if (editId != null) {
            viewModelScope.launch {
                val draft = transactions.draftFor(editId) ?: return@launch
                form.update {
                    it.copy(
                        kind = draft.kind,
                        input = AmountInput.of(draft.amount),
                        categoryId = draft.categoryId,
                        accountId = draft.accountId,
                        toAccountId = draft.toAccountId,
                        date = draft.occurredAt.toLocalDate(),
                        time = draft.occurredAt.toLocalTime(),
                        note = draft.note.orEmpty(),
                        keepAccountEmpty = draft.accountId == null,
                        keepCategoryEmpty = draft.categoryId == null,
                    )
                }
            }
        }
    }

    private fun defaultAccount(kind: EntryKind, accounts: List<Account>): Long? = when (kind) {
        EntryKind.EXPENSE -> accounts.firstOrNull { it.type == AccountType.CASH } ?: accounts.firstOrNull()
        EntryKind.INCOME, EntryKind.TRANSFER -> accounts.firstOrNull { it.type == AccountType.BANK } ?: accounts.firstOrNull()
    }?.id

    fun setKind(kind: EntryKind) = form.update {
        it.copy(kind = kind, categoryId = null, accountId = null, toAccountId = null, keepCategoryEmpty = false, error = null)
    }

    fun press(key: Char) = form.update { it.copy(input = it.input.press(key), error = null) }

    fun delete() = form.update { it.copy(input = it.input.delete()) }

    fun pickCategory(id: Long) = form.update { it.copy(categoryId = id) }

    fun pickAccount(id: Long) = form.update { it.copy(accountId = id, error = null) }

    fun pickToAccount(id: Long) = form.update { it.copy(toAccountId = id, error = null) }

    fun setDate(date: LocalDate) = form.update { it.copy(date = date) }

    fun setNote(note: String) = form.update { it.copy(note = note.take(120)) }

    fun applyPreset(preset: QuickPreset) {
        val categoryId = latestExpense.firstOrNull { it.name == preset.categoryName }?.id
        form.update {
            it.copy(
                kind = EntryKind.EXPENSE,
                input = AmountInput.of(preset.amount),
                categoryId = categoryId,
                accountId = state.value.accounts.firstOrNull { a -> a.type == AccountType.CASH }?.id ?: it.accountId,
                note = if (it.note.isBlank()) preset.name else it.note,
                error = null,
            )
        }
    }

    fun save() {
        val s = state.value
        val error = when {
            s.input.money.paise <= 0 -> "Enter an amount"
            s.accountId == null && (editId == null || s.kind == EntryKind.TRANSFER) -> "Add an account first"
            s.kind == EntryKind.TRANSFER && s.toAccountId == null -> "Pick where the money goes"
            else -> null
        }
        if (error != null) {
            form.update { it.copy(error = error) }
            return
        }
        if (s.saving) return
        form.update { it.copy(saving = true) }
        viewModelScope.launch {
            val f = form.value
            val time = if (f.date == LocalDate.now(clock) && editId == null) LocalTime.now(clock) else f.time
            runCatching {
                transactions.save(
                    TransactionDraft(
                        editingId = editId,
                        kind = s.kind,
                        amount = s.input.money,
                        accountId = s.accountId,
                        toAccountId = s.toAccountId,
                        categoryId = s.categoryId.takeIf { s.kind != EntryKind.TRANSFER },
                        occurredAt = LocalDateTime.of(f.date, time),
                        note = f.note,
                    ),
                )
            }.onSuccess {
                savedChannel.send(Unit)
            }.onFailure { e ->
                form.update { it.copy(saving = false, error = e.message ?: "Could not save") }
            }
        }
    }
}
