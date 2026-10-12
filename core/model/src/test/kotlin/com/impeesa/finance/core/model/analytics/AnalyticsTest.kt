package com.impeesa.finance.core.model.analytics

import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeriodsTest {
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun calendarMonthWhenStartDayIsFirst() {
        assertEquals(Period(d("2026-10-01"), d("2026-10-31")), Periods.budget(d("2026-10-11"), 1))
    }

    @Test
    fun salaryCycleStartsOnTheChosenDay() {
        // Paid on the 25th: 25 Sep – 24 Oct contains 11 Oct; 25 Oct starts the next cycle.
        assertEquals(Period(d("2026-09-25"), d("2026-10-24")), Periods.budget(d("2026-10-11"), 25))
        assertEquals(Period(d("2026-10-25"), d("2026-11-24")), Periods.budget(d("2026-10-25"), 25))
    }

    @Test
    fun startDay31MeansTheLastDayOfEachMonth() {
        // Leap year: 29 Feb 2028 starts the cycle, which runs to 30 Mar (31 Mar starts the next).
        assertEquals(Period(d("2028-02-29"), d("2028-03-30")), Periods.budget(d("2028-03-10"), 31))
        assertEquals(Period(d("2028-01-31"), d("2028-02-28")), Periods.budget(d("2028-02-28"), 31))
        // Non-leap year.
        assertEquals(Period(d("2027-02-28"), d("2027-03-30")), Periods.budget(d("2027-03-01"), 31))
        assertEquals(31, Periods.budget(d("2027-03-01"), 31).days)
    }

    @Test
    fun startDay30InFebruaryClampsToTheLastDay() {
        assertEquals(Period(d("2027-01-30"), d("2027-02-27")), Periods.budget(d("2027-02-10"), 30))
        assertEquals(Period(d("2027-02-28"), d("2027-03-29")), Periods.budget(d("2027-03-05"), 30))
    }

    @Test
    fun offsetsWalkWholePeriods() {
        assertEquals(Period(d("2026-07-25"), d("2026-08-24")), Periods.budget(d("2026-10-11"), 25, -2))
        assertEquals(Period(d("2026-10-25"), d("2026-11-24")), Periods.budget(d("2026-10-11"), 25, 1))
    }

    @Test
    fun weeksStartOnMonday() {
        assertEquals(Period(d("2026-10-05"), d("2026-10-11")), Periods.week(d("2026-10-11")))
        assertEquals(Period(d("2026-09-28"), d("2026-10-04")), Periods.week(d("2026-10-11"), -1))
    }

    @Test
    fun elapsedAndDaysLeftIncludeToday() {
        val oct = Periods.budget(d("2026-10-11"), 1)
        assertEquals(11, oct.elapsedDays(d("2026-10-11")))
        assertEquals(21, oct.daysLeft(d("2026-10-11")))
        assertEquals(1, oct.daysLeft(d("2026-10-31")))
        assertEquals(0, oct.daysLeft(d("2026-11-01")))
    }

    @Test
    fun likeForLikeComparesTheSameNumberOfDays() {
        val today = d("2026-10-11")
        val current = Periods.budget(today, 1)
        val previous = Periods.budget(today, 1, -1)
        assertEquals(Period(d("2026-09-01"), d("2026-09-11")), Periods.likeForLike(current, previous, today))
        // 31 days into a 31-day month vs a 30-day month: clamp to the shorter month.
        assertEquals(Period(d("2026-09-01"), d("2026-09-30")), Periods.likeForLike(current, previous, d("2026-10-31")))
    }
}

class FlowsAndBudgetTest {
    private fun d(s: String) = LocalDate.parse(s)
    private fun day(s: String, spent: Long = 0, refunds: Long = 0, income: Long = 0) = DayFlow(DateKey.of(d(s)), spent, refunds, income)

    private val days = listOf(
        day("2026-09-28", spent = 1_999_00), // Myntra order, refunded next month
        day("2026-10-01", spent = 18_000_00, income = 1_12_000_00),
        day("2026-10-03", spent = 486_00),
        day("2026-10-05", refunds = 1_999_00), // the refund lands in October
        day("2026-10-09", spent = 4_210_50),
    )

    @Test
    fun spendingNetsRefundsInThePeriodTheyArrive() {
        val oct = Periods.budget(d("2026-10-11"), 1)
        val sep = Periods.budget(d("2026-10-11"), 1, -1)
        assertEquals(Money(18_000_00 + 486_00 - 1_999_00 + 4_210_50), Flows.spending(days, oct))
        assertEquals(Money(1_999_00), Flows.spending(days, sep))
        val flow = Flows.flow(days, oct)
        assertEquals(Money(1_12_000_00), flow.income)
        assertEquals(Money(1_12_000_00 - 20_697_50), flow.saved)
        assertEquals(0.8152, flow.savingsRate!!, 0.0001)
    }

