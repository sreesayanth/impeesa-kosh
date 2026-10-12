package com.impeesa.finance.core.model.analytics

import com.impeesa.finance.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CardCycleTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun cycleRunsFromTheDayAfterTheStatement() {
        assertEquals(d("2026-09-20"), CardCycle.lastStatementDate(d("2026-10-11"), 20))
        assertEquals(Period(d("2026-09-21"), d("2026-10-20")), CardCycle.currentCycle(d("2026-10-11"), 20))
        assertEquals(d("2026-10-20"), CardCycle.lastStatementDate(d("2026-10-20"), 20))
    }

    @Test
    fun day31ClampsInShortMonths() {
        assertEquals(d("2027-02-28"), CardCycle.lastStatementDate(d("2027-03-05"), 31))
        assertEquals(Period(d("2027-03-01"), d("2027-03-31")), CardCycle.currentCycle(d("2027-03-05"), 31))
        assertEquals(d("2027-02-28"), CardCycle.nextDueDate(d("2027-02-10"), 30))
    }

    @Test
    fun nextDueDateRollsOverAfterTheDay() {
        assertEquals(d("2026-10-18"), CardCycle.nextDueDate(d("2026-10-11"), 18))
        assertEquals(d("2026-10-18"), CardCycle.nextDueDate(d("2026-10-18"), 18))
        assertEquals(d("2026-11-18"), CardCycle.nextDueDate(d("2026-10-19"), 18))
    }

    @Test
    fun utilisation() {
        assertEquals(0.112f, CardCycle.utilisation(Money(18_680_00), Money(1_67_000_00))!!, 0.001f)
        assertEquals(0f, CardCycle.utilisation(Money(-500_00), Money(1_00_000_00))!!, 0f)
        assertNull(CardCycle.utilisation(Money(100), null))
    }
}
