package com.impeesa.finance.core.data

import androidx.room.withTransaction
import com.impeesa.finance.core.data.db.CategorizableRow
import com.impeesa.finance.core.data.db.CategoryCorrectionEntity
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.data.db.RuleEntity
import com.impeesa.finance.core.data.db.RuleRow
import com.impeesa.finance.core.engine.category.CategoryDecision
import com.impeesa.finance.core.engine.category.CategoryInput
import com.impeesa.finance.core.engine.category.CategoryRule
import com.impeesa.finance.core.engine.category.Categorizer
import com.impeesa.finance.core.engine.category.Correction
import com.impeesa.finance.core.engine.category.LearnedState
import com.impeesa.finance.core.engine.category.LearningPolicy
import com.impeesa.finance.core.engine.category.RuleAction
import com.impeesa.finance.core.engine.category.RuleMatch
import com.impeesa.finance.core.engine.category.RuleMatcher
import com.impeesa.finance.core.engine.merchant.MerchantNormalizer
import com.impeesa.finance.core.model.Direction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Duration

/** A rule as the Categories & rules screen shows it. */
data class Rule(
    val id: Long,
    val match: RuleMatch,
    val pattern: String,
    val label: String,
    val accountId: Long?,
    val minPaise: Long?,
    val maxPaise: Long?,
    val action: RuleAction,
    val categoryId: Long?,
    val categoryName: String?,
    val categoryColor: Int?,
    /** Made from an "Always sort" switch rather than in the editor. */
    val fromEdit: Boolean,
    val enabled: Boolean,
    val matchCount: Int,
) {
    val condition: String get() = RuleMatcher.describe(toEngine())

    val outcome: String
        get() = when (action) {
            RuleAction.SET_CATEGORY -> categoryName ?: "Category removed"
            RuleAction.MARK_TRANSFER -> "Mark as transfer"
            RuleAction.EXCLUDE -> "Leave out of spending"
            RuleAction.ASK -> "Ask me each time"
        }

    internal fun toEngine() = CategoryRule(id, match, pattern, label, accountId, minPaise, maxPaise, action, categoryId, enabled)
}

/** What the rule editor saves. */
data class RuleDraft(
    val id: Long = 0,
    val match: RuleMatch,
    val pattern: String = "",
    val label: String = pattern,
    val accountId: Long? = null,
    val minPaise: Long? = null,
    val maxPaise: Long? = null,
    val action: RuleAction = RuleAction.SET_CATEGORY,
    val categoryId: Long? = null,
) {
    val isValid: Boolean
        get() = when (match) {
            RuleMatch.VPA -> MerchantNormalizer.isVpa(pattern)
            RuleMatch.MERCHANT -> MerchantNormalizer.key(pattern).isNotEmpty()
            RuleMatch.CONTAINS -> pattern.trim().length >= 2
            RuleMatch.ACCOUNT -> accountId != null
            RuleMatch.AMOUNT_RANGE -> (minPaise != null || maxPaise != null) && (minPaise == null || maxPaise == null || minPaise <= maxPaise)
        } && (action != RuleAction.SET_CATEGORY || categoryId != null)

    internal fun toEngine() = CategoryRule(id, match, pattern.trim(), label.trim().ifEmpty { pattern.trim() }, accountId, minPaise, maxPaise, action, categoryId)
}

/** A merchant whose category the app has learned from corrections, or that the user sorts different ways. */
data class LearnedMerchant(
    val merchantKey: String,
    val label: String,
    /** The learned category, or the candidates for an ambiguous merchant (most likely first). */
    val categoryIds: List<Long>,
    val ambiguous: Boolean,
    val corrections: Int,
)

/** How one row's category looked before a bulk change, for undo. */
data class CategorySnapshot(
    val transactionId: Long,
    val categoryId: Long?,
    val source: String?,
    val reason: String?,
    val excluded: Boolean,
)

/** The result of the user moving one transaction to another category. */
data class CategoryChange(
    val transactionId: Long,
    val categoryId: Long,
    val direction: Direction,
    /** Null for manual entries: nothing to learn from. */
    val merchantKey: String?,
    val merchantLabel: String?,
    /** Earlier rows from the same merchant that "Apply to earlier" would update. */
    val earlierCount: Int,
    internal val previous: CategorySnapshot,
    internal val correctionId: Long?,
)

