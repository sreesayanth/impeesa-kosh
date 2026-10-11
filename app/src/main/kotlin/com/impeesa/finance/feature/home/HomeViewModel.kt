package com.impeesa.finance.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impeesa.finance.core.data.TransactionRepository
import com.impeesa.finance.core.data.db.TransactionListItem
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Money
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

enum class Greeting { MORNING, AFTERNOON, EVENING }

@Immutable
data class RecentRow(val id: Long, val title: String, val subtitle: String, val amount: Money, val color: Int?)

@Immutable
data class HomeUiState(
    val loading: Boolean = true,
    val greeting: Greeting = Greeting.MORNING,
    val monthName: String = "",
    val spent: Money = Money.ZERO,
    val income: Money = Money.ZERO,
    val recent: List<RecentRow> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && recent.isEmpty()
}

class HomeViewModel(
    repository: TransactionRepository,
    private val clock: Clock,
) : ViewModel() {

    private val month = YearMonth.now(clock)
    private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    val state: StateFlow<HomeUiState> =
        combine(
            repository.observeSummary(DateKey.monthRange(month)),
            repository.observeRecent(limit = 5),
        ) { summary, recent ->
            HomeUiState(
                loading = false,
                greeting = greetingNow(),
                monthName = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
                spent = summary.spent,
                income = summary.income,
                recent = recent.map(::toRow),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(greeting = greetingNow()))

    private fun greetingNow(): Greeting = when (LocalDateTime.now(clock).hour) {
        in 5..11 -> Greeting.MORNING
        in 12..16 -> Greeting.AFTERNOON
        else -> Greeting.EVENING
    }

    private fun toRow(item: TransactionListItem): RecentRow {
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(item.occurredAt), clock.zone)
        val signed = if (item.direction == "DEBIT") -item.amountPaise else item.amountPaise
        val source = when (item.source) {
            "SMS" -> "SMS"
            "NOTIFICATION" -> "Notification"
            else -> "Added by you"
        }
        return RecentRow(
            id = item.id,
            title = item.counterparty ?: item.note ?: "Transaction",
            subtitle = listOfNotNull(item.categoryName, source, time.format(timeFormat)).joinToString(" · "),
            amount = Money(signed),
            color = item.categoryColor,
        )
    }
}
