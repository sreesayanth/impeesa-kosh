package com.impeesa.finance.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE archived = 0 ORDER BY type, name")
    fun observeActive(): Flow<List<AccountEntity>>
}

@Dao
interface CategoryDao {
    @Insert
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY sort_order")
    fun observeAll(): Flow<List<CategoryEntity>>
}

/** A transaction joined with what the list needs to show it. */
data class TransactionListItem(
    val id: Long,
    val amountPaise: Long,
    val direction: String,
    val counterparty: String?,
    val note: String?,
    val occurredAt: Long,
    val source: String,
    val categoryName: String?,
    val categoryColor: Int?,
)

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query(
        """
        SELECT t.id, t.amount_paise AS amountPaise, t.direction, t.counterparty, t.note,
               t.occurred_at AS occurredAt, t.source, c.name AS categoryName, c.color AS categoryColor
        FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.deleted_at IS NULL AND t.status = 'CONFIRMED'
        ORDER BY t.occurred_at DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<TransactionListItem>>

    @Query(
        """
        SELECT COALESCE(SUM(t.amount_paise), 0)
        FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.deleted_at IS NULL AND t.status = 'CONFIRMED' AND t.type = 'EXPENSE'
          AND t.excluded_from_spending = 0 AND (c.id IS NULL OR c.counts_as_spending = 1)
          AND t.local_date BETWEEN :fromDate AND :toDate
        """,
    )
    fun observeSpending(fromDate: Int, toDate: Int): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(amount_paise), 0) FROM transactions
        WHERE deleted_at IS NULL AND status = 'CONFIRMED' AND type = 'INCOME'
          AND local_date BETWEEN :fromDate AND :toDate
        """,
    )
    fun observeIncome(fromDate: Int, toDate: Int): Flow<Long>
}
