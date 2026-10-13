package com.impeesa.finance.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TransferHintsTest {
    private val engine = DetectionEngine.withBundledData()

    private fun kind(sender: String, body: String, title: String? = null, source: MessageSource = MessageSource.SMS, isCard: Boolean = false): MovementKind {
        val envelope = MessageEnvelope(source, sender, body, Instant.parse("2026-10-11T07:00:00Z"), title)
        return TransferHints.classify(envelope, engine.analyze(envelope), isCard)
    }

    @Test
    fun `atm withdrawal`() = assertEquals(
        MovementKind.ATM_WITHDRAWAL,
        kind("VM-HDFCBK", "Rs.5000 withdrawn from A/c XX4821 at ATM S1AW1234 on 11-10-26. Avl Bal Rs.1,79,350.00"),
    )

    @Test
    fun `card bill paid from the bank`() = assertEquals(
        MovementKind.CARD_PAYMENT,
        kind("VM-HDFCBK", "Rs.18,680.00 debited from A/c XX4821 on 11-10-26 towards ICICI Bank Credit Card XX7703 via BillDesk. Ref 628400991122"),
    )

    @Test
    fun `card bill paid through CRED`() = assertEquals(
        MovementKind.CARD_PAYMENT,
        kind("com.dreamplug.androidapp", "₹18,680 paid to ICICI Bank credit card XX7703", title = "Payment successful", source = MessageSource.NOTIFICATION),
    )

    @Test
    fun `card issuer confirms the payment`() {
        assertEquals(MovementKind.CARD_PAYMENT, kind("AD-ICICIT", "Payment of Rs 18,680.00 has been received on your ICICI Bank Credit Card Account 4xxx7703 on 11-Oct-26. Thank you."))
        assertEquals(MovementKind.CARD_PAYMENT, kind("AD-SBICRD", "We have received payment of Rs.6,240.00 towards your SBI Credit Card ending 3390 on 11/10/26. Thank you."))
        assertEquals(MovementKind.CARD_PAYMENT, kind("AD-AXISBK", "Payment of INR 1,499 for your Axis Bank Credit Card XX4455 received. Thank you."))
    }

    @Test
    fun `spending on a credit card is not a card payment`() = assertEquals(
        MovementKind.NORMAL,
        kind("AD-ICICIT", "INR 612.00 spent on ICICI Bank Card XX7703 on 11-Oct-26 at ZOMATO. Avl Lmt: INR 1,48,320.00"),
    )

    @Test
    fun `UPI spend charged to a RuPay credit card is not a card payment`() = assertEquals(
        MovementKind.NORMAL,
        kind(
            "JX-JTEDGE-S",
            "Rs.245.00 debited to your Edge CSB Bank RuPay Credit Card ending 4417 on 10/9/26, 8:15 PM - (UPI Ref no.628812345670). " +
                "To dispute, call 8045219280. Thanks for using the Jupiter App.",
        ),
    )

    @Test
    fun `card bill paid from an account to your card`() = assertEquals(
        MovementKind.CARD_PAYMENT,
        kind("AD-AXISBK", "INR 5,000.00 debited from A/c no. XX4821 on 11-10-26 for payment to your Axis Bank Credit Card XX4455."),
    )

    @Test
    fun `reversal of a failed payment`() = assertEquals(
        MovementKind.REVERSAL,
        kind("VM-HDFCBK", "Rs.1,000.00 credited to A/c XX4821 on 11-10-26. Reversal of failed UPI txn Ref 628455009988."),
    )

    @Test
    fun `merchant refund stays a refund`() = assertEquals(
        MovementKind.NORMAL,
        kind("VM-HDFCBK", "Rs.899.00 credited to A/c XX4821 on 11-10-26 by REFUND MYNTRA (IMPS Ref 628400112233)."),
    )

    @Test
    fun `salary is ordinary income`() = assertEquals(
        MovementKind.NORMAL,
        kind("JD-HDFCBK-S", "Update! INR 25,000.00 deposited in HDFC Bank A/c XX4821 on 05-OCT-26 for NEFT Cr-HDFC0000001-ACME CORP-SALARY."),
    )
}
