package com.impeesa.finance.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.impeesa.finance.core.model.Direction
import kotlinx.coroutines.flow.Flow

data class DayFlowRow(val date: Int, val spent: Long, val refunds: Long, val income: Long)

data class CategoryTotalRow(val categoryId: Long?, val name: String?, val color: Int?, val total: Long, val count: Int)

data class MerchantTotalRow(val payeeKey: String, val label: String?, val total: Long, val count: Int)

data class OccurrenceRow(val payeeKey: String, val label: String?, val amountPaise: Long, val localDate: Int, val direction: Direction)

/** Linked refunds count against the purchase's category (§10.1); unlinked ones sit in the non-spending "Refunds". */
private const val SPENDING_OR_REFUND = """
    t.deleted_at IS NULL AND t.status = 'CONFIRMED'
    AND ((t.type = 'EXPENSE' AND t.excluded_from_spending = 0) OR t.type = 'REFUND')
    AND (c.id IS NULL OR c.counts_as_spending = 1)
"""

/** Summary queries for budgets, insights and habits (IMPLEMENTATION_PLAN.md §10.1–10.2). */
@Dao
internal interface AnalyticsDao {
    @Query(
        """
        SELECT t.local_date AS date,
               COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' AND t.excluded_from_spending = 0 AND (c.id IS NULL OR c.counts_as_spending = 1) THEN t.amount_paise ELSE 0 END), 0) AS spent,
               COALESCE(SUM(CASE WHEN t.type = 'REFUND' AND (c.id IS NULL OR c.counts_as_spending = 1) THEN t.amount_paise ELSE 0 END), 0) AS refunds,
               COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN t.amount_paise ELSE 0 END), 0) AS income
        FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.deleted_at IS NULL AND t.status = 'CONFIRMED' AND t.local_date BETWEEN :fromDate AND :toDate
        GROUP BY t.local_date
        """,
    )
    fun observeDailyFlows(fromDate: Int, toDate: Int): Flow<List<DayFlowRow>>

    @Query(
        """
        SELECT t.category_id AS categoryId, c.name AS name, c.color AS color,
               SUM(CASE WHEN t.type = 'REFUND' THEN -t.amount_paise ELSE t.amount_paise END) AS total,
               SUM(CASE WHEN t.type = 'EXPENSE' THEN 1 ELSE 0 END) AS count
        FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
        WHERE $SPENDING_OR_REFUND AND t.local_date BETWEEN :fromDate AND :toDate
        GROUP BY t.category_id
        ORDER BY total DESC
        """,
    )
    fun observeCategoryTotals(fromDate: Int, toDate: Int): Flow<List<CategoryTotalRow>>

    @Query(
        """
        SELECT t.category_id AS categoryId, c.name AS name, c.color AS color,
               SUM(CASE WHEN t.type = 'REFUND' THEN -t.amount_paise ELSE t.amount_paise END) AS total,
               SUM(CASE WHEN t.type = 'EXPENSE' THEN 1 ELSE 0 END) AS count
        FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
        WHERE $SPENDING_OR_REFUND AND t.local_date BETWEEN :fromDate AND :toDate
        GROUP BY t.category_id
        """,
    )
    suspend fun categoryTotals(fromDate: Int, toDate: Int): List<CategoryTotalRow>

    @Query(
        """
        SELECT COALESCE(t.merchant_key, t.counterparty) AS payeeKey, MAX(t.counterparty) AS label,
               SUM(t.amount_paise) AS total, COUNT(*) AS count
        FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.deleted_at IS NULL AND t.status = 'CONFIRMED' AND t.type = 'EXPENSE' AND t.excluded_from_spending = 0
          AND (c.id IS NULL OR c.counts_as_spending = 1)
          AND COALESCE(t.merchant_key, t.counterparty) IS NOT NULL
          AND t.local_date BETWEEN :fromDate AND :toDate
        GROUP BY COALESCE(t.merchant_key, t.counterparty)
        ORDER BY total DESC
        LIMIT :limit
        """,
    )
    fun observeTopMerchants(fromDate: Int, toDate: Int, limit: Int): Flow<List<MerchantTotalRow>>

    /** Payments and income by payee, for recurring detection (§9.6). Transfers are not recurring spending. */
    @Query(
        """
        SELECT COALESCE(merchant_key, counterparty) AS payeeKey, counterparty AS label, amount_paise AS amountPaise,
               local_date AS localDate, direction
        FROM transactions
        WHERE deleted_at IS NULL AND status = 'CONFIRMED' AND type IN ('EXPENSE', 'INCOME')
          AND COALESCE(merchant_key, counterparty) IS NOT NULL AND local_date >= :fromDate
        """,
    )
    fun observeOccurrences(fromDate: Int): Flow<List<OccurrenceRow>>

    /** When food orders happened, for the late-night pattern. */
    @Query(
        """
        SELECT t.occurred_at FROM transactions t JOIN categories c ON c.id = t.category_id
        WHERE t.deleted_at IS NULL AND t.status = 'CONFIRMED' AND t.type = 'EXPENSE'
          AND c.name = 'Food & dining' AND t.local_date BETWEEN :fromDate AND :toDate
        """,
    )
    fun observeFoodOrderTimes(fromDate: Int, toDate: Int): Flow<List<Long>>

    // ------------------------------------------------------------ budgets

    @Insert
    suspend fun insertBudget(budget: BudgetEntity): Long

    @Update
    suspend fun updateBudget(budget: BudgetEntity)

    @Query("SELECT * FROM budgets WHERE id = :id")
    suspend fun budget(id: Long): BudgetEntity?

    @Query("SELECT * FROM budgets WHERE archived = 0 AND ((:categoryId IS NULL AND category_id IS NULL) OR category_id = :categoryId) LIMIT 1")
    suspend fun budgetFor(categoryId: Long?): BudgetEntity?

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteBudget(id: Long)

    @Query("SELECT * FROM budgets WHERE archived = 0 ORDER BY category_id IS NOT NULL, created_at")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    // ------------------------------------------------------------ recurring

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setRecurringPref(pref: RecurringPrefEntity)

    @Query("DELETE FROM recurring_prefs WHERE `key` = :key")
    suspend fun clearRecurringPref(key: String)

    @Query("SELECT * FROM recurring_prefs")
    fun observeRecurringPrefs(): Flow<List<RecurringPrefEntity>>
}
