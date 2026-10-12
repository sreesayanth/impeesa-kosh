package com.impeesa.finance.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import com.impeesa.finance.core.data.db.CategoryDao
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.data.db.TransactionDao
import com.impeesa.finance.core.data.db.TransactionEntity
import com.impeesa.finance.core.data.db.TransactionRow
import com.impeesa.finance.core.engine.registry.AppRegistry
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.AmountInput
import com.impeesa.finance.core.model.Channel
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Direction
import com.impeesa.finance.core.model.EntryKind
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.TxnSource
import com.impeesa.finance.core.model.TxnType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/** What the add/edit screen saves. [amount] is always positive. */
data class TransactionDraft(
    val editingId: Long? = null,
    val kind: EntryKind,
    val amount: Money,
    /** Null only for captured transactions whose account is not known yet. */
    val accountId: Long?,
    val toAccountId: Long? = null,
    val categoryId: Long? = null,
    val occurredAt: LocalDateTime,
    val note: String? = null,
)

enum class ActivityKind { ALL, SPENT, RECEIVED, TRANSFERS }

/** One message behind a transaction. [merged] messages can be split back into their own transaction. */
data class MessageSourceRef(val rawId: Long, val label: String, val merged: Boolean, val text: String?)

data class ActivityFilter(
    val kind: ActivityKind = ActivityKind.ALL,
    val accountId: Long? = null,
    val query: String = "",
)

data class TransactionDetail(
    val id: Long,
    val kind: EntryKind,
    val amount: Money,
    val note: String?,
    val counterparty: String?,
    val occurredAt: Instant,
    val categoryId: Long?,
    val accountId: Long?,
    /** For transfers: where the money went. */
    val toAccountId: Long?,
    val source: TxnSource,
    val channel: Channel,
    val reference: String?,
    /** The SMS or notification this came from, when it was captured. */
    val rawMessageId: Long? = null,
    val pendingReview: Boolean = false,
    /** USER, RULE, LEARNED, DICTIONARY or KEYWORD; null when nothing decided. */
    val categorySource: String? = null,
    /** Why it has this category, in words. */
    val categoryReason: String? = null,
    val excludedFromSpending: Boolean = false,
)