    @Test
    fun savingsRateIsHiddenWithoutIncome() {
        assertNull(PeriodFlow(Periods.week(d("2026-10-11")), Money(100), Money.ZERO).savingsRate)
    }

    @Test
    fun weekdayAndWeekendAveragesOnlyCountDaysSoFar() {
        val week = Periods.week(d("2026-10-11")) // Mon 5 – Sun 11
        val flows = listOf(day("2026-10-05", spent = 1_000_00), day("2026-10-10", spent = 3_000_00), day("2026-10-11", spent = 1_000_00))
        val (weekday, weekend) = Flows.weekdayWeekend(flows, week, d("2026-10-11"))
        assertEquals(Money(200_00), weekday) // ₹1,000 over 5 weekdays
        assertEquals(Money(2_000_00), weekend) // ₹4,000 over 2 weekend days
    }

    @Test
    fun paceAndSafeToSpend() {
        val oct = Periods.budget(d("2026-10-11"), 1)
        assertEquals(Money(60_000_00L * 11 / 31), BudgetMath.pace(Money(60_000_00), oct, d("2026-10-11")))
        // (60,000 − 38,420 − 599 upcoming) ÷ 21 days left including today
        assertEquals(Money((60_000_00L - 38_420_00 - 599_00) / 21), BudgetMath.safeToSpendPerDay(Money(60_000_00), Money(38_420_00), Money(599_00), oct, d("2026-10-11")))
        assertEquals(Money.ZERO, BudgetMath.safeToSpendPerDay(Money(10_000_00), Money(12_000_00), Money.ZERO, oct, d("2026-10-11")))
    }

    @Test
    fun budgetStatusWithCarryOver() {
        val carried = BudgetMath.carryOver(Money(10_000_00), Money(8_500_00))
        assertEquals(Money(1_500_00), carried)
        assertEquals(Money.ZERO, BudgetMath.carryOver(Money(10_000_00), Money(12_000_00)))
        val status = BudgetStatus(Money(10_000_00), Money(11_000_00), carried)
        assertFalse(status.over)
        assertEquals(Money(500_00), status.remaining)
        assertTrue(status.reached(80))
    }

    @Test
    fun suggestionIsTheMedianRoundedUpToFiveHundred() {
        assertEquals(Money(11_500_00), BudgetMath.suggest(listOf(Money(10_200_00), Money(11_340_00), Money(12_900_00))))
        assertEquals(Money(10_000_00), BudgetMath.suggest(listOf(Money(9_000_00), Money(11_000_00))))
        assertNull(BudgetMath.suggest(emptyList()))
    }

    @Test
    fun overrunStreakCountsTheLatestPeriods() {
        val over = BudgetStatus(Money(100), Money(200))
        val fine = BudgetStatus(Money(200), Money(100))
        assertEquals(3, BudgetMath.overrunStreak(listOf(fine, over, over, over)))
        assertEquals(0, BudgetMath.overrunStreak(listOf(over, over, fine)))
    }

    @Test
    fun heatmapLevels() {
        val history = (1..20).map { Money(it * 100_00L) }
        val t = Heatmap.thresholds(history)
        assertEquals(0, Heatmap.level(Money.ZERO, t))
        assertEquals(1, Heatmap.level(Money(100_00), t))
        assertEquals(4, Heatmap.level(Money(2_000_00), t))
    }
}

class RecurringDetectorTest {
    private val today = LocalDate.parse("2026-10-11")
    private fun pay(key: String, amount: Long, date: String, credit: Boolean = false) = Occurrence(key, key.lowercase().replaceFirstChar { it.uppercase() }, Money(amount), LocalDate.parse(date), credit)

    @Test
    fun monthlySubscriptionWithAPriceRise() {
        val history = listOf(
            pay("SPOTIFY", 119_00, "2026-06-15"),
            pay("SPOTIFY", 119_00, "2026-07-15"),
            pay("SPOTIFY", 119_00, "2026-08-14"),
            pay("SPOTIFY", 139_00, "2026-09-15"),
        )
        val s = RecurringDetector.detect(history, today).single()
        assertEquals(Cadence.MONTHLY, s.cadence)
        // Intervals 30, 30, 32 days: median 30 after the last payment.
        assertEquals(LocalDate.parse("2026-10-15"), s.nextDate)
        assertEquals(Money(20_00), s.priceChange)
    }

