package com.impeesa.finance.core.model.alerts

import com.impeesa.finance.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AlertRulesTest {
    private val due = LocalDate.parse("2026-10-18")
    private val card = CardDueInput(7, "ICICI Card", due, Money(18_680_00), Money(940_00), paid = false)

    private fun keysOn(day: String, inputs: (LocalDate) -> AlertInputs) = AlertRules.evaluate(inputs(LocalDate.parse(day))).map { it.dedupeKey }

    @Test
    fun billRemindersFireThreeDaysBeforeAndOnTheDueDate() {
        val cards = { d: LocalDate -> AlertInputs(d, cards = listOf(card)) }
        assertEquals(emptyList<String>(), keysOn("2026-10-14", cards))
        assertEquals(listOf("CARD_DUE:7:2026-10-18:SOON"), keysOn("2026-10-15", cards))
        // Missed day 3? Day 2 still raises the same (de-duplicated) reminder.
        assertEquals(listOf("CARD_DUE:7:2026-10-18:SOON"), keysOn("2026-10-16", cards))
        assertEquals(listOf("CARD_DUE:7:2026-10-18:TODAY"), keysOn("2026-10-18", cards))
        assertEquals(emptyList<String>(), keysOn("2026-10-19", cards))
        val first = AlertRules.evaluate(cards(LocalDate.parse("2026-10-15"))).single()
        assertEquals("ICICI Card bill due in 3 days · ₹18,680", first.title)
        assertEquals("account:7", first.link)
    }

    @Test
    fun paidBillsStopReminding() {
        assertTrue(AlertRules.evaluate(AlertInputs(due, cards = listOf(card.copy(paid = true)))).isEmpty())
    }

    @Test
    fun budgetWarnsOnceThenOnceMoreWhenUsedUp() {
        val start = LocalDate.parse("2026-10-01")
        fun at(spent: Long) = AlertRules.evaluate(AlertInputs(LocalDate.parse("2026-10-20"), budgets = listOf(BudgetInput(3, "Food & dining", start, Money(spent), Money(10_000_00), 80)))).map { it.dedupeKey }
        assertEquals(emptyList<String>(), at(7_900_00))
        assertEquals(listOf("BUDGET_WARN:3:2026-10-01"), at(8_000_00))
        assertEquals(listOf("BUDGET_OVER:3:2026-10-01"), at(10_000_00))
    }

    @Test
    fun renewalsAndPriceChanges() {
        val netflix = RenewalInput("NETFLIX", "Netflix", LocalDate.parse("2026-10-15"), Money(649_00), null, LocalDate.parse("2026-09-15"))
        assertEquals(listOf("RENEWAL:NETFLIX:2026-10-15"), keysOn("2026-10-12") { AlertInputs(it, renewals = listOf(netflix)) })
        assertEquals(emptyList<String>(), keysOn("2026-10-11") { AlertInputs(it, renewals = listOf(netflix)) })
        val spotify = RenewalInput("SPOTIFY", "Spotify", LocalDate.parse("2026-11-15"), Money(139_00), Money(20_00), LocalDate.parse("2026-10-15"))
        val alert = AlertRules.evaluate(AlertInputs(LocalDate.parse("2026-10-16"), renewals = listOf(spotify))).single()
        assertEquals("PRICE:SPOTIFY:2026-10-15", alert.dedupeKey)
        assertEquals("Now ₹139, ₹20 more than before.", alert.body)
    }

    @Test
    fun largeTransactionsByAmountOrAccountHabit() {
        val inputs = AlertInputs(
            LocalDate.parse("2026-10-11"),
            largeTransactions = listOf(
                LargeTransactionInput(1, "Croma", Money(24_999_00), null),
                LargeTransactionInput(2, "Swiggy", Money(486_00), Money(400_00)),
                LargeTransactionInput(3, "Decathlon", Money(4_200_00), Money(900_00)),
            ),
        )
        assertEquals(listOf("LARGE:1", "LARGE:3"), AlertRules.evaluate(inputs).map { it.dedupeKey })
    }

    @Test
    fun weeklySummaryOnlyOnMonday() {
        val weekly = WeeklyInput(Money(9_968_00), Money(11_200_00))
        assertEquals(emptyList<String>(), keysOn("2026-10-11") { AlertInputs(it, weekly = weekly) })
        val monday = AlertRules.evaluate(AlertInputs(LocalDate.parse("2026-10-12"), weekly = weekly)).single()
        assertEquals("WEEKLY:2026-10-05", monday.dedupeKey)
        assertEquals("Week of 5 Oct, 11% less than the week before.", monday.body)
        // A brand-new install has nothing to summarise.
        val empty = WeeklyInput(Money.ZERO, Money.ZERO)
        assertTrue(AlertRules.evaluate(AlertInputs(LocalDate.parse("2026-10-12"), weekly = empty)).isEmpty())
    }

    @Test
    fun lowBalanceIsOffUnlessEnabledAndSyncNeedsFiveHundred() {
        val low = AlertInputs(LocalDate.parse("2026-10-11"), lowBalances = listOf(LowBalanceInput(4, "SBI", Money(800_00), Money(2_000_00))))
        assertTrue(AlertRules.evaluate(low).isEmpty())
        assertEquals(listOf("LOW:4:2026-10-11"), AlertRules.evaluate(low, enabled = setOf(AlertKind.LOW_BALANCE)).map { it.dedupeKey })

        val syncs = AlertInputs(
            LocalDate.parse("2026-10-11"),
            balanceSyncs = listOf(BalanceSyncInput(10, 1, "HDFC", Money(-400_00)), BalanceSyncInput(11, 1, "HDFC", Money(-2_150_00))),
        )
        assertEquals(listOf("SYNC:11"), AlertRules.evaluate(syncs).map { it.dedupeKey })
    }

    @Test
    fun disabledKindsAreSkipped() {
        val inputs = AlertInputs(due, cards = listOf(card), reviewCount = 2)
        assertEquals(listOf(AlertKind.REVIEW), AlertRules.evaluate(inputs, enabled = setOf(AlertKind.REVIEW)).map { it.kind })
    }
}
