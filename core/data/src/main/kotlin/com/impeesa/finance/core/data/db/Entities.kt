package com.impeesa.finance.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.CategoryKind
import com.impeesa.finance.core.model.Channel
import com.impeesa.finance.core.model.Direction
import com.impeesa.finance.core.model.TxnSource
import com.impeesa.finance.core.model.TxnStatus
import com.impeesa.finance.core.model.TxnType

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: AccountType,
    val institution: String?,
    val name: String,
    val mask: String?,
    val color: Int,
    @ColumnInfo(name = "opening_balance_paise") val openingBalancePaise: Long = 0,
    @ColumnInfo(name = "opening_date") val openingDate: Int? = null,
    @ColumnInfo(name = "credit_limit_paise") val creditLimitPaise: Long? = null,
    @ColumnInfo(name = "statement_day") val statementDay: Int? = null,
    @ColumnInfo(name = "due_day") val dueDay: Int? = null,
    @ColumnInfo(name = "include_in_net") val includeInNet: Boolean = true,
    val archived: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "categories", indices = [Index("parent_id")])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    @ColumnInfo(name = "parent_id") val parentId: Long? = null,
    val color: Int,
    val icon: String,
    @ColumnInfo(name = "counts_as_spending") val countsAsSpending: Boolean,
    @ColumnInfo(name = "is_system") val isSystem: Boolean = true,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean = false,
)

@Entity(
    tableName = "transactions",
    indices = [
        Index("local_date"),
        Index("account_id", "local_date"),
        Index("category_id", "local_date"),
        Index("status"),
        Index("reference"),
        Index("amount_paise", "direction", "occurred_at"),
        Index("sender_code", "account_mask"),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long?,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    val currency: String = "INR",
    val direction: Direction,
    val type: TxnType,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "category_source") val categorySource: String? = null,
    val counterparty: String?,
    val note: String? = null,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "local_date") val localDate: Int,
    val channel: Channel = Channel.OTHER,
    val reference: String? = null,
    val status: TxnStatus = TxnStatus.CONFIRMED,
    val source: TxnSource,
    val confidence: Float = 1f,
    @ColumnInfo(name = "transfer_group_id") val transferGroupId: String? = null,
    @ColumnInfo(name = "linked_txn_id") val linkedTxnId: Long? = null,
    @ColumnInfo(name = "excluded_from_spending") val excludedFromSpending: Boolean = false,
    @ColumnInfo(name = "user_edited_fields") val userEditedFields: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "review_reason") val reviewReason: String? = null,
    @ColumnInfo(name = "raw_message_id") val rawMessageId: Long? = null,
    @ColumnInfo(name = "merchant_key") val merchantKey: String? = null,
    /** SMS sender entity (e.g. HDFCBK) and account digits, kept so a later account can claim the row. */
    @ColumnInfo(name = "sender_code") val senderCode: String? = null,
    @ColumnInfo(name = "account_mask") val accountMask: String? = null,
    /** Set on a row that may describe the same payment as another (cross-source). */
    @ColumnInfo(name = "duplicate_of") val duplicateOf: Long? = null,
    /** How [linkedTxnId] relates: REFUND_OF or REVERSAL_OF. */
    @ColumnInfo(name = "link_kind") val linkKind: String? = null,
    /** Why the row has its category, in words ("Your rule: Merchant is Swiggy"). */
    @ColumnInfo(name = "category_reason") val categoryReason: String? = null,
)

/** A financial message that produced a transaction or useful info. Non-financial messages are never stored. */
@Entity(
    tableName = "raw_messages",
    indices = [Index(value = ["source_hash"], unique = true), Index("txn_id")],
)
data class RawMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val sender: String,
    val title: String?,
    /** Cleared after the retention period; extracted fields stay on the transaction. */
    val body: String?,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "source_hash") val sourceHash: String,
    @ColumnInfo(name = "parser_version") val parserVersion: Int,
    @ColumnInfo(name = "message_type") val messageType: String,
    val route: String,
    @ColumnInfo(name = "txn_id") val txnId: Long? = null,
)

/** Monthly counts of what capture did, including skipped messages (only the count is kept). */
@Entity(tableName = "capture_stats", primaryKeys = ["month", "source", "outcome"])
data class CaptureStatEntity(
    val month: Int,
    val source: String,
    val outcome: String,
    val count: Int,
)

/** An account seen in messages that the user has not added yet. */
@Entity(
    tableName = "account_suggestions",
    indices = [Index(value = ["sender_code", "mask"], unique = true)],
)
data class AccountSuggestionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sender_code") val senderCode: String,
    val mask: String,
    val institution: String?,
    @ColumnInfo(name = "looks_like_card") val looksLikeCard: Boolean,
    val sightings: Int,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
    val dismissed: Boolean = false,
)

/** Links an account to the SMS sender and digits that identify it. */
@Entity(
    tableName = "account_identifiers",
    indices = [Index(value = ["sender_code", "mask"], unique = true), Index("account_id")],
)
data class AccountIdentifierEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "sender_code") val senderCode: String,
    val mask: String?,
)

/** Full-text index over merchant and note; Room keeps it in sync with `transactions`. */
@Fts4(contentEntity = TransactionEntity::class)
@Entity(tableName = "transactions_fts")
data class TransactionFts(
    val counterparty: String?,
    val note: String?,
)

/** A user rule (IMPLEMENTATION_PLAN.md §5.11, §7.1). Match and action names come from the engine's enums. */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "match_type") val matchType: String,
    val pattern: String,
    val label: String,
    @ColumnInfo(name = "account_id") val accountId: Long? = null,
    @ColumnInfo(name = "min_paise") val minPaise: Long? = null,
    @ColumnInfo(name = "max_paise") val maxPaise: Long? = null,
    val action: String,
    @ColumnInfo(name = "category_id") val categoryId: Long? = null,
    /** EDIT: from an "Always sort" switch; MANUAL: made in the rule editor. */
    val source: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    @ColumnInfo(name = "match_count") val matchCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Each time the user moved a merchant's transaction to a category; learning reads this log (§7.4). */
@Entity(tableName = "category_corrections", indices = [Index("merchant_key")])
data class CategoryCorrectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "merchant_key") val merchantKey: String,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "transaction_id") val transactionId: Long?,
    val at: Long,
)
/** A monthly budget: overall when [categoryId] is null (IMPLEMENTATION_PLAN.md §5.8). */
@Entity(tableName = "budgets", indices = [Index("category_id")])
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "category_id") val categoryId: Long?,
    @ColumnInfo(name = "amount_paise") val amountPaise: Long,
    /** Warn when this share of the budget is used: 50, 80 or 100. */
    @ColumnInfo(name = "alert_pct") val alertPct: Int = 80,
    /** Add last period's unspent money to this one. */
    val rollover: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val archived: Boolean = false,
)

/** The user's verdict on a detected recurring payment, by payee key. */
@Entity(tableName = "recurring_prefs")
data class RecurringPrefEntity(
    @PrimaryKey val key: String,
    /** HIDDEN: "not a subscription". */
    val status: String,
    val at: Long,
)