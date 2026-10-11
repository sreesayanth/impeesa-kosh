package com.impeesa.finance.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impeesa.finance.core.data.AccountRepository
import com.impeesa.finance.core.data.TransactionRepository
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.ui.TxnUi
import com.impeesa.finance.ui.toUi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

enum class Greeting { MORNING, AFTERNOON, EVENING }

@Immutable
data class HomeUiState(
    val loading: Boolean = true,
    val greeting: Greeting = Greeting.MORNING,
    val monthName: String = "",
    val spent: Money = Money.ZERO,
    val income: Money = Money.ZERO,
    val accounts: List<Account> = emptyList(),
    val recent: List<TxnUi> = emptyList(),
    val transactionCount: Int = 0,
) {
    val isEmpty: Boolean get() = !loading && transactionCount == 0
}

class HomeViewModel(
    transactions: TransactionRepository,
    accounts: AccountRepository,
    private val clock: Clock,
) : ViewModel() {

    private val month = YearMonth.now(clock)
    private val today = LocalDate.now(clock)

    val state: StateFlow<HomeUiState> =
        combine(
            transactions.observeSummary(DateKey.monthRange(month)),
            transactions.observeRecent(limit = 5),
            accounts.observeAccounts(),
            transactions.observeCount(),
        ) { summary, recent, accountList, count ->
            HomeUiState(
                loading = false,
                greeting = greetingNow(),
                monthName = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                spent = summary.spent,
                income = summary.income,
                accounts = accountList,
                recent = recent.map { it.toUi(clock.zone, today) },
                transactionCount = count,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(greeting = greetingNow()))

    private fun greetingNow(): Greeting = when (LocalDateTime.now(clock).hour) {
        in 5..11 -> Greeting.MORNING
        in 12..16 -> Greeting.AFTERNOON
        else -> Greeting.EVENING
    }
}
