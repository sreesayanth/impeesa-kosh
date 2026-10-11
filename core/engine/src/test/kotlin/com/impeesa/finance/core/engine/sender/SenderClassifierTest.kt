package com.impeesa.finance.core.engine.sender

import org.junit.Assert.assertEquals
import org.junit.Test

class SenderClassifierTest {

    @Test
    fun `reads commercial headers with and without prefix`() {
        assertEquals(SenderInfo(SenderKind.COMMERCIAL, "HDFCBK"), SenderClassifier.classify("VM-HDFCBK"))
        assertEquals(SenderInfo(SenderKind.COMMERCIAL, "ICICIT"), SenderClassifier.classify("ICICIT"))
    }

    @Test
    fun `reads TRAI category suffix`() {
        assertEquals(HeaderCategory.PROMOTIONAL, SenderClassifier.classify("BZ-AXISBK-P").category)
        assertEquals(HeaderCategory.SERVICE, SenderClassifier.classify("AD-HDFCBK-S").category)
    }

    @Test
    fun `flags personal numbers`() {
        assertEquals(SenderKind.PERSONAL, SenderClassifier.classify("+919812345678").kind)
        assertEquals(SenderKind.PERSONAL, SenderClassifier.classify("9812345678").kind)
    }

    @Test
    fun `anything else is unknown`() {
        assertEquals(SenderKind.UNKNOWN, SenderClassifier.classify("56161").kind)
        assertEquals(SenderKind.UNKNOWN, SenderClassifier.classify("Hello World").kind)
    }
}
