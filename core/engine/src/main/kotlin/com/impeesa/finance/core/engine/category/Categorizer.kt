package com.impeesa.finance.core.engine.category

import com.impeesa.finance.core.engine.merchant.MerchantNormalizer

/** Why a transaction has its category (IMPLEMENTATION_PLAN.md §7.1). Stored as text on the row. */
enum class CategorySource { USER, RULE, LEARNED, DICTIONARY, KEYWORD }

/** What a rule looks at, most specific first. */
enum class RuleMatch { VPA, MERCHANT, CONTAINS, ACCOUNT, AMOUNT_RANGE }

enum class RuleAction { SET_CATEGORY, MARK_TRANSFER, EXCLUDE, ASK }

/** A user rule: "When merchant is Swiggy, then Groceries". */
data class CategoryRule(
    val id: Long,
    val match: RuleMatch,
    /** VPA, merchant key or text to look for; unused for ACCOUNT and AMOUNT_RANGE. */
    val pattern: String = "",
    /** How the rule reads to the user, e.g. "Swiggy". */
    val label: String = pattern,
    val accountId: Long? = null,
    val minPaise: Long? = null,
    val maxPaise: Long? = null,
    val action: RuleAction = RuleAction.SET_CATEGORY,
    val categoryId: Long? = null,
    val enabled: Boolean = true,
    val priority: Int = 0,
)

/** What the categoriser can see about one transaction. */
data class CategoryInput(
    val merchantKey: String?,
    /** Raw payee: a VPA, a name or a merchant string. */
    val counterparty: String?,
    /** The message text, when captured. */
    val text: String = "",
    val accountId: Long? = null,
    val amountPaise: Long,
    val isDebit: Boolean,
    /** Category name from the bundled merchant dictionary. */
    val dictionaryCategory: String? = null,
)

/** What the user has taught the app about one merchant (see [LearningPolicy]). */
sealed interface LearnedState {
    data object None : LearnedState
    data class Learned(val categoryId: Long) : LearnedState

    /** Sorted different ways recently: ask, offering these first (most likely first). */
    data class Ambiguous(val ranked: List<Long>) : LearnedState
}

/** The categoriser's answer. A category is given as an id (rules, learning) or a taxonomy name (dictionary, keywords). */
data class CategoryDecision(
    val source: CategorySource?,
    val categoryId: Long? = null,
    val categoryName: String? = null,
    val action: RuleAction = RuleAction.SET_CATEGORY,
    val ruleId: Long? = null,
    /** Short human explanation for the Detail screen. */
    val reason: String? = null,
    /** Ask the user (an ASK rule or an ambiguous merchant); the category, if any, is only a suggestion. */
    val ask: Boolean = false,
) {
    companion object {
        val NONE = CategoryDecision(source = null)
    }
}

object RuleMatcher {
    fun matches(rule: CategoryRule, input: CategoryInput): Boolean {
        if (!rule.enabled) return false
        return when (rule.match) {
            RuleMatch.VPA -> input.counterparty?.trim()?.equals(rule.pattern.trim(), ignoreCase = true) == true
            RuleMatch.MERCHANT -> input.merchantKey != null && input.merchantKey == MerchantNormalizer.key(rule.pattern)
            RuleMatch.CONTAINS -> rule.pattern.trim().length >= 2 &&
                (listOfNotNull(input.counterparty, input.text).any { it.contains(rule.pattern.trim(), ignoreCase = true) })
            RuleMatch.ACCOUNT -> rule.accountId != null && rule.accountId == input.accountId
            RuleMatch.AMOUNT_RANGE -> (rule.minPaise != null || rule.maxPaise != null) &&
                (rule.minPaise == null || input.amountPaise >= rule.minPaise) &&
                (rule.maxPaise == null || input.amountPaise <= rule.maxPaise) &&
                (rule.accountId == null || rule.accountId == input.accountId)
        }
    }

    /** Most specific match type first, then priority, then the newest rule. */
    val order: Comparator<CategoryRule> = compareBy<CategoryRule> { it.match.ordinal }
        .thenByDescending { it.priority }
        .thenByDescending { it.id }

    fun describe(rule: CategoryRule): String = when (rule.match) {
        RuleMatch.VPA -> "UPI ID is ${rule.label}"
        RuleMatch.MERCHANT -> "Merchant is ${rule.label}"
        RuleMatch.CONTAINS -> "Message contains “${rule.pattern.trim()}”"
        RuleMatch.ACCOUNT -> "Paid with ${rule.label}"
        RuleMatch.AMOUNT_RANGE -> when {
            rule.minPaise != null && rule.maxPaise != null -> "Amount ₹${rule.minPaise / 100}–₹${rule.maxPaise / 100}"
            rule.minPaise != null -> "Amount ₹${rule.minPaise / 100} or more"
            else -> "Amount up to ₹${(rule.maxPaise ?: 0) / 100}"
        }
    }
}

/**
 * Picks a category in the order of IMPLEMENTATION_PLAN.md §7.1: user rules, learned mappings,
 * the merchant dictionary, keyword heuristics. A category the user set themselves never reaches here.
 * Pure: everything it needs is passed in.
 */
object Categorizer {
    /** [payeeOnly] keywords are too common in bank boilerplate ("pre-approved loan") to trust in the message text. */
    private data class Keyword(val pattern: Regex, val category: String, val payeeOnly: Boolean = false)

