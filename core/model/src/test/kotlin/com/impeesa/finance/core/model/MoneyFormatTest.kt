package com.impeesa.finance.core.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class MoneyFormatTest {

    @Test
    fun `groups digits the Indian way`() {
        assertEquals("486", MoneyFormat.group(486))
        assertEquals("1,000", MoneyFormat.group(1_000))
        assertEquals("38,420", MoneyFormat.group(38_420))
        assertEquals("1,12,000", MoneyFormat.group(1_12_000))
        assertEquals("12,34,56,789", MoneyFormat.group(12_34_56_789))
    }

    @Test
    fun `formats sign and paise`() {
        assertEquals("₹1,12,000", MoneyFormat.format(Money.ofRupees(112_000)))
        assertEquals("−₹486", MoneyFormat.format(Money(-48_600)))
        assertEquals("+₹899", MoneyFormat.format(Money(89_900), signed = true))
        assertEquals("₹486.05", MoneyFormat.format(Money(48_605), showPaise = true))
        assertEquals("₹0", MoneyFormat.format(Money.ZERO, signed = true))
    }

    @Test
    fun `compacts large amounts`() {
        assertEquals("₹1.12 L", MoneyFormat.compact(Money.ofRupees(112_000)))
        assertEquals("₹1 L", MoneyFormat.compact(Money.ofRupees(100_000)))
        assertEquals("₹2.4 Cr", MoneyFormat.compact(Money.ofRupees(24_000_000)))
        assertEquals("₹38.4K", MoneyFormat.compact(Money.ofRupees(38_420)))
        assertEquals("₹9,968", MoneyFormat.compact(Money.ofRupees(9_968)))
    }

    @Test
    fun `speaks amounts for screen readers`() {
        assertEquals("minus 486 rupees", MoneyFormat.spoken(Money(-48_600)))
        assertEquals("1 rupee and 50 paise", MoneyFormat.spoken(Money(150)))
    }

    @Test
    fun `date keys round trip`() {
        val date = LocalDate.of(2026, 10, 11)
        assertEquals(20261011, DateKey.of(date))
        assertEquals(date, DateKey.toLocalDate(20261011))
        assertEquals(20260201..20260228, DateKey.monthRange(YearMonth.of(2026, 2)))
    }
}