    @Test
    fun exactAmountsAreSubscriptionsVaryingOnesAreBills() {
        val netflix = listOf("2026-07-15", "2026-08-15", "2026-09-15").map { pay("NETFLIX", 649_00, it) }
        val power = listOf(1_820_00L to "2026-07-05", 2_010_00L to "2026-08-05", 1_950_00L to "2026-09-04").map { (a, d) -> pay("BESCOM", a, d) }
        val found = RecurringDetector.detect(netflix + power, today).associateBy { it.key }
        assertEquals(RecurringKind.SUBSCRIPTION, found.getValue("NETFLIX").kind)
        assertEquals(RecurringKind.BILL, found.getValue("BESCOM").kind)
        assertNull(found.getValue("NETFLIX").priceChange)
    }

    @Test
    fun largestMonthlyCreditIsTheSalaryAndSuggestsTheStartDay() {
        val salary = listOf("2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01").map { pay("ACME CORP", 1_12_000_00, it, credit = true) }
        val rent = listOf("2026-07-03", "2026-08-03", "2026-09-03", "2026-10-03").map { pay("FLAT 2B", 5_000_00, it, credit = true) }
        val series = RecurringDetector.detect(salary + rent, today)
        assertEquals(RecurringKind.SALARY, series.single { it.key == "ACME CORP" }.kind)
        assertEquals(RecurringKind.INCOME, series.single { it.key == "FLAT 2B" }.kind)
        assertEquals(1, RecurringDetector.salaryDay(series, salary + rent))
    }

    @Test
    fun irregularOrUnsteadyPaymentsAreNotRecurring() {
        val swiggy = listOf(486_00L to "2026-09-02", 1_240_00L to "2026-09-09", 312_00L to "2026-09-30", 650_00L to "2026-10-02").map { (a, d) -> pay("SWIGGY", a, d) }
        assertTrue(RecurringDetector.detect(swiggy, today).isEmpty())
        val twice = listOf("2026-08-15", "2026-09-15").map { pay("NETFLIX", 649_00, it) }
        assertTrue(RecurringDetector.detect(twice, today).isEmpty())
    }

    @Test
    fun seriesThatStoppedHaveEnded() {
        val old = listOf("2026-03-10", "2026-04-10", "2026-05-10").map { pay("GYM", 1_480_00, it) }
        assertTrue(RecurringDetector.detect(old, today).isEmpty())
    }

    @Test
    fun splitPaymentsOnOneDayCountOnce() {
        val history = listOf("2026-07-01", "2026-08-01", "2026-09-01").flatMap { listOf(pay("SCHOOL", 5_000_00, it), pay("SCHOOL", 5_000_00, it)) }
        assertEquals(Money(10_000_00), RecurringDetector.detect(history, today).single().amount)
    }
}

class InsightRulesTest {
    private val today = LocalDate.parse("2026-10-11")

    @Test
    fun bigDayNeedsTwoAndAHalfTimesTheMedianAndAThousandRupees() {
        val daily = (1..60L).associate { today.minusDays(it) to Money(800_00) } + (today.minusDays(2) to Money(4_210_00))
        val insight = InsightRules.bigDay(InsightInputs(today, daily))!!
        assertEquals("BIG_DAY:2026-10-09", insight.key)
        assertEquals("Friday was 5.3× your usual day — ₹4,210 spent.", insight.text)

        val small = (1..60L).associate { today.minusDays(it) to Money(200_00) } + (today.minusDays(2) to Money(900_00))
        assertNull(InsightRules.bigDay(InsightInputs(today, small)))
    }

    @Test
    fun categoryTrendNeedsAQuarterAndAThousand() {
        val inputs = InsightInputs(
            today,
            daily = emptyMap(),
            categoryChanges = listOf(
                CategoryChange("Food & dining", Money(9_840_00), Money(6_200_00)),
                CategoryChange("Travel", Money(1_100_00), Money(700_00)), // +57 % but only ₹400
            ),
        )
        val insights = InsightRules.evaluate(inputs)
        assertEquals(1, insights.size)
        assertEquals("Food & dining is up 58% on last period so far (₹3,640 more).", insights.single().text)
    }

    @Test
    fun lateNightAndStreak() {
        val inputs = InsightInputs(today, daily = (1..5L).associate { today.minusDays(it) to Money(500_00) }, foodOrders = 14, lateFoodOrders = 9, safePerDay = Money(1_079_00))
        val kinds = InsightRules.evaluate(inputs).map { it.kind }
        assertTrue(InsightKind.LATE_NIGHT in kinds)
        assertTrue(InsightKind.STREAK in kinds)
    }

    @Test
    fun noStreakForDaysBeforeTrackingStarted() {
        // Installed today: the five empty days before are missing data, not a streak.
        val inputs = InsightInputs(today, daily = mapOf(today to Money(3_475_00)), safePerDay = Money(1_826_00))
        assertTrue(InsightRules.evaluate(inputs).none { it.kind == InsightKind.STREAK })
    }
}
