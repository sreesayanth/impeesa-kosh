package com.impeesa.finance.core.model.alerts

import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.MoneyFormat
import com.impeesa.finance.core.model.counted
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Alert types and whether they start on (IMPLEMENTATION_PLAN.md §5.10). */
enum class AlertKind(val title: String, private val template: String, val defaultOn: Boolean = true) {
    CARD_DUE("Card bill due", "3 days before and on the due date, until it's paid"),
    BUDGET("Budgets", "When a budget reaches your warn-at level or runs out"),
    REVIEW("Review reminder", "Once a day while transactions wait for you"),
    LARGE_TRANSACTION("Large transaction", "Over {large}, or 3× your usual on that account"),
    UNUSUAL_DAY("Unusual spending day", "When a day is far above your usual"),
    SUBSCRIPTION("Subscriptions", "3 days before a renewal, and when a price changes"),
    NEW_ACCOUNT("New account found", "An account seen twice in your messages that you haven't added"),
    LOW_BALANCE("Low balance", "When an account drops below the amount you set", defaultOn = false),
    BALANCE_SYNC("Balance updated", "When your bank's balance differs from Kosh's by more than {sync}"),
    WEEKLY_SUMMARY("Weekly summary", "Monday morning: how last week went"),
    CAPTURE_HEALTH("Auto-capture health", "When your phone stops Kosh reading messages"),
    ;

    /** Shown on Alert settings, with amounts in the user's currency. */
    val description: String
        get() = template.replace("{large}", MoneyFormat.format(Money(AlertRules.LARGE_AMOUNT_PAISE))).replace("{sync}", MoneyFormat.format(Money(500_00)))
}

/** One alert to post. [dedupeKey] makes posting idempotent: the same key is never shown twice. */
data class AlertCandidate(
    val kind: AlertKind,
    val dedupeKey: String,
    val title: String,
    val body: String,
    /** In-app destination: "account:12", "budgets", "review", "txn:5", "habits", "sources"… */
    val link: String,
)

data class CardDueInput(val accountId: Long, val name: String, val dueDate: LocalDate, val totalDue: Money?, val minDue: Money?, val paid: Boolean)

data class BudgetInput(val budgetId: Long, val name: String, val periodStart: LocalDate, val spent: Money, val available: Money, val warnPercent: Int)

data class RenewalInput(val key: String, val label: String, val nextDate: LocalDate, val amount: Money, val priceChange: Money?, val lastDate: LocalDate)

data class LargeTransactionInput(val transactionId: Long, val title: String, val amount: Money, val accountMedian: Money?)

data class LowBalanceInput(val accountId: Long, val name: String, val balance: Money, val threshold: Money)

data class NewAccountInput(val suggestionId: Long, val label: String, val sightings: Int)

data class BalanceSyncInput(val snapshotId: Long, val accountId: Long, val accountName: String, val difference: Money)

data class BigDayInput(val key: String, val text: String)

data class WeeklyInput(val spent: Money, val previous: Money)

data class AlertInputs(
    val today: LocalDate,
    val cards: List<CardDueInput> = emptyList(),
    val budgets: List<BudgetInput> = emptyList(),
    val renewals: List<RenewalInput> = emptyList(),
    val reviewCount: Int = 0,
    val largeTransactions: List<LargeTransactionInput> = emptyList(),
    val lowBalances: List<LowBalanceInput> = emptyList(),
    val newAccounts: List<NewAccountInput> = emptyList(),
    val balanceSyncs: List<BalanceSyncInput> = emptyList(),
    val bigDay: BigDayInput? = null,
    val weekly: WeeklyInput? = null,
    val captureHealthy: Boolean = true,
)

/**
 * Decides which alerts are due today. Pure and deterministic: the caller posts the candidates and the
 * store drops any key it has seen before, so running this many times a day never repeats an alert.
 */