class TransactionRepository internal constructor(
    private val db: ImpeesaDatabase,
    private val clock: Clock,
) {
    private val dao: TransactionDao = db.transactionDao()
    private val categories: CategoryDao = db.categoryDao()
    private val resolver = CategoryResolver(db, clock)

    fun observeSummary(dates: IntRange): Flow<PeriodSummary> =
        combine(
            dao.observeSpending(dates.first, dates.last),
            dao.observeIncome(dates.first, dates.last),
        ) { spent, income -> PeriodSummary(Money(spent), Money(income)) }

    fun observeRecent(limit: Int): Flow<List<TransactionRow>> = dao.observeRecent(limit)

    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observeDailySpending(fromDate: Int): Flow<Map<Int, Money>> =
        dao.observeDailySpending(fromDate).map { totals -> totals.associate { it.date to Money(it.total) } }

    fun pagedActivity(filter: ActivityFilter): Flow<PagingData<TransactionRow>> {
        val search = SearchQuery.from(filter.query)
        return Pager(PagingConfig(pageSize = 50, prefetchDistance = 100, enablePlaceholders = false)) {
            dao.pagingSource(
                accountId = filter.accountId,
                direction = when (filter.kind) {
                    ActivityKind.SPENT -> Direction.DEBIT.name
                    ActivityKind.RECEIVED -> Direction.CREDIT.name
                    else -> null
                },
                onlyTransfers = filter.kind == ActivityKind.TRANSFERS,
                excludeTransfers = filter.kind == ActivityKind.SPENT || filter.kind == ActivityKind.RECEIVED,
                ftsQuery = search?.fts,
                likeQuery = search?.like,
                amountPaise = search?.amountPaise,
            )
        }.flow
    }

    fun observeDetail(id: Long): Flow<TransactionDetail?> = dao.observeById(id).map { entity ->
        entity?.takeIf { it.deletedAt == null }?.let { toDetail(it) }
    }

    suspend fun draftFor(id: Long): TransactionDraft? {
        val detail = dao.getById(id)?.let { toDetail(it) } ?: return null
        val zone = clock.zone
        return TransactionDraft(
            editingId = detail.id,
            kind = detail.kind,
            amount = detail.amount,
            accountId = detail.accountId,
            toAccountId = detail.toAccountId,
            categoryId = detail.categoryId,
            occurredAt = LocalDateTime.ofInstant(detail.occurredAt, zone),
            note = detail.note,
        )
    }

    /** Saves a new or edited transaction. Transfers are stored as a linked pair of rows. */
    suspend fun save(draft: TransactionDraft): Long {
        require(draft.amount.paise > 0) { "Amount must be positive" }
        if (draft.kind == EntryKind.TRANSFER) {
            requireNotNull(draft.accountId) { "Transfer needs a source account" }
            requireNotNull(draft.toAccountId) { "Transfer needs a destination" }
            require(draft.toAccountId != draft.accountId) { "Pick two different accounts" }
        }
        return db.withTransaction {
            val now = clock.millis()
            var source = TxnSource.MANUAL
            var createdAt = now
            if (draft.editingId != null) {
                val existing = requireNotNull(dao.getById(draft.editingId)) { "Transaction not found" }
                source = existing.source
                createdAt = existing.createdAt
                val editedBits = if (existing.source == TxnSource.MANUAL) 0 else ALL_FIELDS_EDITED
                if (existing.transferGroupId == null && draft.kind != EntryKind.TRANSFER) {
                    resolver.recordCorrection(existing.merchantKey, existing.categoryId, draft.categoryId, existing.id)
                    dao.update(
                        single(draft, source, createdAt, now).copy(
                            id = existing.id,
                            userEditedFields = editedBits,
                            // Keep what capture knew about the row.
                            counterparty = existing.counterparty,
                            currency = existing.currency,
                            channel = existing.channel,
                            reference = existing.reference,
                            confidence = existing.confidence,
                            rawMessageId = existing.rawMessageId,
                            merchantKey = existing.merchantKey,
                            senderCode = existing.senderCode,
                            accountMask = existing.accountMask,
                        ),
                    )
                    return@withTransaction existing.id
                }
                val ids = existing.transferGroupId?.let { group -> dao.getGroup(group).map { it.id } } ?: listOf(existing.id)
                dao.hardDelete(ids)
            }
            if (draft.kind == EntryKind.TRANSFER) insertTransfer(draft, source, createdAt, now)
            else dao.insert(single(draft, source, createdAt, now))
        }
    }

    /** Soft-deletes a transaction (both legs for transfers). Returns ids for [restore]. */
    suspend fun delete(id: Long): List<Long> = db.withTransaction {
        val entity = dao.getById(id) ?: return@withTransaction emptyList()
        val ids = entity.transferGroupId?.let { group -> dao.getGroup(group).map { it.id } } ?: listOf(id)
        dao.softDelete(ids, clock.millis())
        ids
    }

    suspend fun restore(ids: List<Long>) = dao.restore(ids, clock.millis())

    private fun single(draft: TransactionDraft, source: TxnSource, createdAt: Long, now: Long): TransactionEntity {
        val income = draft.kind == EntryKind.INCOME
        return TransactionEntity(
            accountId = draft.accountId,
            amountPaise = draft.amount.paise,
            direction = if (income) Direction.CREDIT else Direction.DEBIT,
            type = if (income) TxnType.INCOME else TxnType.EXPENSE,
            categoryId = draft.categoryId,
            categorySource = "USER",
            counterparty = null,
            note = draft.note?.trim()?.ifEmpty { null },
            occurredAt = draft.occurredAt.atZone(clock.zone).toInstant().toEpochMilli(),
            localDate = DateKey.of(draft.occurredAt.toLocalDate()),
            source = source,
            createdAt = createdAt,
            updatedAt = now,
        )
    }

    private suspend fun insertTransfer(draft: TransactionDraft, source: TxnSource, createdAt: Long, now: Long): Long {
        val toAccountId = requireNotNull(draft.toAccountId)
        val accountDao = db.accountDao()
        val from = accountDao.getById(requireNotNull(draft.accountId))
        val to = accountDao.getById(toAccountId)
        val categoryName = when {
            to?.type == AccountType.CARD -> "Card payments"
            to?.type == AccountType.CASH && from?.type == AccountType.BANK -> "Cash withdrawal"
            to?.type == AccountType.WALLET -> "Wallet top-up"
            else -> "Transfers"
        }
        val categoryId = categories.idByName(categoryName)
        val group = UUID.randomUUID().toString()
        val occurredAt = draft.occurredAt.atZone(clock.zone).toInstant().toEpochMilli()
        val base = TransactionEntity(
            accountId = draft.accountId,
            amountPaise = draft.amount.paise,
            direction = Direction.DEBIT,
            type = TxnType.TRANSFER,
            categoryId = categoryId,
            categorySource = "USER",
            counterparty = null,
            note = draft.note?.trim()?.ifEmpty { null },
            occurredAt = occurredAt,
            localDate = DateKey.of(draft.occurredAt.toLocalDate()),
            source = source,
            transferGroupId = group,
            excludedFromSpending = true,
            createdAt = createdAt,
            updatedAt = now,
        )
        val outId = dao.insert(base)
        dao.insert(base.copy(accountId = toAccountId, direction = Direction.CREDIT))
        return outId
    }

    private suspend fun toDetail(entity: TransactionEntity): TransactionDetail {
        var accountId = entity.accountId
        var toAccountId: Long? = null
        val kind = when (entity.type) {
            TxnType.TRANSFER -> EntryKind.TRANSFER
            TxnType.INCOME, TxnType.REFUND -> EntryKind.INCOME
            else -> if (entity.direction == Direction.CREDIT) EntryKind.INCOME else EntryKind.EXPENSE
        }
        var id = entity.id
        if (kind == EntryKind.TRANSFER && entity.transferGroupId != null) {
            val legs = dao.getGroup(entity.transferGroupId)
            val out = legs.firstOrNull { it.direction == Direction.DEBIT } ?: entity
            accountId = out.accountId
            toAccountId = legs.firstOrNull { it.direction == Direction.CREDIT }?.accountId
            id = out.id
        }
        return TransactionDetail(
            id = id,
            kind = kind,
            amount = Money(entity.amountPaise),
            note = entity.note,
            counterparty = entity.counterparty,
            occurredAt = Instant.ofEpochMilli(entity.occurredAt),
            categoryId = entity.categoryId,
            accountId = accountId,
            toAccountId = toAccountId,
            source = entity.source,
            channel = entity.channel,
            reference = entity.reference,
            rawMessageId = entity.rawMessageId,
            pendingReview = entity.status == com.impeesa.finance.core.model.TxnStatus.PENDING_REVIEW,
            categorySource = entity.categorySource,
            categoryReason = entity.categoryReason,
            excludedFromSpending = entity.excludedFromSpending,
        )
    }

    /** Every message that describes this transaction (one, or two when SMS and app notification were merged). */
    fun observeSources(txnId: Long): Flow<List<MessageSourceRef>> =
        dao.observeRawForTransaction(txnId).map { rows ->
            rows.map { raw ->
                MessageSourceRef(
                    rawId = raw.id,
                    label = if (raw.source == "SMS") "SMS from ${raw.sender}" else (apps.find(raw.sender)?.app ?: raw.sender) + " notification",
                    merged = raw.route == "MERGED",
                    text = raw.body?.let { body -> listOfNotNull(raw.title, body).joinToString("\n") },
                )
            }
        }

    private val apps by lazy { AppRegistry.load() }

    /** Text of the original SMS/notification, while it is still kept (see retention). */
    fun observeOriginalMessage(rawId: Long): Flow<String?> =
        db.captureDao().observeRaw(rawId).map { raw -> raw?.body?.let { body -> listOfNotNull(raw.title, body).joinToString("\n") } }

    private companion object {
        /** Every editable field; auto-captured rows edited by the user are never re-processed. */
        const val ALL_FIELDS_EDITED = 0x7FFFFFFF
    }
}

/** Turns what the user typed into an FTS prefix query, a LIKE pattern and an exact amount. */
internal data class SearchQuery(val fts: String, val like: String, val amountPaise: Long?) {
    companion object {
        fun from(raw: String): SearchQuery? {
            val tokens = raw.trim().split(Regex("\\s+")).map { token -> token.filter(Char::isLetterOrDigit) }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return null
            val amount = if (tokens.size == 1) AmountInput.parse(raw)?.paise else null
            return SearchQuery(
                fts = tokens.joinToString(" ") { "$it*" },
                like = "%" + raw.trim().replace("%", "").replace("_", "") + "%",
                amountPaise = amount,
            )
        }
    }
}
