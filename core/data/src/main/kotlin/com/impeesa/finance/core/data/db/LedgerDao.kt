package com.impeesa.finance.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Balance snapshots, card statements, alerts and per-account views (IMPLEMENTATION_PLAN.md §9.5, §5.7, §5.10). */
@Dao
internal interface LedgerDao {
    // ------------------------------------------------------------ snapshots

    @Insert
    suspend fun insertSnapshot(snapshot: BalanceSnapshotEntity): Long

    @Query("SELECT MAX(as_of) FROM balance_snapshots WHERE account_id = :accountId")
    suspend fun latestSnapshotAt(accountId: Long): Long?

    @Query("SELECT * FROM balance_snapshots WHERE account_id = :accountId ORDER BY as_of DESC, id DESC LIMIT 1")
    fun observeLatestSnapshot(accountId: Long): Flow<BalanceSnapshotEntity?>

    /** Recent reconciliations big enough to mention, for the "Balance updated" alert. */
    @Query("SELECT * FROM balance_snapshots WHERE as_of >= :since AND source = 'SMS' AND ABS(diff_paise) > :minDiff")
    suspend fun recentDifferences(since: Long, minDiff: Long): List<BalanceSnapshotEntity>

    // ------------------------------------------------------------ statements

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStatement(statement: CardStatementEntity): Long

    @Update
    suspend fun updateStatement(statement: CardStatementEntity)

    @Query("SELECT * FROM card_statements WHERE account_id = :accountId AND due_date = :dueDate")
    suspend fun statementFor(accountId: Long, dueDate: Int): CardStatementEntity?

    @Query("SELECT * FROM card_statements WHERE id = :id")
    suspend fun statement(id: Long): CardStatementEntity?

    @Query("SELECT * FROM card_statements WHERE account_id = :accountId ORDER BY due_date DESC LIMIT :limit")
    fun observeStatements(accountId: Long, limit: Int): Flow<List<CardStatementEntity>>

    /** The latest bill still open on a card, which incoming payments go towards. */
    @Query("SELECT * FROM card_statements WHERE account_id = :accountId AND paid_at IS NULL ORDER BY due_date DESC LIMIT 1")
    suspend fun openStatement(accountId: Long): CardStatementEntity?

    /** Each card's latest statement, for bill reminders. */
    @Query("SELECT c.* FROM card_statements c WHERE c.id = (SELECT c2.id FROM card_statements c2 WHERE c2.account_id = c.account_id ORDER BY c2.due_date DESC LIMIT 1)")
    suspend fun latestStatements(): List<CardStatementEntity>

    @Query("DELETE FROM card_statements WHERE id = :id")
    suspend fun deleteStatement(id: Long)

    // ------------------------------------------------------------ per account

    @Query("SELECT * FROM accounts WHERE id = :id")
    fun observeAccountEntity(id: Long): Flow<AccountEntity?>

    @Query(
        """
        SELECT t.id, t.amount_paise AS amountPaise, t.direction, t.type, t.counterparty, t.note,
               t.occurred_at AS occurredAt, t.local_date AS localDate, t.source,
               c.name AS categoryName, c.color AS categoryColor, a.name AS accountName,
               t.transfer_group_id AS transferGroupId
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.category_id
        LEFT JOIN accounts a ON a.id = t.account_id
        WHERE t.account_id = :accountId AND t.deleted_at IS NULL AND t.status = 'CONFIRMED'
        ORDER BY t.occurred_at DESC, t.id DESC LIMIT :limit
        """,
    )
    fun observeAccountTransactions(accountId: Long, limit: Int): Flow<List<TransactionRow>>

    /** Spending on one account by category over a date range (card cycle spend). */
    @Query(
        """
        SELECT t.category_id AS categoryId, c.name AS name, c.color AS color,
               SUM(CASE WHEN t.type = 'REFUND' THEN -t.amount_paise ELSE t.amount_paise END) AS total,
               SUM(CASE WHEN t.type = 'EXPENSE' THEN 1 ELSE 0 END) AS count
        FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
        WHERE t.account_id = :accountId AND t.deleted_at IS NULL AND t.status = 'CONFIRMED'
          AND ((t.type = 'EXPENSE' AND t.excluded_from_spending = 0) OR t.type = 'REFUND')
          AND t.local_date BETWEEN :fromDate AND :toDate
        GROUP BY t.category_id ORDER BY total DESC
        """,
    )
    fun observeAccountCategoryTotals(accountId: Long, fromDate: Int, toDate: Int): Flow<List<CategoryTotalRow>>

    /** Median-ish reference for "3× your usual": the account's debits over the last months. */
    @Query("SELECT amount_paise FROM transactions WHERE account_id = :accountId AND direction = 'DEBIT' AND deleted_at IS NULL AND status = 'CONFIRMED' AND occurred_at >= :since")
    suspend fun debitAmounts(accountId: Long, since: Long): List<Long>

    @Query("UPDATE accounts SET low_balance_paise = :paise, updated_at = :now WHERE id = :id")
    suspend fun setLowBalance(id: Long, paise: Long?, now: Long)

    @Query("UPDATE accounts SET statement_day = :statementDay, due_day = :dueDay, updated_at = :now WHERE id = :id")
    suspend fun setCardDays(id: Long, statementDay: Int?, dueDay: Int?, now: Long)

    // ------------------------------------------------------------ alerts

    /** Returns -1 when an alert with the same dedupe key already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlert(alert: AlertEntity): Long

    @Query("SELECT * FROM alerts WHERE created_at >= :since ORDER BY created_at DESC, id DESC")
    fun observeAlerts(since: Long): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE read_at IS NULL AND created_at >= :since")
    fun observeUnread(since: Long): Flow<Int>

    @Query("UPDATE alerts SET read_at = :at WHERE read_at IS NULL")
    suspend fun markAllRead(at: Long)

    @Query("UPDATE alerts SET read_at = :at WHERE id = :id AND read_at IS NULL")
    suspend fun markRead(id: Long, at: Long)

    /** Keeps the list to 60 days; dedupe keys of purged alerts are old enough not to recur. */
    @Query("DELETE FROM alerts WHERE created_at < :before")
    suspend fun purgeAlerts(before: Long)
}
