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

/** Account plus its live balance (see [BALANCE_EXPR]). */
data class AccountBalanceRow(
    val id: Long,
    val type: AccountType,
    val name: String,
    val mask: String?,
    val color: Int,
    val creditLimitPaise: Long?,
    val includeInNet: Boolean,
    val balancePaise: Long,
    /** When the bank (or the user) last told us the balance; null = computed from the opening balance. */
    val syncedAt: Long?,
    val syncSource: String?,
    val lowBalancePaise: Long?,
)

/** Signed sum of account `a`'s confirmed transactions; callers may add "AND t.occurred_at > …". */
private const val MOVEMENTS = "SELECT SUM(CASE WHEN t.direction = 'CREDIT' THEN t.amount_paise ELSE -t.amount_paise END) FROM transactions t WHERE t.account_id = a.id AND t.deleted_at IS NULL AND t.status = 'CONFIRMED'"

/** The latest snapshot for account a, as s. */
internal const val LATEST_SNAPSHOT_JOIN = "LEFT JOIN balance_snapshots s ON s.id = (SELECT s2.id FROM balance_snapshots s2 WHERE s2.account_id = a.id ORDER BY s2.as_of DESC, s2.id DESC LIMIT 1)"

/**
 * Balance per IMPLEMENTATION_PLAN.md §9.5: the latest snapshot plus what happened after it; a card's
 * available-limit snapshot means owed = limit − available; without snapshots, opening balance + everything.
 */
