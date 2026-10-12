package com.impeesa.finance.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.impeesa.finance.core.model.Direction
import com.impeesa.finance.core.model.TxnType
import kotlinx.coroutines.flow.Flow

/** A rule with what the list shows next to it. */
data class RuleRow(
    val id: Long,
    val matchType: String,
    val pattern: String,
    val label: String,
    val accountId: Long?,
    val minPaise: Long?,
    val maxPaise: Long?,
    val action: String,
    val categoryId: Long?,
    val categoryName: String?,
    val categoryColor: Int?,
    val source: String,
    val enabled: Boolean,
    val matchCount: Int,
)

/** What re-categorising an earlier transaction needs to see and to undo. */
data class CategorizableRow(
    val id: Long,
    val merchantKey: String?,
    val counterparty: String?,
    val body: String?,
    val accountId: Long?,
    val amountPaise: Long,
    val direction: Direction,
    val type: TxnType,
    val categoryId: Long?,
    val categorySource: String?,
    val categoryReason: String?,
    val excludedFromSpending: Boolean,
)

/** Rows the user has not set a category on themselves: the only ones learning and rules may touch. */
private const val UNLOCKED = """
    t.deleted_at IS NULL AND t.status != 'IGNORED' AND t.type IN ('EXPENSE', 'INCOME')
    AND (t.category_source IS NULL OR t.category_source != 'USER') AND t.user_edited_fields = 0
"""

private const val CATEGORIZABLE_COLUMNS = """
    t.id, t.merchant_key AS merchantKey, t.counterparty, r.body AS body, t.account_id AS accountId,
    t.amount_paise AS amountPaise, t.direction, t.type, t.category_id AS categoryId,
    t.category_source AS categorySource, t.category_reason AS categoryReason,
    t.excluded_from_spending AS excludedFromSpending
    FROM transactions t LEFT JOIN raw_messages r ON r.id = t.raw_message_id
"""

@Dao
internal interface CategorizationDao {
    // ------------------------------------------------------------ rules

    @Insert
    suspend fun insertRule(rule: RuleEntity): Long

    @Update
    suspend fun updateRule(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun rule(id: Long): RuleEntity?

    @Query("SELECT * FROM rules WHERE enabled = 1")
    suspend fun enabledRules(): List<RuleEntity>

    @Query("SELECT * FROM rules WHERE match_type = :matchType AND pattern = :pattern COLLATE NOCASE")
    suspend fun rulesFor(matchType: String, pattern: String): List<RuleEntity>

    @Query("UPDATE rules SET enabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE rules SET match_count = match_count + :by WHERE id = :id")
    suspend fun bumpMatches(id: Long, by: Int = 1)

    @Query(
        """
        SELECT r.id, r.match_type AS matchType, r.pattern, r.label, r.account_id AS accountId,
               r.min_paise AS minPaise, r.max_paise AS maxPaise, r.action, r.category_id AS categoryId,
               c.name AS categoryName, c.color AS categoryColor, r.source, r.enabled, r.match_count AS matchCount
        FROM rules r LEFT JOIN categories c ON c.id = r.category_id
        ORDER BY r.enabled DESC, r.created_at DESC
        """,
    )
    fun observeRules(): Flow<List<RuleRow>>

    // ------------------------------------------------------------ corrections

    @Insert
    suspend fun insertCorrection(correction: CategoryCorrectionEntity): Long

    @Query("SELECT * FROM category_corrections WHERE merchant_key = :merchantKey AND at >= :since")
    suspend fun corrections(merchantKey: String, since: Long): List<CategoryCorrectionEntity>

    @Query("SELECT * FROM category_corrections WHERE at >= :since ORDER BY at DESC")
    fun observeCorrections(since: Long): Flow<List<CategoryCorrectionEntity>>

    @Query("DELETE FROM category_corrections WHERE merchant_key = :merchantKey")
    suspend fun forget(merchantKey: String)

    @Query("DELETE FROM category_corrections WHERE id = :id")
    suspend fun deleteCorrection(id: Long)

    // ------------------------------------------------------------ transactions

    @Query("SELECT counterparty FROM transactions WHERE merchant_key = :merchantKey AND counterparty IS NOT NULL ORDER BY occurred_at DESC LIMIT 1")
    suspend fun merchantLabel(merchantKey: String): String?

    @Query("SELECT COUNT(*) FROM transactions t WHERE $UNLOCKED AND t.merchant_key = :merchantKey AND t.id != :excludeId AND t.direction = :direction AND (t.category_id IS NULL OR t.category_id != :categoryId)")
    suspend fun countEarlier(merchantKey: String, excludeId: Long, direction: String, categoryId: Long): Int

    @Query("SELECT $CATEGORIZABLE_COLUMNS WHERE $UNLOCKED AND t.merchant_key = :merchantKey AND t.id != :excludeId AND t.direction = :direction AND (t.category_id IS NULL OR t.category_id != :categoryId)")
    suspend fun earlier(merchantKey: String, excludeId: Long, direction: String, categoryId: Long): List<CategorizableRow>

    /** Every row a new rule could apply to; the rule itself is matched in Kotlin, with the same code as capture. */
    @Query("SELECT $CATEGORIZABLE_COLUMNS WHERE $UNLOCKED AND t.transfer_group_id IS NULL")
    suspend fun unlockedRows(): List<CategorizableRow>

    @Query("UPDATE transactions SET category_id = :categoryId, category_source = :source, category_reason = :reason, updated_at = :at WHERE id IN (:ids)")
    suspend fun setCategories(ids: List<Long>, categoryId: Long?, source: String?, reason: String?, at: Long)

    @Query("UPDATE transactions SET excluded_from_spending = :excluded, updated_at = :at WHERE id IN (:ids)")
    suspend fun setExcluded(ids: List<Long>, excluded: Boolean, at: Long)
}