/**
 * Category resolution shared by capture, review and edits (IMPLEMENTATION_PLAN.md §7.1, §7.4).
 * Rules are re-read per message so edits apply to the very next capture.
 */
internal class CategoryResolver(private val db: ImpeesaDatabase, private val clock: Clock) {
    private val dao = db.categorizationDao()

    suspend fun rules(): List<CategoryRule> = dao.enabledRules().map { it.toEngine() }

    suspend fun learned(merchantKey: String?): LearnedState {
        if (merchantKey.isNullOrBlank()) return LearnedState.None
        val history = dao.corrections(merchantKey, since()).map { Correction(it.categoryId, it.at) }
        return LearningPolicy.evaluate(history, clock.millis())
    }

    suspend fun decide(input: CategoryInput, rules: List<CategoryRule>): CategoryDecision =
        Categorizer.resolve(input, rules, learned(input.merchantKey))

    /** Category id for a decision: rules and learning give ids, the dictionary and keywords give names. */
    suspend fun categoryIdFor(decision: CategoryDecision): Long? =
        decision.categoryId ?: decision.categoryName?.let { db.categoryDao().idByName(it) }

    suspend fun recordMatch(ruleId: Long?) {
        if (ruleId != null) dao.bumpMatches(ruleId)
    }

    /** Logs a correction when the user picked something other than what the row had. */
    suspend fun recordCorrection(merchantKey: String?, fromCategoryId: Long?, toCategoryId: Long?, transactionId: Long): Long? {
        if (merchantKey.isNullOrBlank() || toCategoryId == null || toCategoryId == fromCategoryId) return null
        return dao.insertCorrection(CategoryCorrectionEntity(merchantKey = merchantKey, categoryId = toCategoryId, transactionId = transactionId, at = clock.millis()))
    }

    fun since(): Long = clock.millis() - Duration.ofDays(LearningPolicy.WINDOW_DAYS).toMillis()
}

internal fun RuleEntity.toEngine() = CategoryRule(
    id = id,
    match = RuleMatch.valueOf(matchType),
    pattern = pattern,
    label = label,
    accountId = accountId,
    minPaise = minPaise,
    maxPaise = maxPaise,
    action = RuleAction.valueOf(action),
    categoryId = categoryId,
    enabled = enabled,
    priority = priority,
)