object AlertRules {
    const val LARGE_AMOUNT_PAISE = 10_000_00L
    private const val SYNC_NOTICE_PAISE = 500_00L
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun evaluate(inputs: AlertInputs, enabled: Set<AlertKind> = AlertKind.entries.filter { it.defaultOn }.toSet()): List<AlertCandidate> = buildList {
        val today = inputs.today
        inputs.cards.forEach { card -> cardDue(card, today)?.let(::add) }
        inputs.budgets.forEach { budget(it)?.let(::add) }
        if (inputs.reviewCount > 0) {
            add(
                AlertCandidate(
                    AlertKind.REVIEW, "REVIEW:$today",
                    if (inputs.reviewCount == 1) "1 transaction to review" else "${inputs.reviewCount} transactions to review",
                    "Kosh wasn't sure about these. Confirm them in a tap.", "review",
                ),
            )
        }
        inputs.largeTransactions.forEach { t ->
            val median = t.accountMedian
            if (t.amount.paise >= LARGE_AMOUNT_PAISE || (median != null && median.paise > 0 && t.amount.paise > median.paise * 3 && t.amount.paise >= 1_000_00)) {
                add(AlertCandidate(AlertKind.LARGE_TRANSACTION, "LARGE:${t.transactionId}", "Large payment: ${MoneyFormat.format(t.amount)}", "${t.title}. Tap to check it was you.", "txn:${t.transactionId}"))
            }
        }
        inputs.bigDay?.let { add(AlertCandidate(AlertKind.UNUSUAL_DAY, it.key, "A big spending day", it.text, "habits")) }
        inputs.renewals.forEach { r ->
            val days = ChronoUnit.DAYS.between(today, r.nextDate)
            if (days in 0..3) {
                val whenText = when (days) {
                    0L -> "today"
                    1L -> "tomorrow"
                    else -> "in $days days"
                }
                add(AlertCandidate(AlertKind.SUBSCRIPTION, "RENEWAL:${r.key}:${r.nextDate}", "${r.label} renews $whenText", "About ${MoneyFormat.format(r.amount)} on ${r.nextDate.format(dayMonth)}.", "habits"))
            }
            val change = r.priceChange
            if (change != null && ChronoUnit.DAYS.between(r.lastDate, today) in 0..7) {
                add(
                    AlertCandidate(
                        AlertKind.SUBSCRIPTION, "PRICE:${r.key}:${r.lastDate}",
                        "${r.label} price ${if (change.isNegative) "dropped" else "went up"}",
                        "Now ${MoneyFormat.format(r.amount)}, ${MoneyFormat.format(change.abs())} ${if (change.isNegative) "less" else "more"} than before.", "habits",
                    ),
                )
            }
        }
        inputs.newAccounts.filter { it.sightings >= 2 }.forEach { a ->
            add(AlertCandidate(AlertKind.NEW_ACCOUNT, "NEW_ACCOUNT:${a.suggestionId}", "New account found: ${a.label}", "Seen in ${counted(a.sightings, "message")}. Add it to track its balance.", "accounts"))
        }
        inputs.lowBalances.filter { it.balance < it.threshold }.forEach { a ->
            add(AlertCandidate(AlertKind.LOW_BALANCE, "LOW:${a.accountId}:$today", "${a.name} is low: ${MoneyFormat.format(a.balance)}", "Below the ${MoneyFormat.format(a.threshold)} you set.", "account:${a.accountId}"))
        }
        inputs.balanceSyncs.filter { kotlin.math.abs(it.difference.paise) > SYNC_NOTICE_PAISE }.forEach { s ->
            add(
                AlertCandidate(
                    AlertKind.BALANCE_SYNC, "SYNC:${s.snapshotId}", "Balance updated from your bank",
                    "${s.accountName} was ${MoneyFormat.format(s.difference.abs())} ${if (s.difference.isNegative) "lower" else "higher"} than Kosh had. Some payments may not have been captured.",
                    "account:${s.accountId}",
                ),
            )
        }
        val weekly = inputs.weekly
        // A week with nothing spent and nothing to compare with has no story to tell.
        if (weekly != null && today.dayOfWeek == DayOfWeek.MONDAY && (weekly.spent.paise > 0 || weekly.previous.paise > 0)) {
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
            val compare = when {
                weekly.previous.paise <= 0 -> ""
                else -> {
                    val pct = kotlin.math.abs(weekly.spent.paise - weekly.previous.paise) * 100 / weekly.previous.paise
                    if (weekly.spent <= weekly.previous) ", $pct% less than the week before" else ", $pct% more than the week before"
                }
            }
            add(AlertCandidate(AlertKind.WEEKLY_SUMMARY, "WEEKLY:$weekStart", "Last week: ${MoneyFormat.format(weekly.spent)} spent", "Week of ${weekStart.format(dayMonth)}$compare.", "insights"))
        }
        if (!inputs.captureHealthy) {
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            add(AlertCandidate(AlertKind.CAPTURE_HEALTH, "HEALTH:$weekStart", "Auto-capture is paused", "Your phone stopped Kosh reading payment alerts. Tap to fix it.", "sources"))
        }
    }.filter { it.kind in enabled }

    /** 3 days before (or the first day after that the app runs) and on the due date, while unpaid. */
    private fun cardDue(card: CardDueInput, today: LocalDate): AlertCandidate? {
        if (card.paid) return null
        val days = ChronoUnit.DAYS.between(today, card.dueDate)
        val amount = card.totalDue?.let { " · ${MoneyFormat.format(it)}" }.orEmpty()
        val min = card.minDue?.let { " Minimum ${MoneyFormat.format(it)}." }.orEmpty()
        return when (days) {
            in 1L..3L -> AlertCandidate(
                AlertKind.CARD_DUE, "CARD_DUE:${card.accountId}:${card.dueDate}:SOON",
                "${card.name} bill due ${if (days == 1L) "tomorrow" else "in $days days"}$amount",
                "Due ${card.dueDate.format(dayMonth)}.$min", "account:${card.accountId}",
            )
            0L -> AlertCandidate(
                AlertKind.CARD_DUE, "CARD_DUE:${card.accountId}:${card.dueDate}:TODAY",
                "${card.name} bill due today$amount",
                "Pay today to avoid late fees and interest.$min", "account:${card.accountId}",
            )
            else -> null
        }
    }

    private fun budget(b: BudgetInput): AlertCandidate? {
        if (b.available.paise <= 0) return null
        val used = b.spent.paise * 100 / b.available.paise
        return when {
            used >= 100 -> AlertCandidate(
                AlertKind.BUDGET, "BUDGET_OVER:${b.budgetId}:${b.periodStart}", "${b.name} budget used up",
                "${MoneyFormat.format(b.spent)} of ${MoneyFormat.format(b.available)} spent.", "budgets",
            )
            b.warnPercent < 100 && used >= b.warnPercent -> AlertCandidate(
                AlertKind.BUDGET, "BUDGET_WARN:${b.budgetId}:${b.periodStart}", "${b.name}: $used% of budget used",
                "${MoneyFormat.format(b.available - b.spent)} left for this period.", "budgets",
            )
            else -> null
        }
    }
}
