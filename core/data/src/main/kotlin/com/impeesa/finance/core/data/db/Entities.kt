package com.impeesa.finance.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
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
)
