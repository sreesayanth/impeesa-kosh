package com.impeesa.finance.core.engine

import com.impeesa.finance.core.engine.classify.MessageType
import com.impeesa.finance.core.engine.extract.StatementExtractor
import com.impeesa.finance.core.engine.extract.StatementInfo
import com.impeesa.finance.core.engine.text.TextNormalizer
import com.impeesa.finance.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Statement lines from the corpus (IMPLEMENTATION_PLAN.md §15 Phase 7 tests). */
class StatementExtractorTest {
    private fun read(body: String, received: String) =
        StatementExtractor.extract(TextNormalizer.normalize(body), LocalDate.parse(received))

    private fun info(total: Long?, min: Long?, due: String?) = StatementInfo(total?.let(::Money), min?.let(::Money), due?.let(LocalDate::parse))

    @Test
    fun iciciStatement() = assertEquals(
        info(18_680_00, 940_00, "2026-10-18"),
        read("ICICI Bank Credit Card XX7703 Statement is sent to your email. Total Amount due: INR 18,680.00; Minimum Amount due: INR 940.00. Payment due date: 18-OCT-26.", "2026-10-03"),
    )

    @Test
    fun sbiCardIgnoresTheStatementDate() = assertEquals(
        info(6_240_00, 312_00, "2026-10-02"),
        read("E-statement of SBI Credit Card ending 3390 dated 20/09/26 has been mailed. Total Amt Due Rs.6,240.00; Min Amt Due Rs.312.00; Payable by 02/10/26", "2026-09-21"),
    )

    @Test
    fun hdfcBillReminder() = assertEquals(
        info(12_450_00, 620_00, "2026-10-18"),
        read("HDFC Bank: Your card XX9012 bill of Rs.12,450.00 is due on 18-10-26. Min due Rs.620.00. Pay via NetBanking.", "2026-10-11"),
    )

    @Test
    fun axisWordings() {
        assertEquals(
            info(4_320_00, 216_00, "2026-10-22"),
            read("Axis Bank Credit Card XX4455 statement generated. Total due INR 4,320.00, Minimum due INR 216.00, Due date 22-10-26.", "2026-10-10"),
        )
        assertEquals(
            info(4_320_00, 216_00, "2026-10-22"),
            read("Your Axis Bank Card XX4455 has a payment due of INR 4,320 on 22-10-26. Min amount due INR 216.", "2026-10-12"),
        )
    }

    @Test
    fun dueReminderWithoutMinimum() = assertEquals(
        info(18_680_00, null, "2026-10-18"),
        read("ICICI Bank Credit Card XX7703: Payment due Rs 18,680 by 18-Oct-26. Please ignore if already paid.", "2026-10-12"),
    )

    @Test
    fun engineAttachesStatementsOnlyToStatements() {
        val engine = DetectionEngine.withBundledData()
        val statement = engine.analyze(
            MessageEnvelope(MessageSource.SMS, "AD-SBICRD", "E-statement of SBI Credit Card ending 3390 dated 20/09/26 has been mailed. Total Amt Due Rs.6,240.00; Min Amt Due Rs.312.00; Payable by 02/10/26", Instant.parse("2026-09-21T03:30:00Z")),
        )
        assertEquals(MessageType.STATEMENT, statement.type)
        assertEquals(Money(6_240_00), statement.statement?.totalDue)
        assertEquals("3390", statement.mask)

        val spend = engine.analyze(
            MessageEnvelope(MessageSource.SMS, "VM-HDFCBK", "Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 11-10-26. UPI Ref 628491037215.", Instant.parse("2026-10-11T07:54:00Z")),
        )
        assertEquals(null, spend.statement)
    }
}
