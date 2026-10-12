package com.impeesa.finance.core.model

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrencyFormatTest {
    @After
    fun reset() {
        MoneyFormat.settings = MoneySettings()
    }

    @Test
    fun defaultIsRupeesWithIndianGrouping() {
        assertEquals("₹1,12,000", MoneyFormat.format(Money.ofRupees(112_000)))
        assertEquals("₹1.12 L", MoneyFormat.compact(Money.ofRupees(112_000)))
    }

    @Test
    fun dollarsUseInternationalGroupingAndWords() {
        MoneyFormat.settings = MoneySettings(Currencies.USD)
        assertEquals("$112,000", MoneyFormat.format(Money.ofRupees(112_000)))
        assertEquals("$1,234,567.50", MoneyFormat.format(Money(1_234_567_50), showPaise = true))
        assertEquals("−$486", MoneyFormat.format(Money(-486_00)))
        assertEquals("$1.2M", MoneyFormat.compact(Money.ofRupees(1_234_567)))
        assertEquals("$12.5K", MoneyFormat.compact(Money.ofRupees(12_500)))
        assertEquals("minus 486 dollars and 50 cents", MoneyFormat.spoken(Money(-486_50)))
        assertEquals("1 dollar", MoneyFormat.spoken(Money(100)))
    }

    @Test
    fun groupingCanDifferFromTheCurrencyDefault() {
        MoneyFormat.settings = MoneySettings(Currencies.INR, DigitGrouping.INTERNATIONAL)
        assertEquals("₹1,234,567", MoneyFormat.format(Money.ofRupees(1_234_567)))
        assertEquals("₹1.2M", MoneyFormat.compact(Money.ofRupees(1_234_567)))
        MoneyFormat.settings = MoneySettings(Currencies.AED, DigitGrouping.INDIAN)
        assertEquals("AED 12,34,567", MoneyFormat.format(Money.ofRupees(1_234_567)))
    }

    @Test
    fun unknownCodesFallBackToRupees() {
        assertEquals(Currencies.INR, Currencies.byCode("XYZ"))
        assertEquals(Currencies.GBP, Currencies.byCode("GBP"))
    }
}