    private fun words(vararg words: String) = Regex("\\b(" + words.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    private val keywords = listOf(
        Keyword(words("PETROL", "FUEL", "HPCL", "IOCL", "BPCL", "INDIAN OIL", "HP PAY", "FILLING STATION"), "Travel"),
        Keyword(words("PHARMA", "PHARMACY", "MEDICAL", "MEDICALS", "HOSPITAL", "CLINIC", "CHEMIST", "DIAGNOSTICS?", "LABS?"), "Health"),
        Keyword(words("RESTAURANT", "CAFE", "DHABA", "BAKERY", "BAKERS", "SWEETS", "BIRYANI", "KITCHEN", "FOODS"), "Food & dining"),
        Keyword(words("ELECTRICITY", "BESCOM", "MSEDCL", "TATA POWER", "ADANI ELECTRICITY", "TNEB", "KSEB", "BROADBAND", "RECHARGE", "POSTPAID", "DTH", "GAS BILL", "WATER BILL"), "Bills & utilities"),
        Keyword(words("SUPERMARKET", "KIRANA", "PROVISION", "PROVISIONS", "VEGETABLES?", "FRUITS?", "DAIRY", "MART"), "Groceries"),
        Keyword(words("SCHOOL", "COLLEGE", "UNIVERSITY", "TUITION", "ACADEMY"), "Education"),
        Keyword(words("SALON", "SPA", "PARLOUR", "PARLOR", "BARBER"), "Personal care"),
        Keyword(words("RENT", "SOCIETY MAINTENANCE", "MAINTENANCE CHARGES"), "Rent & housing", payeeOnly = true),
        Keyword(words("EMI", "LOAN"), "EMI & loans", payeeOnly = true),
        Keyword(words("PARKING", "FASTAG", "TOLL", "METRO", "IRCTC", "RAILWAY"), "Travel"),
    )

    fun resolve(input: CategoryInput, rules: List<CategoryRule>, learned: LearnedState): CategoryDecision {
        rules.filter { RuleMatcher.matches(it, input) }.minWithOrNull(RuleMatcher.order)?.let { rule ->
            return CategoryDecision(
                source = CategorySource.RULE,
                categoryId = rule.categoryId,
                action = rule.action,
                ruleId = rule.id,
                reason = "Your rule: " + RuleMatcher.describe(rule),
                ask = rule.action == RuleAction.ASK,
            )
        }
        when (learned) {
            is LearnedState.Learned -> return CategoryDecision(
                source = CategorySource.LEARNED,
                categoryId = learned.categoryId,
                reason = "Learned from your corrections",
            )
            is LearnedState.Ambiguous -> return CategoryDecision(
                source = CategorySource.LEARNED,
                categoryId = learned.ranked.firstOrNull(),
                reason = "You've sorted this merchant in different ways",
                ask = true,
            )
            LearnedState.None -> Unit
        }
        // The dictionary and keywords know spending categories only.
        if (!input.isDebit) return CategoryDecision.NONE
        input.dictionaryCategory?.let {
            return CategoryDecision(source = CategorySource.DICTIONARY, categoryName = it, reason = "Known merchant")
        }
        keywordFor(input)?.let { (category, word) ->
            return CategoryDecision(source = CategorySource.KEYWORD, categoryName = category, reason = "Message mentions “${word.uppercase()}”")
        }
        return CategoryDecision.NONE
    }

    /** Payee text is checked before the message, so "at HP PETROL PUMP" wins over bank boilerplate. */
    internal fun keywordFor(input: CategoryInput): Pair<String, String>? {
        input.counterparty?.let { payee ->
            for (keyword in keywords) keyword.pattern.find(payee)?.let { return keyword.category to it.value }
        }
        for (keyword in keywords) {
            if (!keyword.payeeOnly) keyword.pattern.find(input.text)?.let { return keyword.category to it.value }
        }
        return null
    }
}

/** One time the user moved a merchant's transaction to a category. */
/** [order] breaks ties between corrections saved in the same millisecond: higher is later. */
data class Correction(val categoryId: Long, val atMillis: Long, val order: Long = 0)

/**
 * Turns a merchant's correction history into what to do next time (IMPLEMENTATION_PLAN.md §7.4):
 * two consistent corrections in 90 days teach a mapping; two different answers make the merchant ambiguous.
 */
object LearningPolicy {
    const val WINDOW_DAYS = 90L
    const val TO_LEARN = 2
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    fun evaluate(corrections: List<Correction>, nowMillis: Long): LearnedState {
        val recent = corrections.filter { nowMillis - it.atMillis <= WINDOW_DAYS * DAY_MILLIS }
        if (recent.isEmpty()) return LearnedState.None
        val byCategory = recent.groupBy { it.categoryId }
        if (byCategory.size >= 2) {
            val ranked = byCategory.entries
                .sortedWith(compareByDescending<Map.Entry<Long, List<Correction>>> { it.value.size }.thenByDescending { e -> e.value.maxOf { it.atMillis } }.thenByDescending { e -> e.value.maxOf { it.order } })
                .map { it.key }
            return LearnedState.Ambiguous(ranked)
        }
        return if (recent.size >= TO_LEARN) LearnedState.Learned(recent.first().categoryId) else LearnedState.None
    }
}
