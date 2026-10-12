package com.impeesa.finance.feature.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impeesa.finance.AppPrefs
import com.impeesa.finance.core.data.AccountRepository
import com.impeesa.finance.core.data.AnalyticsRepository
import com.impeesa.finance.core.data.ReviewRepository
import com.impeesa.finance.core.data.TransactionRepository
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.analytics.Flows
import com.impeesa.finance.core.model.analytics.Insight
import com.impeesa.finance.core.model.analytics.Periods
import com.impeesa.finance.ui.TxnUi
import com.impeesa.finance.ui.analytics.BudgetOverview
import com.impeesa.finance.ui.analytics.MoneyOverview
import com.impeesa.finance.ui.toUi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

enum class Greeting { MORNING, AFTERNOON, EVENING }

/** This week's bars, Monday first. */
@Immutable
data class WeekChart(
    val values: List<Long> = emptyList(),
    val labels: List<String> = emptyList(),
    val todayIndex: Int = 0,
    val total: Money = Money.ZERO,
    val perDay: Money = Money.ZERO,
)

@Immutable
data class HomeUiState(
    val loading: Boolean = true,
    val greeting: Greeting = Greeting.MORNING,
    val budget: BudgetOverview? = null,
    val income: Money = Money.ZERO,
    val week: WeekChart = WeekChart(),
    val insight: Insight? = null,
    val accounts: List<Account> = emptyList(),
    val recent: List<TxnUi> = emptyList(),
    val transactionCount: Int = 0,
    val reviewCount: Int = 0,
) {
    val isEmpty: Boolean get() = !loading && transactionCount == 0
}

class HomeViewModel(
    transactions: TransactionRepository,
    accounts: AccountRepository,
    review: ReviewRepository,
    analytics: AnalyticsRepository,
    overviewSource: MoneyOverview,
    private val prefs: AppPrefs,
    private val clock: Clock,
) : ViewModel() {

    private val today = LocalDate.now(clock)
    private val week = Periods.week(today)

    private val overview = overviewSource.budgets(today, prefs.monthStartDay)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val insights = overviewSource.insights(today, overview, prefs.dismissedInsights) { clock.millis() }

    private val weekChart = analytics.observeDaily(week.start, week.end).map { days ->
        val values = MoneyOverview.dailyValues(days, week)
        val elapsed = week.elapsedDays(today)
        val total = Money(values.sum())
        WeekChart(
            values = values,
            labels = week.dates().mapIndexed { i, d -> if (i == elapsed - 1) "Today" else d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH) }.toList(),
            todayIndex = elapsed - 1,
            total = total,
            perDay = Money(total.paise / elapsed.coerceAtLeast(1)),
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val income = overview.flatMapLatest { o -> analytics.observeDaily(o.period.start, o.period.end).map { Flows.income(it, o.period) } }

    private val base = combine(
        transactions.observeRecent(limit = 5),
        accounts.observeAccounts(),
        transactions.observeCount(),
        review.observeCount(),
        income,
    ) { recent, accountList, count, reviewCount, periodIncome ->
        HomeUiState(
            loading = false,
            greeting = greetingNow(),
            income = periodIncome,
            accounts = accountList,
            recent = recent.map { it.toUi(clock.zone, today) },
            transactionCount = count,
            reviewCount = reviewCount,
        )
    }

    val state: StateFlow<HomeUiState> = combine(base, overview, weekChart, insights) { b, o, w, list ->
        b.copy(budget = o, week = w, insight = list.firstOrNull())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(greeting = greetingNow()))

    fun dismissInsight(insight: Insight) {
        prefs.dismissInsight(insight.key, clock.millis() + insight.kind.cooldownDays * DAY_MILLIS)
    }

    private fun greetingNow(): Greeting = when (LocalDateTime.now(clock).hour) {
        in 5..11 -> Greeting.MORNING
        in 12..16 -> Greeting.AFTERNOON
        else -> Greeting.EVENING
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
