package com.impeesa.finance.core.engine.classify

import com.impeesa.finance.core.engine.sender.SenderClassifier
import com.impeesa.finance.core.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

/** Samples mirror IMPLEMENTATION_PLAN.md Appendix A. */
class MessageTypeClassifierTest {

    private fun type(body: String, sender: String = "VM-HDFCBK") =
        MessageTypeClassifier.classify(body, SenderClassifier.classify(sender))

    @Test
    fun `UPI debit is a transaction`() {
        val c = type("Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 11-10-26. UPI Ref 628491037215. Not you? Call your bank. Never share your OTP.")
        assertEquals(MessageType.TRANSACTION, c.type)
        assertEquals(Direction.DEBIT, c.direction)
    }

    @Test
    fun `card spend with available limit is a debit`() {
        val c = type("INR 612.00 spent on ICICI Bank Card XX7703 on 10-Oct-26 at ZOMATO. Avl Lmt: INR 1,48,320", "AD-ICICIT")
        assertEquals(Classification(MessageType.TRANSACTION, Direction.DEBIT), c)
    }

    @Test
    fun `OTP is dropped`() {
        assertEquals(MessageType.OTP, type("Your OTP for transaction of Rs 2,000 is 482913. Do not share", "JD-SBIINB").type)
        assertEquals(MessageType.OTP, type("482913 is your one time password for login. Valid 5 min").type)
    }

    @Test
    fun `collect request is not a transaction`() {
        assertEquals(MessageType.COLLECT_REQUEST, type("rahul.m@okaxis has requested Rs.500 via UPI. Approve in your app").type)
    }

    @Test
    fun `promotions are dropped`() {
        assertEquals(MessageType.PROMOTIONAL, type("Pre-approved personal loan up to Rs 5,00,000! Apply now", "BZ-AXISBK-P").type)
        assertEquals(MessageType.PROMOTIONAL, type("Get Rs 500 cashback up to on your next order. Click bit.ly/x", "AD-SWIGGY").type)
    }

    @Test
    fun `refund is a credit refund`() {
        val c = type("Rs.899.00 credited to A/c XX4821 from MYNTRA REFUND on 08-10-26")
        assertEquals(Classification(MessageType.REFUND, Direction.CREDIT), c)
    }

    @Test
    fun `card payment received is a credit transaction`() {
        val c = type("Payment of Rs 18,680.00 received towards your ICICI Bank Credit Card XX7703", "AD-ICICIT")
        assertEquals(Classification(MessageType.TRANSACTION, Direction.CREDIT), c)
    }

    @Test
    fun `pre-debit notice is upcoming`() {
        assertEquals(MessageType.UPCOMING, type("Your A/c XX4821 will be debited Rs.649 on 15-10-26 towards NETFLIX mandate").type)
    }

    @Test
    fun `mandate debit that happened is a transaction`() {
        assertEquals(MessageType.TRANSACTION, type("Rs.649.00 debited from A/c XX4821 towards NETFLIX mandate on 15-10-26").type)
    }

    @Test
    fun `failed and failed-but-debited`() {
        assertEquals(MessageType.FAILED, type("Transaction of Rs 2,499 on card XX7703 at AMAZON failed due to insufficient limit", "AD-ICICIT").type)
        assertEquals(
            MessageType.FAILED_BUT_DEBITED,
            type("Rs 1,000 debited from A/c XX4821 but transaction failed. Amount will be reversed in 3 days").type,
        )
    }

    @Test
    fun `statement and balance only`() {
        assertEquals(MessageType.STATEMENT, type("ICICI Card XX7703 statement: Total amount due Rs 18,680, Minimum amount due Rs 940, due by 18-Oct", "AD-ICICIT").type)
        assertEquals(MessageType.BALANCE, type("Available balance in A/c XX4821 is Rs 1,84,350 as on 11-10-26").type)
    }

    @Test
    fun `ATM withdrawal is a debit`() {
        val c = type("Rs.5000 withdrawn at ATM S1AW1234 from A/c XX4821 on 03-10-26. Avl Bal Rs.1,79,350")
        assertEquals(Classification(MessageType.TRANSACTION, Direction.DEBIT), c)
    }

    @Test
    fun `chatty text is unknown`() {
        assertEquals(MessageType.UNKNOWN, type("Your parcel is out for delivery today").type)
    }
}
