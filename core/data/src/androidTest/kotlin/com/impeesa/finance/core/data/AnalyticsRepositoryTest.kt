package com.impeesa.finance.core.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.impeesa.finance.core.data.capture.CaptureRepository
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.engine.DetectionEngine
import com.impeesa.finance.core.engine.MessageEnvelope
import com.impeesa.finance.core.engine.MessageSource
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.analytics.Flows
import com.impeesa.finance.core.model.analytics.Periods
import com.impeesa.finance.core.model.analytics.RecurringKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AnalyticsRepositoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), zone)
    private val today = LocalDate.now(clock)
    private lateinit var db: ImpeesaDatabase
    private lateinit var capture: CaptureRepository
    private lateinit var analytics: AnalyticsRepository
    private lateinit var budgets: BudgetRepository
    private lateinit var transactions: TransactionRepository
    private val ids = mutableMapOf<String, Long>()

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ImpeesaDatabase::class.java).allowMainThreadQueries().build()
        val categories = CategoryRepository(db, clock)
        categories.ensureSeeded()
        AccountRepository(db.accountDao(), clock, db).ensureCashWallet()
        capture = CaptureRepository(context, db, DetectionEngine::withBundledData, clock)
        analytics = AnalyticsRepository(db, clock)
        budgets = BudgetRepository(db, clock)
        transactions = TransactionRepository(db, clock)
        categories.observeAll().first().forEach { ids[it.name] = it.id }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun sms(sender: String, body: String, at: String) =
        capture.ingest(MessageEnvelope(MessageSource.SMS, sender, body, Instant.parse(at)))

    @Test
    fun refundsAreNettedFromSpendingAndTheirCategory() = runTest {
        sms("AD-ICICIT", "INR 1,999.00 spent on ICICI Bank Card XX7703 on 03-Oct-26 at MYNTRA DESIGNS. Avl Lmt: INR 1,54,000.00.", "2026-10-03T09:00:00Z")
        sms("VM-HDFCBK", "Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici on 05-10-26. UPI Ref 628491037215.", "2026-10-05T07:54:00Z")
        sms("VM-HDFCBK", "Rs.1,999.00 credited to A/c XX4821 on 08-10-26 by REFUND MYNTRA (IMPS Ref 628400112233).", "2026-10-08T09:00:00Z")

        val october = Periods.budget(today, 1)
        val days = analytics.observeDaily(october.start, october.end).first()
        assertEquals(Money(486_00), Flows.spending(days, october))
        // Home's summary agrees with the analytics definition.
        assertEquals(Money(486_00), transactions.observeSummary(DateKey.monthRange(YearMonth.of(2026, 10))).first().spent)

        val totals = analytics.categoryTotals(october)
        assertEquals(listOf("Food & dining"), totals.map { it.name })
        assertEquals(Money(486_00), totals.single().total)
    }

    @Test
    fun budgetsAreOnePerCategory() = runTest {
        val food = ids.getValue("Food & dining")
        val first = budgets.save(Budget(categoryId = food, amount = Money(10_000_00)))
        val second = budgets.save(Budget(categoryId = food, amount = Money(11_500_00), alertPercent = 50))
        assertEquals(first, second)
        val all = budgets.observe().first()
        assertEquals(1, all.size)
        assertEquals(Money(11_500_00), all.single().amount)

        budgets.save(Budget(categoryId = null, amount = Money(60_000_00), rollover = true))
        assertEquals(2, budgets.observe().first().size)

        val removed = budgets.delete(first)!!
        assertEquals(1, budgets.observe().first().size)
        budgets.restore(removed)
        assertEquals(2, budgets.observe().first().size)
    }

    @Test
    fun monthlyCardSpendsBecomeASubscription() = runTest {
        listOf("15-Jul-26" to "2026-07-15", "15-Aug-26" to "2026-08-15", "15-Sep-26" to "2026-09-15").forEach { (text, iso) ->
            sms("AD-ICICIT", "INR 649.00 spent on ICICI Bank Card XX7703 on $text at NETFLIX. Avl Lmt: INR 1,54,000.00.", "${iso}T04:00:00Z")
        }
        val summary = analytics.observeRecurring(today).first()
        val netflix = summary.series.single()
        assertEquals(RecurringKind.SUBSCRIPTION, netflix.kind)
        // 15 Jul → 15 Aug → 15 Sep are 31-day gaps, so the next charge is expected 31 days on.
        assertEquals(LocalDate.parse("2026-10-16"), netflix.nextDate)
        assertNull(summary.salaryDay)

        analytics.hideRecurring(netflix.key)
        assertTrue(analytics.observeRecurring(today).first().series.isEmpty())
        analytics.unhideRecurring(netflix.key)
        assertEquals(1, analytics.observeRecurring(today).first().series.size)
    }
}
