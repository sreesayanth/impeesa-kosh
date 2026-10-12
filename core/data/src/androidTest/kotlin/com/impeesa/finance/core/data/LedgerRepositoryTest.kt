package com.impeesa.finance.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.impeesa.finance.core.data.capture.CaptureRepository
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.engine.DetectionEngine
import com.impeesa.finance.core.engine.MessageEnvelope
import com.impeesa.finance.core.engine.MessageSource
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Phase 7 acceptance: balances follow the bank, statements and payments, alert de-duplication. */
@RunWith(AndroidJUnit4::class)
class LedgerRepositoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-10-12T04:00:00Z"), zone)
    private lateinit var db: ImpeesaDatabase
    private lateinit var capture: CaptureRepository
    private lateinit var accounts: AccountRepository
    private lateinit var ledger: LedgerRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ImpeesaDatabase::class.java).allowMainThreadQueries().build()
        CategoryRepository(db, clock).ensureSeeded()
        accounts = AccountRepository(db.accountDao(), clock, db)
        capture = CaptureRepository(context, db, DetectionEngine::withBundledData, clock)
        ledger = LedgerRepository(db, clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun sms(sender: String, body: String, at: String) =
        capture.ingest(MessageEnvelope(MessageSource.SMS, sender, body, Instant.parse(at)))

    private suspend fun balanceOf(id: Long) = accounts.observeAccounts().first().single { it.id == id }

    @Test
    fun bankBalanceFollowsAvlBalAndReconciles() = runTest {
        val hdfc = accounts.save(AccountDraft(type = AccountType.BANK, name = "HDFC", mask = "4821", openingBalance = Money(1_00_000_00)))

        // First snapshot: nothing to reconcile against, the bank's figure simply wins.
        sms("VM-HDFCBK", "Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 11-10-26. UPI Ref 628491037215. Avl Bal Rs.1,84,350.00", "2026-10-11T07:54:00Z")
        assertEquals(Money(1_84_350_00), balanceOf(hdfc).balance)
        assertTrue(balanceOf(hdfc).syncedFromBank)

        // A debit without a balance counts from the snapshot.
        sms("VM-HDFCBK", "Rs.120.00 debited from A/c XX4821 to VPA rapido@ybl on 11-10-26. UPI Ref 628455667788.", "2026-10-11T09:00:00Z")
        assertEquals(Money(1_84_230_00), balanceOf(hdfc).balance)

        // The bank says ₹1,84,000: a ₹230 payment was missed. The bank wins; the difference is kept.
        sms("VM-HDFCBK", "Available Bal in HDFC Bank A/c XX4821 as on yesterday:11-OCT-26 is INR 1,84,000.00. Cheque clearing subject to realisation.", "2026-10-12T02:30:00Z")
        assertEquals(Money(1_84_000_00), balanceOf(hdfc).balance)
        val differences = ledger.recentBalanceDifferences(0, Money(100_00))
        assertEquals(listOf(Money(-230_00)), differences.map { it.difference })
    }

    @Test
    fun cardOwedComesFromAvailableLimit() = runTest {
        val card = accounts.save(AccountDraft(type = AccountType.CARD, name = "ICICI Card", mask = "7703", creditLimit = Money(1_67_000_00)))
        sms("AD-ICICIT", "INR 1,999.00 spent on ICICI Bank Card XX7703 on 11-Oct-26 at MYNTRA DESIGNS. Avl Lmt: INR 1,48,320.00.", "2026-10-11T09:00:00Z")
        // Owed = limit − available.
        assertEquals(Money(18_680_00), balanceOf(card).owed)
    }

    @Test
    fun statementBecomesABillAndPaymentMarksItPaid() = runTest {
        val card = accounts.save(AccountDraft(type = AccountType.CARD, name = "ICICI Card", mask = "7703", creditLimit = Money(1_67_000_00)))
        sms("AD-ICICIT", "ICICI Bank Credit Card XX7703 Statement is sent to your email. Total Amount due: INR 18,680.00; Minimum Amount due: INR 940.00. Payment due date: 18-OCT-26.", "2026-10-03T03:30:00Z")
        val bill = ledger.latestStatements().single()
        assertEquals(card, bill.accountId)
        assertEquals(LocalDate.parse("2026-10-18"), bill.dueDate)
        assertEquals(Money(18_680_00), bill.totalDue)
        assertEquals(Money(940_00), bill.minDue)
        assertFalse(bill.isPaid)

        // The reminder for the same bill doesn't create a second one.
        sms("AD-ICICIT", "ICICI Bank Credit Card XX7703: Payment due Rs 18,680 by 18-Oct-26. Please ignore if already paid.", "2026-10-12T03:30:00Z")
        assertEquals(1, ledger.observeStatements(card).first().size)

        sms("AD-ICICIT", "Payment of Rs 18,680.00 has been received on your ICICI Bank Credit Card Account 4xxx7703 on 12-Oct-26. Thank you.", "2026-10-12T05:00:00Z")
        assertTrue(ledger.latestStatements().single().isPaid)
    }

    @Test
    fun manualBalanceAndMarkPaidUndo() = runTest {
        val cash = accounts.save(AccountDraft(type = AccountType.CASH, name = "Cash", openingBalance = Money(3_200_00)))
        ledger.setBalance(cash, Money(2_950_00))
        assertEquals(Money(2_950_00), balanceOf(cash).balance)
        assertFalse(balanceOf(cash).syncedFromBank)

        val card = accounts.save(AccountDraft(type = AccountType.CARD, name = "SBI Card", mask = "3390"))
        val id = ledger.addStatement(card, Money(6_240_00), Money(312_00), LocalDate.parse("2026-11-02"))
        val before = ledger.markPaid(id)!!
        assertTrue(ledger.latestStatements().single().isPaid)
        ledger.restoreStatement(before)
        assertFalse(ledger.latestStatements().single().isPaid)
    }

    @Test
    fun alertsAreNeverPostedTwice() = runTest {
        assertTrue(ledger.postAlert("CARD_DUE", "CARD_DUE:7:2026-10-18:SOON", "ICICI Card bill due in 3 days", "Due 18 Oct.", "account:7"))
        assertFalse(ledger.postAlert("CARD_DUE", "CARD_DUE:7:2026-10-18:SOON", "ICICI Card bill due in 3 days", "Due 18 Oct.", "account:7"))
        assertEquals(1, ledger.observeAlerts().first().size)
        assertEquals(1, ledger.observeUnreadAlerts().first())
        ledger.markAllAlertsRead()
        assertEquals(0, ledger.observeUnreadAlerts().first())
    }
}
