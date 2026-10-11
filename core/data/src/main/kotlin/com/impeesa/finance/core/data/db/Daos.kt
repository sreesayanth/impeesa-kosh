package com.impeesa.finance.core.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.CategoryKind
import com.impeesa.finance.core.model.Direction
import com.impeesa.finance.core.model.TxnSource
import com.impeesa.finance.core.model.TxnType
import kotlinx.coroutines.flow.Flow

/** Account plus its live balance: opening balance + credits − debits. */
data class AccountBalanceRow(
    val id: Long,
    val type: AccountType,
    val name: String,
    val mask: String?,
    val color: Int,
    val creditLimitPaise: Long?,
    val includeInNet: Boolean,
    val balancePaise: Long,
)

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Query("SELECT COUNT(*) FROM accounts WHERE type = 'CASH'")
    suspend fun cashCount(): Int

    @Query("UPDATE accounts SET archived = :archived, updated_at = :now WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, now: Long)

    @Query(
        """
        SELECT a.id, a.type, a.name, a.mask, a.color,
               a.credit_limit_paise AS creditLimitPaise, a.include_in_net AS includeInNet,
               a.opening_balance_paise + COALESCE((
                   SELECT SUM(CASE WHEN t.direction = 'CREDIT' THEN t.amount_paise ELSE -t.amount_paise END)
                   FROM transactions t
                   WHERE t.account_id = a.id AND t.deleted_at IS NULL AND t.status = 'CONFIRMED'
               ), 0) AS balancePaise
        FROM accounts a
        WHERE a.archived = 0
        ORDER BY CASE a.type WHEN 'BANK' THEN 0 WHEN 'CARD' THEN 1 WHEN 'WALLET' THEN 2 ELSE 3 END, a.name
        """,
    )
    fun observeActiveWithBalance(): Flow<List<AccountBalanceRow>>
}

@Dao
interface CategoryDao {
    @Insert
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY sort_order")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE kind = :kind AND archived = 0 ORDER BY sort_order")
    fun observeByKind(kind: CategoryKind): Flow<List<CategoryEntity>>

    @Query("SELECT id FROM categories WHERE name = :name LIMIT 1")
    suspend fun idByName(name: String): Long?
}

/** A transaction joined with what a list row needs to show it. */
data class TransactionRow(
    val id: Long,
    val amountPaise: Long,
    val direction: Direction,
    val type: TxnType,
    val counterparty: String?,
    val note: String?,
    val occurredAt: Long,
    val localDate: Int,
    val source: TxnSource,
    val categoryName: String?,
    val categoryColor: Int?,
    val accountName: String?,
    val transferGroupId: String?,
)

data class DayTotal(val date: Int, val total: Long)

private const val ROW_COLUMNS = """
    t.id, t.amount_paise AS amountPaise, t.direction, t.type, t.counterparty, t.note,
    t.occurred_at AS occurredAt, t.local_date AS localDate, t.source,
    c.name AS categoryName, c.color AS categoryColor, a.name AS accountName,
    t.transfer_group_id AS transferGroupId
"""

private const val ROW_JOINS = """
    FROM transactions t
    LEFT JOIN categories c ON c.id = t.category_id
    LEFT JOIN accounts a ON a.id = t.account_id
"""

private const val SPENDING_FILTER = """
    t.deleted_at IS NULL AND t.status = 'CONFIRMED' AND t.type = 'EXPENSE'
    AND t.excluded_from_spending = 0 AND (c.id IS NULL OR c.counts_as_spending = 1)
"""

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: Long): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions WHERE transfer_group_id = :groupId")
    suspend fun getGroup(groupId: String): List<TransactionEntity>

    @Query("UPDATE transactions SET deleted_at = :at, updated_at = :at WHERE id IN (:ids)")
    suspend fun softDelete(ids: List<Long>, at: Long)

    @Query("UPDATE transactions SET deleted_at = NULL, updated_at = :at WHERE id IN (:ids)")
    suspend fun restore(ids: List<Long>, at: Long)

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun hardDelete(ids: List<Long>)

    /** Global lists show a transfer once (its outgoing leg); account lists show both legs. */
    @Query(
        "SELECT $ROW_COLUMNS $ROW_JOINS " +
            "WHERE t.deleted_at IS NULL AND t.status = 'CONFIRMED' " +
            "AND NOT (t.type = 'TRANSFER' AND t.direction = 'CREDIT') " +
            "ORDER BY t.occurred_at DESC, t.id DESC LIMIT :limit",
    )
    fun observeRecent(limit: Int): Flow<List<TransactionRow>>

    @Query(
        "SELECT $ROW_COLUMNS $ROW_JOINS " +
            """
            WHERE t.deleted_at IS NULL AND t.status = 'CONFIRMED'
              AND (:accountId IS NOT NULL OR NOT (t.type = 'TRANSFER' AND t.direction = 'CREDIT'))
              AND (:accountId IS NULL OR t.account_id = :accountId)
              AND (:direction IS NULL OR t.direction = :direction)
              AND (:onlyTransfers = 0 OR t.type = 'TRANSFER')
              AND (:excludeTransfers = 0 OR t.type != 'TRANSFER')
              AND (:ftsQuery IS NULL
                   OR t.id IN (SELECT rowid FROM transactions_fts WHERE transactions_fts MATCH :ftsQuery)
                   OR c.name LIKE :likeQuery
                   OR t.amount_paise = :amountPaise)
            ORDER BY t.occurred_at DESC, t.id DESC
            """,
    )
    fun pagingSource(
        accountId: Long?,
        direction: String?,
        onlyTransfers: Boolean,
        excludeTransfers: Boolean,
        ftsQuery: String?,
        likeQuery: String?,
        amountPaise: Long?,
    ): PagingSource<Int, TransactionRow>

    @Query("SELECT COALESCE(SUM(t.amount_paise), 0) FROM transactions t LEFT JOIN categories c ON c.id = t.category_id WHERE $SPENDING_FILTER AND t.local_date BETWEEN :fromDate AND :toDate")
    fun observeSpending(fromDate: Int, toDate: Int): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(amount_paise), 0) FROM transactions
        WHERE deleted_at IS NULL AND status = 'CONFIRMED' AND type = 'INCOME'
          AND local_date BETWEEN :fromDate AND :toDate
        """,
    )
    fun observeIncome(fromDate: Int, toDate: Int): Flow<Long>

    @Query("SELECT t.local_date AS date, SUM(t.amount_paise) AS total FROM transactions t LEFT JOIN categories c ON c.id = t.category_id WHERE $SPENDING_FILTER AND t.local_date >= :fromDate GROUP BY t.local_date")
    fun observeDailySpending(fromDate: Int): Flow<List<DayTotal>>

    @Query("SELECT COUNT(*) FROM transactions WHERE deleted_at IS NULL")
    fun observeCount(): Flow<Int>
}