internal const val BALANCE_EXPR = """
    CASE
      WHEN s.kind = 'BALANCE' THEN s.balance_paise + COALESCE(($MOVEMENTS AND t.occurred_at > s.as_of), 0)
      WHEN s.kind = 'AVAILABLE_LIMIT' AND a.credit_limit_paise IS NOT NULL
        THEN s.balance_paise - a.credit_limit_paise + COALESCE(($MOVEMENTS AND t.occurred_at > s.as_of), 0)
      ELSE a.opening_balance_paise + COALESCE(($MOVEMENTS), 0)
    END
"""

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

    @Query("SELECT * FROM accounts WHERE archived = 0")
    suspend fun activeAccounts(): List<AccountEntity>

    @Query("SELECT id FROM accounts WHERE type = 'CASH' AND archived = 0 ORDER BY id LIMIT 1")
    suspend fun cashAccountId(): Long?

    @Query(
        "SELECT a.id, a.type, a.name, a.mask, a.color, a.credit_limit_paise AS creditLimitPaise, a.include_in_net AS includeInNet, " +
            "$BALANCE_EXPR AS balancePaise, s.as_of AS syncedAt, s.source AS syncSource, a.low_balance_paise AS lowBalancePaise " +
            "FROM accounts a $LATEST_SNAPSHOT_JOIN WHERE a.archived = 0 " +
            "ORDER BY CASE a.type WHEN 'BANK' THEN 0 WHEN 'CARD' THEN 1 WHEN 'WALLET' THEN 2 ELSE 3 END, a.name",
    )
    fun observeActiveWithBalance(): Flow<List<AccountBalanceRow>>

    @Query("SELECT $BALANCE_EXPR FROM accounts a $LATEST_SNAPSHOT_JOIN WHERE a.id = :id")
    suspend fun balanceOf(id: Long): Long?

    // ------------------------------------------------------------ merging duplicates

    @Query("UPDATE transactions SET account_id = :into WHERE account_id = :from")
    suspend fun moveTransactions(from: Long, into: Long)

    @Query("UPDATE account_identifiers SET account_id = :into WHERE account_id = :from")
    suspend fun moveIdentifiers(from: Long, into: Long)

    @Query("UPDATE rules SET account_id = :into WHERE account_id = :from")
    suspend fun moveRules(from: Long, into: Long)

    @Query("UPDATE balance_snapshots SET account_id = :into WHERE account_id = :from")
    suspend fun moveSnapshots(from: Long, into: Long)

    /** A bill both accounts have (same due date) stays once, on the account kept. */
    @Query("UPDATE OR IGNORE card_statements SET account_id = :into WHERE account_id = :from")
    suspend fun moveStatements(from: Long, into: Long)

    @Query("DELETE FROM card_statements WHERE account_id = :id")
    suspend fun deleteStatementsOf(id: Long)

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long)
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

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM categories")
    suspend fun maxSortOrder(): Int

    @Query("SELECT COUNT(*) FROM categories WHERE name = :name COLLATE NOCASE AND archived = 0 AND id != :excludeId")
    suspend fun countNamed(name: String, excludeId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE category_id = :id AND deleted_at IS NULL")
    suspend fun usage(id: Long): Int

    /** Moves everything that used [from] to [to] (archiving a category). */
    @Query("UPDATE transactions SET category_id = :to, updated_at = :at WHERE category_id = :from")
    suspend fun reassignTransactions(from: Long, to: Long, at: Long)

    @Query("UPDATE rules SET category_id = :to WHERE category_id = :from")
    suspend fun reassignRules(from: Long, to: Long)

    @Query("UPDATE category_corrections SET category_id = :to WHERE category_id = :from")
    suspend fun reassignCorrections(from: Long, to: Long)
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

/** A transaction waiting for the user's decision, with the message it came from. */
data class ReviewRow(
    val id: Long,
    val amountPaise: Long,
    val currency: String,
    val direction: Direction,
    val type: TxnType,
    val counterparty: String?,
    val categoryId: Long?,
    val occurredAt: Long,
    val reviewReason: String?,
    val accountName: String?,
    val senderCode: String?,
    val accountMask: String?,
    val rawSender: String?,
    val rawBody: String?,
    val duplicateOf: Long?,
    val duplicateCounterparty: String?,
    val duplicateSource: String?,
)

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

/** Spending (§10.1): expenses in spending categories, minus refunds linked back to them. */
private const val SPENDING_FILTER = """
    t.deleted_at IS NULL AND t.status = 'CONFIRMED'
    AND ((t.type = 'EXPENSE' AND t.excluded_from_spending = 0) OR t.type = 'REFUND')
    AND (c.id IS NULL OR c.counts_as_spending = 1)
"""

private const val NET_AMOUNT = "CASE WHEN t.type = 'REFUND' THEN -t.amount_paise ELSE t.amount_paise END"

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

    @Query("SELECT COALESCE(SUM($NET_AMOUNT), 0) FROM transactions t LEFT JOIN categories c ON c.id = t.category_id WHERE $SPENDING_FILTER AND t.local_date BETWEEN :fromDate AND :toDate")
    fun observeSpending(fromDate: Int, toDate: Int): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(amount_paise), 0) FROM transactions
        WHERE deleted_at IS NULL AND status = 'CONFIRMED' AND type = 'INCOME'
          AND local_date BETWEEN :fromDate AND :toDate
        """,
    )
    fun observeIncome(fromDate: Int, toDate: Int): Flow<Long>

    @Query("SELECT t.local_date AS date, SUM($NET_AMOUNT) AS total FROM transactions t LEFT JOIN categories c ON c.id = t.category_id WHERE $SPENDING_FILTER AND t.local_date >= :fromDate GROUP BY t.local_date")
    fun observeDailySpending(fromDate: Int): Flow<List<DayTotal>>

    @Query("SELECT COUNT(*) FROM transactions WHERE deleted_at IS NULL")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT id FROM transactions
        WHERE reference = :reference AND amount_paise = :amountPaise AND direction = :direction AND deleted_at IS NULL
        LIMIT 1
        """,
    )
    suspend fun findByReference(reference: String, amountPaise: Long, direction: String): Long?

    @Query(
        """
        SELECT t.id, t.amount_paise AS amountPaise, t.currency, t.direction, t.type, t.counterparty,
               t.category_id AS categoryId, t.occurred_at AS occurredAt, t.review_reason AS reviewReason,
               a.name AS accountName, t.sender_code AS senderCode, t.account_mask AS accountMask,
               r.sender AS rawSender, r.body AS rawBody,
               t.duplicate_of AS duplicateOf, d.counterparty AS duplicateCounterparty, d.source AS duplicateSource
        FROM transactions t
        LEFT JOIN accounts a ON a.id = t.account_id
        LEFT JOIN raw_messages r ON r.id = t.raw_message_id
        LEFT JOIN transactions d ON d.id = t.duplicate_of
        WHERE t.status = 'PENDING_REVIEW' AND t.deleted_at IS NULL
        ORDER BY t.occurred_at DESC, t.id DESC
        """,
    )
    fun observeReviewQueue(): Flow<List<ReviewRow>>

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'PENDING_REVIEW' AND deleted_at IS NULL")
    fun observeReviewCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM transactions WHERE status = 'PENDING_REVIEW' AND deleted_at IS NULL")
    suspend fun reviewCount(): Int

    @Query("UPDATE transactions SET status = :status, review_reason = CASE WHEN :status = 'CONFIRMED' THEN NULL ELSE review_reason END, updated_at = :at WHERE id IN (:ids)")
    suspend fun setStatus(ids: List<Long>, status: String, at: Long)

    @Query("UPDATE transactions SET category_id = :categoryId, category_source = 'USER', category_reason = NULL, updated_at = :at WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long?, at: Long)

    @Query("SELECT id FROM transactions WHERE status = 'PENDING_REVIEW' AND deleted_at IS NULL")
    suspend fun reviewIds(): List<Long>

    /** When an account is added, claim earlier captured rows that carry its sender and digits. */
    @Query("UPDATE transactions SET account_id = :accountId WHERE account_id IS NULL AND sender_code = :sender AND account_mask = :mask")
    suspend fun backfillAccount(accountId: Long, sender: String, mask: String): Int

    /** Captured rows from the other source (SMS vs notification) that could be the same payment. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amount_paise = :amountPaise AND direction = :direction
          AND occurred_at BETWEEN :fromMillis AND :toMillis
          AND source != :source AND source != 'MANUAL'
          AND deleted_at IS NULL AND status != 'IGNORED' AND duplicate_of IS NULL
          AND raw_message_id IS NOT NULL
        ORDER BY ABS(occurred_at - :aroundMillis)
        """,
    )
    suspend fun crossSourceCandidates(amountPaise: Long, direction: String, source: String, fromMillis: Long, toMillis: Long, aroundMillis: Long): List<TransactionEntity>

    /** The other side of a possible transfer: opposite direction, same amount, no group yet. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amount_paise = :amountPaise AND direction = :direction AND id != :excludeId
          AND occurred_at BETWEEN :fromMillis AND :toMillis
          AND transfer_group_id IS NULL AND deleted_at IS NULL AND status != 'IGNORED'
          AND type IN (:types)
        ORDER BY ABS(occurred_at - :aroundMillis)
        LIMIT 5
        """,
    )
    suspend fun transferCounterparts(
        amountPaise: Long,
        direction: String,
        excludeId: Long,
        types: List<String>,
        fromMillis: Long,
        toMillis: Long,
        aroundMillis: Long,
    ): List<TransactionEntity>

    /** The purchase a refund belongs to. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE merchant_key = :merchantKey AND direction = 'DEBIT' AND type = 'EXPENSE'
          AND amount_paise >= :minAmountPaise AND occurred_at BETWEEN :fromMillis AND :toMillis
          AND deleted_at IS NULL AND status = 'CONFIRMED'
        ORDER BY occurred_at DESC LIMIT 1
        """,
    )
    suspend fun refundOriginal(merchantKey: String, minAmountPaise: Long, fromMillis: Long, toMillis: Long): TransactionEntity?

    /** A "debited but failed" row waiting for its reversal. */
    @Query(
        """
        SELECT * FROM transactions
        WHERE amount_paise = :amountPaise AND direction = 'DEBIT' AND review_reason = 'FAILED_BUT_DEBITED'
          AND occurred_at BETWEEN :fromMillis AND :toMillis AND deleted_at IS NULL AND status != 'IGNORED'
        ORDER BY occurred_at DESC LIMIT 1
        """,
    )
    suspend fun failedDebit(amountPaise: Long, fromMillis: Long, toMillis: Long): TransactionEntity?

    @Query("SELECT * FROM raw_messages WHERE txn_id = :txnId ORDER BY received_at")
    fun observeRawForTransaction(txnId: Long): Flow<List<RawMessageEntity>>

    /** Same, for an account added by hand: only the digits are known. */
    @Query("UPDATE transactions SET account_id = :accountId WHERE account_id IS NULL AND account_mask = :mask")
    suspend fun backfillByMask(accountId: Long, mask: String): Int
}
