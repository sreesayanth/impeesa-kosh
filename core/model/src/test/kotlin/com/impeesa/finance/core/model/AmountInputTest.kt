package com.impeesa.finance.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class AmountInputTest {

    private fun type(keys: String) = keys.fold(AmountInput()) { acc, c -> acc.press(c) }

    @Test
    fun `typing digits builds an amount`() {
        val input = type("2140")
        assertEquals("2,140", input.display())
        assertEquals(Money(214_000), input.money)
    }

    @Test
    fun `decimals are limited to two`() {
        val input = type("49.505")
        assertEquals("49.50", input.text)
        assertEquals(Money(4_950), input.money)
    }

    @Test
    fun `leading dot and zeros are tidy`() {
        assertEquals("0.", type(".").text)
        assertEquals("5", type("05").text)
        assertEquals("1.5", type("1..5").text)
    }

    @Test
    fun `whole part is capped`() {
        assertEquals(AmountInput.MAX_WHOLE_DIGITS, type("12345678901").text.length)
    }

    @Test
    fun `delete removes last character`() {
        assertEquals("12", type("123").delete().text)
        assertEquals("", AmountInput().delete().text)
    }

    @Test
    fun `display keeps trailing dot and groups lakhs`() {
        assertEquals("1,23,456.", type("123456.").display())
        assertEquals("0", AmountInput().display())
    }

    @Test
    fun `round trips from money`() {
        assertEquals("120", AmountInput.of(Money(12_000)).text)
        assertEquals("120.5", AmountInput.of(Money(12_050)).text)
        assertEquals("120.05", AmountInput.of(Money(12_005)).text)
        assertEquals("", AmountInput.of(Money.ZERO).text)
    }

    @Test
    fun `parses form text`() {
        assertEquals(Money(12_000_050), AmountInput.parse("₹1,20,000.50"))
        assertEquals(Money(50), AmountInput.parse(".5"))
        assertNull(AmountInput.parse("12a"))
        assertNull(AmountInput.parse("1.234"))
        assertNull(AmountInput.parse(""))
    }

    @Test
    fun `date labels`() {
        val today = LocalDate.of(2026, 10, 11)
        assertEquals("Today · Sun 11 Oct", DateLabels.header(today, today))
        assertEquals("Yesterday · Sat 10 Oct", DateLabels.header(today.minusDays(1), today))
        assertEquals("Fri 9 Oct", DateLabels.header(today.minusDays(2), today))
        assertEquals("Thu 9 Oct 2025", DateLabels.header(LocalDate.of(2025, 10, 9), today))
        assertEquals("Yesterday", DateLabels.chip(today.minusDays(1), today))
        assertEquals("1:24 PM", DateLabels.whenShort(today, LocalTime.of(13, 24), today))
        assertEquals("9 Oct", DateLabels.whenShort(today.minusDays(2), LocalTime.NOON, today))
    }
}
