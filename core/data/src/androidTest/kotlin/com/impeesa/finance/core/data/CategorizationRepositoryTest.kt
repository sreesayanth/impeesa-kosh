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
import com.impeesa.finance.core.engine.category.RuleAction
import com.impeesa.finance.core.engine.category.RuleMatch
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

/** Phase 5 acceptance (IMPLEMENTATION_PLAN.md §15): learning, ambiguity, rules now and for history. */
@RunWith(AndroidJUnit4::class)
class CategorizationRepositoryTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val clock = Clock.fixed(Instant.parse("2026-10-11T10:00:00Z"), zone)
    private lateinit var db: ImpeesaDatabase
    private lateinit var capture: CaptureRepository
    private lateinit var categorization: CategorizationRepository
    private lateinit var transactions: TransactionRepository
    private lateinit var review: ReviewRepository
    private val ids = mutableMapOf<String, Long>()

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, ImpeesaDatabase::class.java).allowMainThreadQueries().build()
        val categories = CategoryRepository(db, clock)
        categories.ensureSeeded()
        AccountRepository(db.accountDao(), clock, db).ensureCashWallet()
        capture = CaptureRepository(context, db, DetectionEngine::withBundledData, clock)
        categorization = CategorizationRepository(db, clock)
        transactions = TransactionRepository(db, clock)
        review = ReviewRepository(db, clock)
        categories.observeAll().first().forEach { ids[it.name] = it.id }
    }

    @After
    fun tearDown() = db.close()

    private var seq = 0

    /** A new Swiggy UPI debit each time (different reference and minute, so never a duplicate). */
    private suspend fun swiggy(amount: String = "486.00"): Long {
        seq++
        val body = "Rs.$amount debited from A/c XX4821 to VPA swiggy@icici on 11-10-26. UPI Ref 62849103${seq.toString().padStart(4, '0')}."
        val result = capture.ingest(MessageEnvelope(MessageSource.SMS, "VM-HDFCBK", body, Instant.parse("2026-10-11T07:00:00Z").plusSeconds(seq * 600L)))
        return (result as IngestResult.Logged).transactionId
    }

    private suspend fun detail(id: Long) = transactions.observeDetail(id).first()!!

    @Test
    fun correctingTwiceTeachesTheNextCapture() = runTest {
        val first = swiggy()
        assertEquals(ids["Food & dining"], detail(first).categoryId)
        categorization.changeCategory(first, ids.getValue("Groceries"))

        // One correction is not enough to change what happens next.
        val second = swiggy()
        assertEquals(ids["Food & dining"], detail(second).categoryId)
        categorization.changeCategory(second, ids.getValue("Groceries"))

        val third = swiggy()
        assertEquals(ids["Groceries"], detail(third).categoryId)
        assertEquals("LEARNED", detail(third).categorySource)
        assertEquals(1, categorization.observeLearned().first().size)
    }

    @Test
    fun conflictingCorrectionsSendTheMerchantToReview() = runTest {
        categorization.changeCategory(swiggy(), ids.getValue("Groceries"))
        categorization.changeCategory(swiggy(), ids.getValue("Shopping"))

        val next = swiggy()
        val queued = review.observeQueue().first().single { it.id == next }
        assertEquals("AMBIGUOUS_CATEGORY", queued.reviewReason)
        // Equal counts: the most recent answer is suggested first.
        assertEquals(ids["Shopping"], queued.categoryId)
        assertTrue(categorization.observeLearned().first().single().ambiguous)

        // Confirming with the other category is one more correction, and counts once confirmed.
        review.confirm(next, ids.getValue("Groceries"))
        assertEquals(ids["Groceries"], detail(next).categoryId)
        assertEquals(false, detail(next).pendingReview)
    }

    @Test
    fun alwaysSortMakesARuleThatAppliesAtOnce() = runTest {
        val change = categorization.changeCategory(swiggy(), ids.getValue("Groceries"))!!
        categorization.setAlwaysSort(change, true)

        val next = swiggy()
        assertEquals(ids["Groceries"], detail(next).categoryId)
        assertEquals("RULE", detail(next).categorySource)
        assertEquals("Your rule: Merchant is Swiggy", detail(next).categoryReason)
        val rule = categorization.observeRules().first().single()
        assertTrue(rule.fromEdit)
        assertEquals(1, rule.matchCount)

        categorization.setAlwaysSort(change, false)
        assertTrue(categorization.observeRules().first().isEmpty())
    }

    @Test
    fun applyToEarlierSkipsRowsSetByHandAndCanBeUndone() = runTest {
        val handSet = swiggy()
        val untouched = swiggy()
        val corrected = swiggy()
        categorization.changeCategory(handSet, ids.getValue("Shopping"))
        val change = categorization.changeCategory(corrected, ids.getValue("Groceries"))!!
        assertEquals(1, change.earlierCount)

        val snapshots = categorization.applyToEarlier(change)
        assertEquals(listOf(untouched), snapshots.map { it.transactionId })
        assertEquals(ids["Groceries"], detail(untouched).categoryId)
        assertEquals(ids["Shopping"], detail(handSet).categoryId)

        categorization.restore(snapshots)
        assertEquals(ids["Food & dining"], detail(untouched).categoryId)
    }

    @Test
    fun editorRuleAppliesToHistoryAndCanBeUndone() = runTest {
        val a = swiggy()
        val b = swiggy("120.00")
        val draft = RuleDraft(match = RuleMatch.CONTAINS, pattern = "swiggy", action = RuleAction.SET_CATEGORY, categoryId = ids.getValue("Groceries"))
        assertEquals(2, categorization.countHistoryMatches(draft))

        val ruleId = categorization.saveRule(draft)
        val snapshots = categorization.applyRuleToHistory(ruleId)
        assertEquals(setOf(a, b), snapshots.map { it.transactionId }.toSet())
        assertEquals("RULE", detail(a).categorySource)

        categorization.restore(snapshots)
        assertEquals(ids["Food & dining"], detail(a).categoryId)
        assertEquals(2, categorization.observeRules().first().single().matchCount)
    }

    @Test
    fun excludeRuleKeepsCapturesOutOfSpending() = runTest {
        categorization.saveRule(RuleDraft(match = RuleMatch.MERCHANT, pattern = "Swiggy", action = RuleAction.EXCLUDE))
        val id = swiggy()
        assertTrue(detail(id).excludedFromSpending)
        val summary = transactions.observeSummary(DateKey.monthRange(YearMonth.of(2026, 10))).first()
        assertEquals(Money.ZERO, summary.spent)
    }

    @Test
    fun askRuleSendsEveryMatchToReview() = runTest {
        categorization.saveRule(RuleDraft(match = RuleMatch.VPA, pattern = "swiggy@icici", action = RuleAction.ASK))
        val id = swiggy()
        assertEquals("ASK_CATEGORY", review.observeQueue().first().single { it.id == id }.reviewReason)
    }
}
