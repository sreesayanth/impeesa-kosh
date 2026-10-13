package com.impeesa.finance.feature.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impeesa.finance.core.data.AccountRepository
import com.impeesa.finance.core.data.CategorizationRepository
import com.impeesa.finance.core.data.CategoryChange
import com.impeesa.finance.core.data.CategoryRepository
import com.impeesa.finance.core.data.MessageSourceRef
import com.impeesa.finance.core.data.TransactionDetail
import com.impeesa.finance.core.data.capture.CaptureRepository
import com.impeesa.finance.core.data.TransactionRepository
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.model.CategoryKind
import com.impeesa.finance.core.model.EntryKind
import com.impeesa.finance.core.model.counted
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.TxnSource
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.UndoEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class DetailRow(
    val label: String,
    val value: String,
    val dotColor: Int? = null,
    /** Second line under the value, e.g. why the category was chosen. */
    val hint: String? = null,
    /** Tapping the row opens the category picker. */
    val opensCategory: Boolean = false,
)

@Immutable
data class DetailUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val kind: EntryKind = EntryKind.EXPENSE,
    val title: String = "",
    val amount: Money = Money.ZERO,
    val whenLabel: String = "",
    val sourceLabel: String = "",
    val tileColor: Int? = null,
    val rows: List<DetailRow> = emptyList(),
    val originalMessage: String? = null,
    val pendingReview: Boolean = false,
    val sources: List<MessageSourceRef> = emptyList(),
    val categoryId: Long? = null,
    /** Categories the picker offers (spending or income, matching the transaction). */
    val categories: List<Category> = emptyList(),
)

/** Shown after the user moves a captured transaction to another category. */
@Immutable
data class LearnPrompt(
    val categoryName: String,
    val merchant: String,
    val earlierCount: Int,
    val alwaysSort: Boolean = false,
    val appliedCount: Int? = null,
    internal val change: CategoryChange,
)

