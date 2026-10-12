package com.impeesa.finance.core.engine

import com.impeesa.finance.core.engine.account.AccountResolver
import com.impeesa.finance.core.engine.amount.AmountSelector
import com.impeesa.finance.core.engine.extract.ChannelDetector
import com.impeesa.finance.core.engine.extract.CounterpartyExtractor
import com.impeesa.finance.core.engine.extract.DateExtractor
import com.impeesa.finance.core.engine.extract.ReferenceExtractor
import com.impeesa.finance.core.engine.merchant.MerchantDirectory
import com.impeesa.finance.core.engine.merchant.MerchantNormalizer
import com.impeesa.finance.core.engine.merchant.MerchantSource
import com.impeesa.finance.core.engine.tools.Anonymizer
import com.impeesa.finance.core.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class ExtractorsTest {
    private val received = LocalDate.of(2026, 10, 11)

    @Test
    fun `amount selector separates transaction, balance and limit`() {
        val pick = AmountSelector.pick("Rs.5000 withdrawn from A/c XX4821 at ATM on 03-10-26. Avl Bal Rs.1,79,350.00")
        assertEquals(500_000L, pick.transaction?.minorUnits)
        assertEquals(17_935_000L, pick.balance)

        val card = AmountSelector.pick("INR 612.00 spent on ICICI Bank Card XX7703 at ZOMATO. Avl Lmt: INR 1,48,320.00")
        assertEquals(61_200L, card.transaction?.minorUnits)
        assertEquals(14_832_000L, card.limit)
    }

    @Test
    fun `amount selector reads SBI amounts without currency`() {
        val pick = AmountSelector.pick("Dear UPI user A/C X0917 debited by 250.0 on date 09Oct26 trf to RAJU")
        assertEquals(25_000L, pick.transaction?.minorUnits)
        assertFalse(pick.hasMarker)
    }

    @Test
    fun `dates in bank formats`() {
        assertEquals(LocalDate.of(2026, 10, 9), DateExtractor.extract("on 09-10-26 at", received))
        assertEquals(LocalDate.of(2026, 10, 9), DateExtractor.extract("on 09Oct26 trf", received))
        assertEquals(LocalDate.of(2026, 10, 5), DateExtractor.extract("on 05-OCT-26 for", received))
        assertEquals(LocalDate.of(2026, 10, 4), DateExtractor.extract("on 4 October 2026 at", received))
        assertEquals(LocalDate.of(2026, 10, 9), DateExtractor.extract("On 2026-10-09:18:10:22", received))
        assertEquals(LocalDate.of(2026, 10, 6), DateExtractor.extract("on 06/10/2026", received))
    }

    @Test
    fun `implausible dates are ignored`() {
        assertNull(DateExtractor.extract("valid till 12-12-30", received))
        assertNull(DateExtractor.extract("statement for 01-01-25", received))
    }

    @Test
    fun `references`() {
        assertEquals("628491037215", ReferenceExtractor.extract("UPI Ref 628491037215. Not you?"))
        assertEquals("628412345678", ReferenceExtractor.extract("BESCOM credited. UPI:628412345678. Call"))
        assertEquals("628411223344", ReferenceExtractor.extract("UPI/P2M/628411223344/BLINKIT"))
        assertEquals("628412398765", ReferenceExtractor.extract("by (Ref no 628412398765)"))
    }

    @Test
    fun `counterparties`() {
        assertEquals("BLINKIT", CounterpartyExtractor.extract("INR 349.00 debited A/c no. XX2210 UPI/P2M/628411223344/BLINKIT Not you?"))
        assertEquals("swiggy@icici", CounterpartyExtractor.extract("debited from A/c XX4821 to VPA swiggy@icici on 11-10-26."))
        assertEquals("ZOMATO", CounterpartyExtractor.extract("spent on ICICI Bank Card XX7703 on 10-Oct-26 at ZOMATO. Avl Lmt"))
        assertEquals("Rahul Menon", CounterpartyExtractor.extract("Rahul Menon sent you ₹500 Received in HDFC"))
        assertEquals("Amazon", CounterpartyExtractor.extract("Paid ₹1,299 to Amazon UPI transaction ID 628455120931"))
    }

    @Test
    fun `channels`() {
        assertEquals(Channel.ATM, ChannelDetector.detect("Rs 5000 withdrawn at ATM"))
        assertEquals(Channel.UPI, ChannelDetector.detect("to VPA swiggy@icici"))
        assertEquals(Channel.AUTOPAY, ChannelDetector.detect("towards NETFLIX for e-mandate UMN"))
        assertEquals(Channel.CARD, ChannelDetector.detect("spent on HDFC Bank Card XX9012"))
    }

    @Test
    fun `merchant keys are stable and idempotent`() {
        val raw = "UPI/P2M/628491037215/BUNDL TECHNOLOGIES PVT LTD/HDFC BANK"
        val key = MerchantNormalizer.key(raw)
        assertEquals(key, MerchantNormalizer.key(key))
        assertEquals("ZOMATO", MerchantNormalizer.key("ZOMATO LTD"))
        assertEquals("GROWW INVEST", MerchantNormalizer.key("NACH/ACH-DR/GROWW INVEST"))
        assertEquals("swiggy", MerchantNormalizer.vpaLocalPart("swiggy@icici"))
    }

    @Test
    fun `merchant dictionary`() {
        val directory = MerchantDirectory.load()
        assertEquals("Swiggy", directory.lookup("swiggy@icici").displayName)
        assertEquals("Swiggy", directory.lookup("BUNDL TECHNOLOGIES PVT LTD").displayName)
        assertEquals("Amazon", directory.lookup("AMAZON PAY INDIA").displayName)
        assertEquals("Food & dining", directory.lookup("ZOMATO LTD").categoryName)
        val unknown = directory.lookup("RAJU TEA STALL")
        assertEquals(MerchantSource.RAW, unknown.source)
        assertEquals("Raju Tea Stall", unknown.displayName)
        assertTrue(directory.lookup("rahul.m@okaxis").looksPersonal)
    }

    @Test
    fun `account resolution`() {
        val accounts = listOf(
            KnownAccount(1, "4821", setOf("HDFCBK")),
            KnownAccount(2, "0903", setOf("ICICIB")),
            KnownAccount(3, null, setOf("SBIUPI")),
        )
        assertEquals(AccountMatch.Matched(1), AccountResolver.resolve("HDFCBK", "4821", accounts))
        assertEquals(AccountMatch.Matched(2), AccountResolver.resolve("ICICIB", "903", accounts))
        assertEquals(AccountMatch.Matched(3), AccountResolver.resolve("SBIUPI", null, accounts))
        assertEquals(AccountMatch.Suggested("AXISBK", "2210"), AccountResolver.resolve("AXISBK", "2210", accounts))
    }

    @Test
    fun `a bank's second sender header finds the same account`() {
        val senders = com.impeesa.finance.core.engine.registry.SenderRegistry.load()
        assertEquals(setOf("HDFCBK", "HDFCBN"), senders.codesOf("HDFCBN"))
        // A Kotak card also ending 0874 must not make the HDFCBN message ambiguous.
        val accounts = listOf(KnownAccount(1, "0874", setOf("HDFCBK")), KnownAccount(2, "0874", setOf("KOTAKB"), isCard = true))
        assertEquals(AccountMatch.Matched(1), AccountResolver.resolve("HDFCBN", "0874", accounts, senders.codesOf("HDFCBN")))
    }

    @Test
    fun `engine never throws on junk`() {
        val engine = DetectionEngine.withBundledData()
        val junk = listOf("", " ", "₹", "Rs.", "Rs 99999999999999999999 debited", "\u0000\u0001", "x".repeat(5000))
        junk.forEach { body ->
            val result = engine.analyze(MessageEnvelope(MessageSource.SMS, "VM-HDFCBK", body, java.time.Instant.EPOCH))
            assertFalse("junk logged: $body", result.route == Route.AUTO_LOG)
        }
    }

    @Test
    fun `anonymizer removes numbers and names`() {
        val out = Anonymizer.scrub("Sent Rs.2,000.00 From HDFC Bank A/C *4821 To RAHUL MENON Ref 628455120931 vpa rahul.m@okaxis", Random(1))
        assertFalse("4821" in out)
        assertFalse("628455120931" in out)
        assertFalse("RAHUL" in out)
        assertFalse("rahul.m" in out)
        assertTrue("2,000.00" in out)
    }
}
