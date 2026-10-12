package com.impeesa.finance.core.engine.category

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategorizerTest {
    private val swiggy = CategoryInput(
        merchantKey = "SWIGGY",
        counterparty = "swiggy@icici",
        text = "Rs.486.00 debited from A/c XX4821 to VPA swiggy@icici",
        accountId = 1,
        amountPaise = 48_600,
        isDebit = true,
        dictionaryCategory = "Food & dining",
    )
    private val food = 1L
    private val groceries = 2L
    private val shopping = 3L

    @Test
    fun dictionaryIsUsedWhenNothingElseKnows() {
        val decision = Categorizer.resolve(swiggy, emptyList(), LearnedState.None)
        assertEquals(CategorySource.DICTIONARY, decision.source)
        assertEquals("Food & dining", decision.categoryName)
    }

    @Test
    fun userRuleBeatsLearningAndDictionary() {
        val rule = CategoryRule(id = 7, match = RuleMatch.MERCHANT, pattern = "Swiggy", categoryId = groceries)
        val decision = Categorizer.resolve(swiggy, listOf(rule), LearnedState.Learned(shopping))
        assertEquals(CategorySource.RULE, decision.source)
        assertEquals(groceries, decision.categoryId)
        assertEquals(7L, decision.ruleId)
        assertEquals("Your rule: Merchant is Swiggy", decision.reason)
    }

    @Test
    fun mostSpecificRuleWins() {
        val contains = CategoryRule(id = 1, match = RuleMatch.CONTAINS, pattern = "swiggy", categoryId = shopping)
        val vpa = CategoryRule(id = 2, match = RuleMatch.VPA, pattern = "SWIGGY@ICICI", categoryId = groceries)
        val account = CategoryRule(id = 3, match = RuleMatch.ACCOUNT, accountId = 1, categoryId = food)
        assertEquals(groceries, Categorizer.resolve(swiggy, listOf(contains, account, vpa), LearnedState.None).categoryId)
    }

    @Test
    fun disabledRulesAreIgnored() {
        val rule = CategoryRule(id = 1, match = RuleMatch.MERCHANT, pattern = "SWIGGY", categoryId = groceries, enabled = false)
        assertEquals(CategorySource.DICTIONARY, Categorizer.resolve(swiggy, listOf(rule), LearnedState.None).source)
    }

    @Test
    fun amountRangeRespectsBoundsAndOptionalAccount() {
        val small = CategoryRule(id = 1, match = RuleMatch.AMOUNT_RANGE, maxPaise = 500_00, categoryId = food)
        assertTrue(RuleMatcher.matches(small, swiggy))
        assertFalse(RuleMatcher.matches(small, swiggy.copy(amountPaise = 600_00)))
        assertFalse(RuleMatcher.matches(small.copy(accountId = 9), swiggy))
        assertFalse(RuleMatcher.matches(CategoryRule(id = 2, match = RuleMatch.AMOUNT_RANGE, categoryId = food), swiggy))
    }

    @Test
    fun actionsOtherThanSetCategoryAreReported() {
        val ask = CategoryRule(id = 1, match = RuleMatch.MERCHANT, pattern = "Swiggy", action = RuleAction.ASK)
        val decision = Categorizer.resolve(swiggy, listOf(ask), LearnedState.None)
        assertTrue(decision.ask)
        assertEquals(RuleAction.ASK, decision.action)
    }

    @Test
    fun learnedMappingAndAmbiguity() {
        assertEquals(groceries, Categorizer.resolve(swiggy, emptyList(), LearnedState.Learned(groceries)).categoryId)
        val ambiguous = Categorizer.resolve(swiggy, emptyList(), LearnedState.Ambiguous(listOf(shopping, groceries)))
        assertTrue(ambiguous.ask)
        assertEquals(shopping, ambiguous.categoryId)
    }

    @Test
    fun keywordsCatchUnknownMerchants() {
        val pump = CategoryInput(merchantKey = "SRI BALAJI", counterparty = "SRI BALAJI PETROL PUMP", amountPaise = 50_000, isDebit = true)
        val decision = Categorizer.resolve(pump, emptyList(), LearnedState.None)
        assertEquals(CategorySource.KEYWORD, decision.source)
        assertEquals("Travel", decision.categoryName)
        assertEquals("Message mentions “PETROL”", decision.reason)
    }

    @Test
    fun keywordsNeedWholeWords() {
        // "PUBLIC" must not look like "LIC"/"CLINIC"; "SPAR" must not look like "SPA".
        val input = CategoryInput(merchantKey = "X", counterparty = "PUBLIC SPARES", amountPaise = 100, isDebit = true)
        assertNull(Categorizer.keywordFor(input))
    }

    @Test
    fun creditsOnlyUseRulesAndLearning() {
        val credit = swiggy.copy(isDebit = false)
        assertNull(Categorizer.resolve(credit, emptyList(), LearnedState.None).source)
    }
}

class LearningPolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    @Test
    fun oneCorrectionTeachesNothing() {
        assertEquals(LearnedState.None, LearningPolicy.evaluate(listOf(Correction(2, now - day)), now))
    }

    @Test
    fun twoConsistentCorrectionsTeachAMapping() {
        val history = listOf(Correction(2, now - 10 * day), Correction(2, now - day))
        assertEquals(LearnedState.Learned(2), LearningPolicy.evaluate(history, now))
    }

    @Test
    fun twoDifferentAnswersMakeTheMerchantAmbiguous() {
        val history = listOf(Correction(3, now - 5 * day), Correction(2, now - 4 * day), Correction(2, now - day))
        assertEquals(LearnedState.Ambiguous(listOf(2L, 3L)), LearningPolicy.evaluate(history, now))
    }

    @Test
    fun oldCorrectionsExpire() {
        val history = listOf(Correction(3, now - 200 * day), Correction(2, now - 3 * day), Correction(2, now - day))
        assertEquals(LearnedState.Learned(2), LearningPolicy.evaluate(history, now))
    }
}
