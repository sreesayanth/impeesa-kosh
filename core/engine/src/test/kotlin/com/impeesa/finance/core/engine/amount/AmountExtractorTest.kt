package com.impeesa.finance.core.engine.amount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountExtractorTest {

    @Test
    fun `parses Indian grouping with paise`() {
        assertEquals(12345678L, AmountExtractor.parseMinorUnits("1,23,456.78"))
    }

    @Test
    fun `parses whole rupees and single decimal`() {
        assertEquals(50000L, AmountExtractor.parseMinorUnits("500"))
        assertEquals(4950L, AmountExtractor.parseMinorUnits("49.5"))
        assertEquals(50L, AmountExtractor.parseMinorUnits("0.50"))
    }

    @Test
    fun `rejects malformed numbers`() {
        assertNull(AmountExtractor.parseMinorUnits("1.2.3"))
        assertNull(AmountExtractor.parseMinorUnits("12.345"))
        assertNull(AmountExtractor.parseMinorUnits(""))
    }

    @Test
    fun `finds every currency marker`() {
        val text = "Rs.486.00 debited. INR 500 sent. ₹ 49.5 paid. Rs 500/- spent. Avl Bal Rs.1,79,350"
        val amounts = AmountExtractor.extract(text).map { it.minorUnits }
        assertEquals(listOf(48600L, 50000L, 4950L, 50000L, 17935000L), amounts)
    }

    @Test
    fun `detects foreign currency`() {
        val found = AmountExtractor.extract("USD 12.99 spent on ICICI Card XX7703")
        assertEquals("USD", found.single().currency)
        assertEquals(1299L, found.single().minorUnits)
    }

    @Test
    fun `ignores words ending in rs`() {
        assertTrue(AmountExtractor.extract("Open 24 hrs 500 offers").isEmpty())
    }
}
