package com.impeesa.finance.core.engine.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountMaskExtractorTest {

    @Test
    fun `reads common mask styles`() {
        assertEquals("4821", AccountMaskExtractor.extract("debited from A/c XX4821 to VPA"))
        assertEquals("4821", AccountMaskExtractor.extract("from A/c *4821 on"))
        assertEquals("7703", AccountMaskExtractor.extract("spent on ICICI Bank Card XX7703"))
        assertEquals("3390", AccountMaskExtractor.extract("spent on SBI Card ending 3390 at"))
        assertEquals("0917", AccountMaskExtractor.extract("Acct no. x0917 credited"))
        assertEquals("1234", AccountMaskExtractor.extract("Account ending with 001234"))
    }

    @Test
    fun `returns null without a mask`() {
        assertNull(AccountMaskExtractor.extract("Payment of ₹1,299 to Amazon was successful"))
    }
}
