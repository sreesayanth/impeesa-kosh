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

    fun observeQueue(): Flow<List<ReviewRow>> = dao.observeReviewQueue()

    fun observeCount(): Flow<Int> = dao.observeReviewCount()

    suspend fun count(): Int = dao.reviewCount()

    /** Accepts a transaction, optionally with a corrected category. */
    suspend fun confirm(id: Long, categoryId: Long?) = db.withTransaction {
        val now = clock.millis()
        if (categoryId != null) dao.setCategory(id, categoryId, now)
        dao.setStatus(listOf(id), TxnStatus.CONFIRMED.name, now)
    }

    /** "Not spending": keeps the row (so it is never re-captured) but hides it everywhere. */
    suspend fun ignore(id: Long) = dao.setStatus(listOf(id), TxnStatus.IGNORED.name, clock.millis())

    suspend fun confirmAll(): List<Long> {
        val ids = dao.reviewIds()
        if (ids.isNotEmpty()) dao.setStatus(ids, TxnStatus.CONFIRMED.name, clock.millis())
        return ids
    }

    /** Puts items back in the queue (undo). */
    suspend fun backToReview(ids: List<Long>) = dao.setStatus(ids, TxnStatus.PENDING_REVIEW.name, clock.millis())
}
