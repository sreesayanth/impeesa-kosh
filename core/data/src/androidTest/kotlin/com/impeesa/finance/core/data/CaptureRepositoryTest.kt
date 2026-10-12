package com.impeesa.finance.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.impeesa.finance.core.data.capture.CaptureRepository
import com.impeesa.finance.core.data.capture.IngestResult
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.engine.DetectionEngine
import com.impeesa.finance.core.engine.MessageEnvelope
import com.impeesa.finance.core.engine.MessageSource
import com.impeesa.finance.core.engine.classify.MessageType
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Money
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CaptureRepositoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), zone)
    private lateinit var db: ImpeesaDatabase
    private lateinit var capture: CaptureRepository
    private lateinit var accounts: AccountRepository
    private lateinit var review: ReviewRepository
    private lateinit var transactions: TransactionRepository

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ImpeesaDatabase::class.java).allowMainThreadQueries().build()
        CategoryRepository(db, clock).ensureSeeded()
        accounts = AccountRepository(db.accountDao(), clock, db)
        accounts.ensureCashWallet()
        capture = CaptureRepository(context, db, DetectionEngine::withBundledData, clock)
        review = ReviewRepository(db, clock)
        transactions = TransactionRepository(db, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun sms(sender: String, body: String, at: String = "2026-10-11T07:54:00Z") =
        MessageEnvelope(MessageSource.SMS, sender, body, Instant.parse(at))

    private val swiggy = sms("VM-HDFCBK", "Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 11-10-26. UPI Ref 628491037215.")

    @Test
    fun confidentSpendIsLoggedOnceAndCounts() = runTest {
        val first = capture.ingest(swiggy)
        assertTrue(first is IngestResult.Logged && !first.needsReview)
        assertEquals(IngestResult.Duplicate, capture.ingest(swiggy.copy(receivedAt = Instant.parse("2026-10-11T07:54:04Z"))))

        val summary = transactions.observeSummary(DateKey.monthRange(YearMonth.of(2026, 10))).first()
        assertEquals(Money(48_600), summary.spent)
        val row = transactions.observeRecent(5).first().single()
        assertEquals("Swiggy", row.counterparty)
        assertEquals("Food & dining", row.categoryName)
    }

    @Test
    fun sameReferenceFromAnotherSenderIsMerged() = runTest {
        capture.ingest(swiggy)
        val copy = sms("AD-HDFCBK", "Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 11-10-26. UPI Ref 628491037215. Not you? Call your bank.")
        assertTrue(capture.ingest(copy) is IngestResult.Merged)
        assertEquals(1, transactions.observeCount().first())
    }

    private fun notification(pkg: String, title: String, body: String, at: String = "2026-10-11T07:54:30Z") =
        MessageEnvelope(MessageSource.NOTIFICATION, pkg, body, Instant.parse(at), title)

    @Test
    fun appNotificationAndBankSmsBecomeOneTransaction() = runTest {
        // GPay arrives first with a clean name; the bank SMS adds account and reference.
        capture.ingest(notification("com.google.android.apps.nbu.paisa.user", "Paid ₹1,299 to Amazon", "Payment successful"))
        val sms = sms("VM-HDFCBK", "Rs.1299.00 debited from A/c XX4821 to VPA amazonupi@apl on 11-10-26. UPI Ref 628455120931.", at = "2026-10-11T07:55:10Z")
        val merged = capture.ingest(sms)
        assertTrue(merged is IngestResult.Merged)

        val rows = transactions.observeRecent(5).first()
        assertEquals(1, rows.size)
        assertEquals("Amazon", rows.single().counterparty)
        val detail = transactions.observeDetail(rows.single().id).first()!!
        assertEquals("628455120931", detail.reference)
        assertEquals(2, transactions.observeSources(rows.single().id).first().size)
    }

    @Test
    fun conflictingMerchantsGoToReviewAsPossibleDuplicate() = runTest {
        capture.ingest(notification("com.phonepe.app", "₹486 paid to Swiggy", "Debited from HDFC Bank - 4821"))
        capture.ingest(sms("VM-HDFCBK", "Rs.486.00 debited from A/c XX4821 to VPA zomato@icici on 11-10-26. UPI Ref 628400777001.", at = "2026-10-11T07:58:00Z"))

        val queue = review.observeQueue().first()
        assertEquals("POSSIBLE_DUPLICATE", queue.single().reviewReason)
        assertTrue(queue.single().duplicateOf != null)

        review.mergeDuplicate(queue.single().id)
        assertEquals(0, review.count())
        assertEquals(1, transactions.observeRecent(5).first().size)
    }

    @Test
    fun mergedMessageCanBeSplitBack() = runTest {
        capture.ingest(notification("com.google.android.apps.nbu.paisa.user", "Paid ₹1,299 to Amazon", "Payment successful"))
        capture.ingest(sms("VM-HDFCBK", "Rs.1299.00 debited from A/c XX4821 to VPA amazonupi@apl on 11-10-26. UPI Ref 628455120931.", at = "2026-10-11T07:55:10Z"))
        val id = transactions.observeRecent(5).first().single().id
        val mergedRaw = transactions.observeSources(id).first().single { it.merged }

        assertTrue(capture.unmerge(mergedRaw.rawId) != null)
        assertEquals(2, transactions.observeRecent(5).first().size)
    }

    @Test
    fun cardBillPaymentPairsWithIssuerConfirmationAndIsNotSpending() = runTest {
        capture.ingest(sms("VM-HDFCBK", "Rs.18,680.00 debited from A/c XX4821 on 11-10-26 towards ICICI Bank Credit Card XX7703 via BillDesk. Ref 628400991122"))
        capture.ingest(sms("AD-ICICIT", "Payment of Rs 18,680.00 has been received on your ICICI Bank Credit Card Account 4xxx7703 on 11-Oct-26. Thank you.", at = "2026-10-11T09:00:00Z"))

        val summary = transactions.observeSummary(DateKey.monthRange(YearMonth.of(2026, 10))).first()
        assertEquals(Money.ZERO, summary.spent)
        assertEquals(Money.ZERO, summary.income)
        // Shown once in Activity: the outgoing side of the transfer.
        val rows = transactions.observeRecent(5).first()
        assertEquals(1, rows.size)
        assertTrue(rows.single().transferGroupId != null)
    }

    @Test
    fun refundLinksToTheOriginalPurchase() = runTest {
        capture.ingest(sms("AD-ICICIT", "INR 1,999.00 spent on ICICI Bank Card XX7703 on 03-Oct-26 at MYNTRA DESIGNS. Avl Lmt: INR 1,54,000.00.", at = "2026-10-03T09:00:00Z"))
        capture.ingest(sms("VM-HDFCBK", "Rs.1,999.00 credited to A/c XX4821 on 11-10-26 by REFUND MYNTRA (IMPS Ref 628400112233)."))

        val refund = transactions.observeRecent(5).first().first()
        assertEquals(com.impeesa.finance.core.model.TxnType.REFUND, refund.type)
        assertEquals("Shopping", refund.categoryName)
    }

    @Test
    fun reversalCancelsTheFailedDebit() = runTest {
        capture.ingest(sms("VM-HDFCBK", "Rs 1,000.00 debited from A/c XX4821 on 08-10-26 but transaction failed. Amount will be reversed within 3 working days.", at = "2026-10-08T09:00:00Z"))
        assertEquals(1, review.count())
        capture.ingest(sms("VM-HDFCBK", "Rs.1,000.00 credited to A/c XX4821 on 11-10-26. Reversal of failed UPI txn Ref 628455009988."))

        assertEquals(0, review.count())
        assertEquals(0, transactions.observeRecent(5).first().size)
    }

    @Test
    fun otpIsDroppedAndOnlyCounted() = runTest {
        val result = capture.ingest(sms("VM-HDFCBK", "OTP is 482913 for txn of INR 1299.00 at AMAZON on HDFC Bank card ending 9012. Do not share OTP."))
        assertEquals(IngestResult.Dropped(MessageType.OTP), result)
        assertEquals(0, transactions.observeCount().first())
        val stats = capture.observeStats(202610).first()
        assertEquals(1, stats.skipped["OTP"])
    }

    @Test
    fun atmWithdrawalMovesMoneyToCash() = runTest {
        capture.ingest(sms("VM-HDFCBK", "Rs.5000 withdrawn from A/c XX4821 at ATM S1AW1234 on 11-10-26. Avl Bal Rs.1,79,350.00"))
        val cash = accounts.observeAccounts().first().single { it.type == AccountType.CASH }
        assertEquals(Money(5_000_00), cash.balance)
        val summary = transactions.observeSummary(DateKey.monthRange(YearMonth.of(2026, 10))).first()
        assertEquals(Money.ZERO, summary.spent)
    }

    @Test
    fun unknownAccountBecomesSuggestionAndAcceptingClaimsRows() = runTest {
        capture.ingest(swiggy)
        val suggestion = accounts.observeSuggestions().first().single()
        assertEquals("4821", suggestion.mask)
        assertEquals("HDFC Bank", suggestion.institution)

        val accountId = accounts.acceptSuggestion(suggestion.id)!!
        val hdfc = accounts.observeAccounts().first().single { it.id == accountId }
        assertEquals(Money(-48_600), hdfc.balance)
        assertTrue(accounts.observeSuggestions().first().isEmpty())

        // The next message from that account is matched directly.
        capture.ingest(sms("VM-HDFCBK", "Rs.120.00 debited from A/c XX4821 to VPA rapido@ybl on 11-10-26. UPI Ref 628455667788."))
        assertEquals(Money(-60_600), accounts.observeAccounts().first().single { it.id == accountId }.balance)
    }

    @Test
    fun uncertainItemsWaitForReviewAndDoNotCountUntilConfirmed() = runTest {
        val result = capture.ingest(sms("AD-ICICIT", "USD 12.99 spent on ICICI Bank Card XX7703 on 11-Oct-26 at OPENAI *CHATGPT. Avl Lmt: INR 1,52,100.00"))
        assertTrue(result is IngestResult.Logged && result.needsReview)
        val queue = review.observeQueue().first()
        assertEquals(1, queue.size)
        assertEquals("FOREIGN_CURRENCY", queue.single().reviewReason)
        assertEquals(0, transactions.observeRecent(5).first().size)

        review.ignore(queue.single().id)
        assertEquals(0, review.count())
        review.backToReview(listOf(queue.single().id))
        assertEquals(1, review.count())
    }
}
