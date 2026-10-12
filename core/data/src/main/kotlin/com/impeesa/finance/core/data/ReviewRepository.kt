package com.impeesa.finance.core.data

import androidx.room.withTransaction
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.data.db.ReviewRow
import com.impeesa.finance.core.model.TxnStatus
import kotlinx.coroutines.flow.Flow
import java.time.Clock

/** The queue of captured transactions the engine was unsure about (IMPLEMENTATION_PLAN.md §5.3). */
class ReviewRepository internal constructor(
    private val db: ImpeesaDatabase,
    private val clock: Clock,
) {
    private val dao = db.transactionDao()
    private val resolver = CategoryResolver(db, clock)

    fun observeQueue(): Flow<List<ReviewRow>> = dao.observeReviewQueue()

    fun observeCount(): Flow<Int> = dao.observeReviewCount()

    suspend fun count(): Int = dao.reviewCount()

    /**
     * Accepts a transaction, optionally with a corrected category. Picking something other than
     * the suggestion teaches the app about that merchant (§7.4).
     */
    suspend fun confirm(id: Long, categoryId: Long?) = db.withTransaction {
        val now = clock.millis()
        val row = dao.getById(id)
        if (categoryId != null && row != null && categoryId != row.categoryId) {
            dao.setCategory(id, categoryId, now)
            resolver.recordCorrection(row.merchantKey, row.categoryId, categoryId, id)
        }
        dao.setStatus(listOf(id), TxnStatus.CONFIRMED.name, now)
    }

    /** "Not spending": keeps the row (so it is never re-captured) but hides it everywhere. */
    suspend fun ignore(id: Long) = dao.setStatus(listOf(id), TxnStatus.IGNORED.name, clock.millis())

    suspend fun confirmAll(): List<Long> {
        val ids = dao.reviewIds()
        if (ids.isNotEmpty()) dao.setStatus(ids, TxnStatus.CONFIRMED.name, clock.millis())
        return ids
    }

    /** "Same payment": folds a possible duplicate into the original. Returns what [restoreDuplicate] needs. */
    suspend fun mergeDuplicate(id: Long): Long? = db.withTransaction {
        val row = dao.getById(id) ?: return@withTransaction null
        val target = row.duplicateOf ?: return@withTransaction null
        db.captureDao().moveRaw(fromTxn = id, toTxn = target)
        dao.softDelete(listOf(id), clock.millis())
        target
    }

    /** Undo for [mergeDuplicate]. */
    suspend fun restoreDuplicate(id: Long, target: Long) = db.withTransaction {
        dao.restore(listOf(id), clock.millis())
        // mergeDuplicate moved this row's message onto the original; give it back.
        dao.getById(id)?.rawMessageId?.let { rawId -> db.captureDao().relinkRaw(rawId, id, "REVIEW") }
        Unit
    }

    /** "Different payments": keeps both and confirms the new one. */
    suspend fun keepBoth(id: Long) = db.withTransaction {
        dao.getById(id)?.let { dao.update(it.copy(duplicateOf = null, status = TxnStatus.CONFIRMED, reviewReason = null, updatedAt = clock.millis())) }
    }

    /** Puts items back in the queue (undo). */
    suspend fun backToReview(ids: List<Long>) = dao.setStatus(ids, TxnStatus.PENDING_REVIEW.name, clock.millis())
}