class CategorizationRepository internal constructor(
    private val db: ImpeesaDatabase,
    private val clock: Clock,
) {
    private val dao = db.categorizationDao()
    private val txns = db.transactionDao()
    private val resolver = CategoryResolver(db, clock)

    // ------------------------------------------------------------ rules

    fun observeRules(): Flow<List<Rule>> = dao.observeRules().map { rows -> rows.map { it.toModel() } }

    suspend fun rule(id: Long): RuleDraft? = dao.rule(id)?.let {
        RuleDraft(it.id, RuleMatch.valueOf(it.matchType), it.pattern, it.label, it.accountId, it.minPaise, it.maxPaise, RuleAction.valueOf(it.action), it.categoryId)
    }

    /** Saves a rule from the editor. It applies to the next capture straight away. */
    suspend fun saveRule(draft: RuleDraft): Long {
        require(draft.isValid) { "Rule is incomplete" }
        val rule = draft.toEngine()
        val pattern = if (rule.match == RuleMatch.VPA) rule.pattern.lowercase() else rule.pattern
        return db.withTransaction {
            val existing = if (draft.id != 0L) dao.rule(draft.id) else null
            val entity = RuleEntity(
                id = existing?.id ?: 0,
                matchType = rule.match.name,
                pattern = pattern,
                label = rule.label,
                accountId = rule.accountId,
                minPaise = rule.minPaise,
                maxPaise = rule.maxPaise,
                action = rule.action.name,
                categoryId = rule.categoryId.takeIf { rule.action == RuleAction.SET_CATEGORY },
                source = existing?.source ?: SOURCE_MANUAL,
                enabled = existing?.enabled ?: true,
                matchCount = existing?.matchCount ?: 0,
                createdAt = existing?.createdAt ?: clock.millis(),
            )
            if (existing != null) dao.updateRule(entity).let { entity.id } else dao.insertRule(entity)
        }
    }

    /** Deletes a rule; returns it for undo. */
    suspend fun deleteRule(id: Long): RuleEntityRef? = db.withTransaction {
        val rule = dao.rule(id) ?: return@withTransaction null
        dao.deleteRule(id)
        RuleEntityRef(rule)
    }

    suspend fun restoreRule(ref: RuleEntityRef) {
        dao.insertRule(ref.entity)
    }

    suspend fun setRuleEnabled(id: Long, enabled: Boolean) = dao.setRuleEnabled(id, enabled)

    /** How many earlier transactions [draft] would change. */
    suspend fun countHistoryMatches(draft: RuleDraft): Int {
        if (!draft.isValid || draft.action == RuleAction.ASK) return 0
        val rule = draft.toEngine()
        return dao.unlockedRows().count { matchesHistory(rule, it) && changes(rule, it) }
    }

    /** Applies a saved rule to earlier transactions the user has not set by hand. Returns snapshots for undo. */
    suspend fun applyRuleToHistory(ruleId: Long): List<CategorySnapshot> = db.withTransaction {
        val rule = dao.rule(ruleId)?.toEngine() ?: return@withTransaction emptyList()
        if (rule.action == RuleAction.ASK) return@withTransaction emptyList()
        val rows = dao.unlockedRows().filter { matchesHistory(rule, it) && changes(rule, it) }
        if (rows.isEmpty()) return@withTransaction emptyList()
        val ids = rows.map { it.id }
        val now = clock.millis()
        val reason = "Your rule: " + RuleMatcher.describe(rule)
        when (rule.action) {
            RuleAction.SET_CATEGORY -> dao.setCategories(ids, rule.categoryId, SOURCE_RULE, reason, now)
            RuleAction.EXCLUDE -> dao.setExcluded(ids, true, now)
            RuleAction.MARK_TRANSFER -> {
                dao.setCategories(ids, db.categoryDao().idByName("Transfers"), SOURCE_RULE, reason, now)
                dao.setExcluded(ids, true, now)
            }
            RuleAction.ASK -> Unit
        }
        dao.bumpMatches(ruleId, rows.size)
        rows.map { it.snapshot() }
    }

    /** Undo for bulk changes. */
    suspend fun restore(snapshots: List<CategorySnapshot>) = db.withTransaction {
        val now = clock.millis()
        snapshots.forEach { s ->
            dao.setCategories(listOf(s.transactionId), s.categoryId, s.source, s.reason, now)
            dao.setExcluded(listOf(s.transactionId), s.excluded, now)
        }
    }

    // ------------------------------------------------------------ corrections

    /**
     * The user moved one transaction to [categoryId]: lock it, and remember the choice for its merchant
     * so the second consistent correction teaches the app (§7.4).
     */
    suspend fun changeCategory(transactionId: Long, categoryId: Long): CategoryChange? = db.withTransaction {
        val row = txns.getById(transactionId) ?: return@withTransaction null
        val previous = CategorySnapshot(row.id, row.categoryId, row.categorySource, row.categoryReason, row.excludedFromSpending)
        txns.setCategory(row.id, categoryId, clock.millis())
        val correctionId = resolver.recordCorrection(row.merchantKey, row.categoryId, categoryId, row.id)
        val key = row.merchantKey
        CategoryChange(
            transactionId = row.id,
            categoryId = categoryId,
            direction = row.direction,
            merchantKey = key,
            merchantLabel = row.counterparty ?: key?.let { dao.merchantLabel(it) },
            earlierCount = key?.let { dao.countEarlier(it, row.id, row.direction.name, categoryId) } ?: 0,
            previous = previous,
            correctionId = correctionId,
        )
    }

    /** Undo for [changeCategory]. */
    suspend fun undoChange(change: CategoryChange) = db.withTransaction {
        val p = change.previous
        dao.setCategories(listOf(p.transactionId), p.categoryId, p.source, p.reason, clock.millis())
        change.correctionId?.let { dao.deleteCorrection(it) }
    }

    /** "Always sort Swiggy as Groceries": a merchant rule, replacing earlier ones for the same merchant. */
    suspend fun setAlwaysSort(change: CategoryChange, on: Boolean) = db.withTransaction {
        val key = change.merchantKey ?: return@withTransaction
        val existing = dao.rulesFor(RuleMatch.MERCHANT.name, key).filter { it.source == SOURCE_EDIT }
        existing.forEach { dao.deleteRule(it.id) }
        if (on) {
            dao.insertRule(
                RuleEntity(
                    matchType = RuleMatch.MERCHANT.name,
                    pattern = key,
                    label = change.merchantLabel ?: key,
                    action = RuleAction.SET_CATEGORY.name,
                    categoryId = change.categoryId,
                    source = SOURCE_EDIT,
                    createdAt = clock.millis(),
                ),
            )
        }
    }

    /** "Apply to 13 earlier Swiggy transactions". Only rows the user has not set by hand. */
    suspend fun applyToEarlier(change: CategoryChange): List<CategorySnapshot> = db.withTransaction {
        val key = change.merchantKey ?: return@withTransaction emptyList()
        val rows = dao.earlier(key, change.transactionId, change.direction.name, change.categoryId)
        if (rows.isNotEmpty()) dao.setCategories(rows.map { it.id }, change.categoryId, SOURCE_LEARNED, "Applied from your correction", clock.millis())
        rows.map { it.snapshot() }
    }

    // ------------------------------------------------------------ learning

    /** Merchants the app has learned, or now asks about. Inspectable and forgettable (§7.4). */
    fun observeLearned(): Flow<List<LearnedMerchant>> = dao.observeCorrections(resolver.since()).map { rows ->
        val now = clock.millis()
        rows.groupBy { it.merchantKey }.mapNotNull { (key, list) ->
            val state = LearningPolicy.evaluate(list.map { Correction(it.categoryId, it.at) }, now)
            val ids = when (state) {
                is LearnedState.Learned -> listOf(state.categoryId)
                is LearnedState.Ambiguous -> state.ranked
                LearnedState.None -> return@mapNotNull null
            }
            LearnedMerchant(key, dao.merchantLabel(key) ?: key, ids, state is LearnedState.Ambiguous, list.size)
        }
    }

    suspend fun forget(merchantKey: String) = dao.forget(merchantKey)

    // ------------------------------------------------------------ helpers

    private fun matchesHistory(rule: CategoryRule, row: CategorizableRow): Boolean {
        val input = CategoryInput(
            merchantKey = row.merchantKey,
            counterparty = row.counterparty,
            text = row.body.orEmpty(),
            accountId = row.accountId,
            amountPaise = row.amountPaise,
            isDebit = row.direction == Direction.DEBIT,
        )
        // Stored rows keep the clean merchant name, so a UPI ID is looked for in the message too.
        return RuleMatcher.matches(rule, input) ||
            (rule.match == RuleMatch.VPA && row.body?.contains(rule.pattern.trim(), ignoreCase = true) == true)
    }

    private fun changes(rule: CategoryRule, row: CategorizableRow): Boolean = when (rule.action) {
        RuleAction.SET_CATEGORY -> row.categoryId != rule.categoryId
        RuleAction.EXCLUDE, RuleAction.MARK_TRANSFER -> !row.excludedFromSpending
        RuleAction.ASK -> false
    }

    private fun CategorizableRow.snapshot() = CategorySnapshot(id, categoryId, categorySource, categoryReason, excludedFromSpending)

    private fun RuleRow.toModel() = Rule(
        id = id,
        match = RuleMatch.valueOf(matchType),
        pattern = pattern,
        label = label,
        accountId = accountId,
        minPaise = minPaise,
        maxPaise = maxPaise,
        action = RuleAction.valueOf(action),
        categoryId = categoryId,
        categoryName = categoryName,
        categoryColor = categoryColor,
        fromEdit = source == SOURCE_EDIT,
        enabled = enabled,
        matchCount = matchCount,
    )

    internal companion object {
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_EDIT = "EDIT"
        const val SOURCE_RULE = "RULE"
        const val SOURCE_LEARNED = "LEARNED"
    }
}

/** A deleted rule, kept opaque so the app can hand it back for undo. */
class RuleEntityRef internal constructor(internal val entity: RuleEntity)
