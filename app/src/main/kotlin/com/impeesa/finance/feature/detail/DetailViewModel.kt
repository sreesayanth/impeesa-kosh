package com.impeesa.finance.feature.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impeesa.finance.core.data.AccountRepository
import com.impeesa.finance.core.data.CategoryRepository
import com.impeesa.finance.core.data.TransactionRepository
import com.impeesa.finance.core.model.EntryKind
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.TxnSource
import com.impeesa.finance.ui.UndoBus
import com.impeesa.finance.ui.UndoEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class DetailRow(val label: String, val value: String, val dotColor: Int? = null)

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
)

class DetailViewModel(
    private val id: Long,
    private val transactions: TransactionRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    private val undo: UndoBus,
    clock: Clock,
) : ViewModel() {

    private val closeChannel = Channel<Unit>(Channel.BUFFERED)
    val close: Flow<Unit> = closeChannel.receiveAsFlow()
    private val formatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mm a", Locale.ENGLISH)

    val state: StateFlow<DetailUiState> = combine(
        transactions.observeDetail(id),
        accounts.observeAccounts(),
        categories.observeAll(),
    ) { detail, accountList, categoryList ->
        if (detail == null) return@combine DetailUiState(loading = false, missing = true)
        val category = categoryList.firstOrNull { it.id == detail.categoryId }
        val account = accountList.firstOrNull { it.id == detail.accountId }
        val toAccount = accountList.firstOrNull { it.id == detail.toAccountId }
        val rows = buildList {
            if (detail.kind == EntryKind.TRANSFER) {
                add(DetailRow("From", account?.displayName ?: "Archived account"))
                add(DetailRow("To", toAccount?.displayName ?: "Archived account"))
                category?.let { add(DetailRow("Type", it.name, it.color)) }
            } else {
                add(DetailRow("Category", category?.name ?: "Uncategorised", category?.color))
                add(DetailRow(if (detail.kind == EntryKind.INCOME) "Received in" else "Paid from", account?.displayName ?: "Archived account"))
            }
            detail.reference?.let { add(DetailRow("Reference", it)) }
            detail.note?.let { add(DetailRow("Note", it)) }
        }
        val signed = when (detail.kind) {
            EntryKind.EXPENSE -> -detail.amount
            else -> detail.amount
        }
        DetailUiState(
            loading = false,
            kind = detail.kind,
            title = detail.note ?: detail.counterparty ?: category?.name ?: "Transaction",
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

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