class DetailViewModel(
    private val id: Long,
    private val transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val categorization: CategorizationRepository,
    private val capture: CaptureRepository,
    private val undo: UndoBus,
    clock: Clock,
) : ViewModel() {

    private val closeChannel = Channel<Unit>(Channel.BUFFERED)
    val close: Flow<Unit> = closeChannel.receiveAsFlow()
    private val formatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH)

    private val _learn = MutableStateFlow<LearnPrompt?>(null)
    val learn: StateFlow<LearnPrompt?> = _learn.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val original: Flow<String?> = transactions.observeDetail(id).flatMapLatest { detail ->
        detail?.rawMessageId?.let(transactions::observeOriginalMessage) ?: flowOf(null)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sources: Flow<List<MessageSourceRef>> = transactions.observeDetail(id).flatMapLatest { detail ->
        detail?.let { transactions.observeSources(it.id) } ?: flowOf(emptyList())
    }

    val state: StateFlow<DetailUiState> = combine(
        transactions.observeDetail(id),
        accounts.observeAccounts(),
        categories.observeAll(),
        original,
        sources,
    ) { detail, accountList, categoryList, message, sourceList ->
        if (detail == null) return@combine DetailUiState(loading = false, missing = true)
        val category = categoryList.firstOrNull { it.id == detail.categoryId }
        val account = accountList.firstOrNull { it.id == detail.accountId }
        val toAccount = accountList.firstOrNull { it.id == detail.toAccountId }
        val rows = buildList {
            if (detail.kind == EntryKind.TRANSFER) {
                add(DetailRow("From", account?.displayName ?: if (detail.accountId == null) "Not added yet" else "Archived account"))
                add(DetailRow("To", toAccount?.displayName ?: if (detail.toAccountId == null) "Not added yet" else "Archived account"))
                category?.let { add(DetailRow("Type", it.name, it.color)) }
            } else {
                add(DetailRow("Category", category?.name ?: "Choose", category?.color, hint = whyLabel(detail, category), opensCategory = true))
                add(DetailRow(if (detail.kind == EntryKind.INCOME) "Received in" else "Paid from", account?.displayName ?: if (detail.accountId == null) "Not added yet" else "Archived account"))
            }
            detail.reference?.let { add(DetailRow("Reference", it)) }
            detail.note?.let { add(DetailRow("Note", it)) }
        }
        val signed = when (detail.kind) {
            EntryKind.EXPENSE -> -detail.amount
            else -> detail.amount
        }
        val pickerKind = if (detail.kind == EntryKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
        DetailUiState(
            loading = false,
            kind = detail.kind,
            title = detail.note ?: detail.counterparty ?: category?.name ?: when (detail.kind) {
                EntryKind.INCOME -> "Money received"
                EntryKind.EXPENSE -> "Payment"
                EntryKind.TRANSFER -> "Transfer"
            },
            amount = signed,
            whenLabel = LocalDateTime.ofInstant(detail.occurredAt, clock.zone).format(formatter),
            sourceLabel = when (detail.source) {
                TxnSource.MANUAL -> "Added by you"
                TxnSource.SMS -> "Logged automatically from SMS"
                TxnSource.NOTIFICATION -> "Logged from a notification"
                TxnSource.IMPORT -> "Imported"
            },
            tileColor = category?.color,
            rows = rows,
            originalMessage = message.takeIf { sourceList.size <= 1 },
            pendingReview = detail.pendingReview,
            sources = sourceList.takeIf { it.size > 1 }.orEmpty(),
            categoryId = detail.categoryId,
            categories = categoryList.filter { it.kind == pickerKind },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    /** "Why this category" (IMPLEMENTATION_PLAN.md §7.1). */
    private fun whyLabel(detail: TransactionDetail, category: Category?): String = when {
        detail.excludedFromSpending && detail.kind == EntryKind.EXPENSE -> "Left out of spending" + (detail.categoryReason?.let { " · $it" } ?: "")
        category == null -> "Not sorted yet · tap to choose"
        detail.categorySource == "USER" -> if (detail.source == TxnSource.MANUAL) "Tap to change" else "You chose this"
        detail.categoryReason != null -> detail.categoryReason!!
        else -> "Set automatically"
    }

    /** Moves the transaction to [categoryId] and, for captured ones, offers to learn from it. */
    fun changeCategory(categoryId: Long) {
        if (categoryId == state.value.categoryId) return
        viewModelScope.launch {
            val change = categorization.changeCategory(id, categoryId) ?: return@launch
            val name = state.value.categories.firstOrNull { it.id == categoryId }?.name ?: "category"
            val merchant = change.merchantLabel
            _learn.value = if (change.merchantKey != null && merchant != null) LearnPrompt(name, merchant, change.earlierCount, change = change) else null
            undo.post(
                UndoEvent("Moved to $name") {
                    if (_learn.value?.alwaysSort == true) categorization.setAlwaysSort(change, false)
                    categorization.undoChange(change)
                    _learn.value = null
                },
            )
        }
    }

    fun setAlwaysSort(on: Boolean) {
        val prompt = _learn.value ?: return
        _learn.update { it?.copy(alwaysSort = on) }
        viewModelScope.launch { categorization.setAlwaysSort(prompt.change, on) }
    }

    fun applyToEarlier() {
        val prompt = _learn.value ?: return
        viewModelScope.launch {
            val snapshots = categorization.applyToEarlier(prompt.change)
            _learn.update { it?.copy(appliedCount = snapshots.size) }
            if (snapshots.isNotEmpty()) {
                undo.post(
                    UndoEvent("Updated ${counted(snapshots.size, "earlier ${prompt.merchant} transaction")}") {
                        categorization.restore(snapshots)
                        _learn.update { it?.copy(appliedCount = null) }
                    },
                )
            }
        }
    }

    fun dismissLearn() {
        _learn.value = null
    }

    /** "Not the same payment": splits a merged message back into its own transaction. */
    fun split(rawId: Long) {
        viewModelScope.launch {
            if (capture.unmerge(rawId) != null) undo.post(UndoEvent("Split into two transactions"))
        }
    }

    fun delete() {
        viewModelScope.launch {
            val removed = transactions.delete(id)
            if (removed.isNotEmpty()) {
                undo.post(UndoEvent("Transaction deleted") { transactions.restore(removed) })
            }
            closeChannel.send(Unit)
        }
    }
}
