package com.impeesa.finance.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.impeesa.finance.core.data.AccountRepository
import com.impeesa.finance.core.data.ActivityFilter
import com.impeesa.finance.core.data.ActivityKind
import com.impeesa.finance.core.data.TransactionRepository
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.ui.TxnUi
import com.impeesa.finance.ui.toUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.LocalDate

sealed interface ActivityItem {
    val key: String

    data class Header(val date: Int) : ActivityItem {
        override val key get() = "h$date"
    }

    data class Row(val txn: TxnUi) : ActivityItem {
        override val key get() = "t${txn.id}"
    }
}

class ActivityViewModel(
    transactions: TransactionRepository,
    accounts: AccountRepository,
    clock: Clock,
) : ViewModel() {

    private val zone = clock.zone
    val today: LocalDate = LocalDate.now(clock)

    private val filterState = MutableStateFlow(ActivityFilter())
    val filter: StateFlow<ActivityFilter> = filterState.asStateFlow()

    private val queryState = MutableStateFlow("")
    val query: StateFlow<String> = queryState.asStateFlow()

    val accounts: StateFlow<List<Account>> =
        accounts.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val dayTotals: StateFlow<Map<Int, Money>> =
        transactions.observeDailySpending(DateKey.of(today.minusYears(3)))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val items: Flow<PagingData<ActivityItem>> =
        combine(filterState, queryState.debounce(250)) { f, q -> f.copy(query = q) }
            .distinctUntilChanged()
            .flatMapLatest { f ->
                transactions.pagedActivity(f).map { page ->
                    page.map { row -> ActivityItem.Row(row.toUi(zone, today)) }
                        .insertSeparators { before: ActivityItem.Row?, after: ActivityItem.Row? ->
                            if (after != null && before?.txn?.localDate != after.txn.localDate) {
                                ActivityItem.Header(after.txn.localDate)
                            } else {
                                null
                            }
                        }
                }
            }
            .cachedIn(viewModelScope)

    fun setQuery(value: String) {
        queryState.value = value.take(60)
    }

    fun setKind(kind: ActivityKind) = filterState.update { it.copy(kind = kind) }

    fun setAccount(id: Long?) = filterState.update { it.copy(accountId = id) }
}
