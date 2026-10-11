package com.impeesa.finance.core.data

import com.impeesa.finance.core.data.db.CategoryDao
import com.impeesa.finance.core.data.db.TransactionDao
import com.impeesa.finance.core.data.db.TransactionListItem
import com.impeesa.finance.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class CategoryRepository(private val dao: CategoryDao) {
    /** Inserts the default categories on first run. Safe to call on every start. */
    suspend fun ensureSeeded() {
        if (dao.count() == 0) dao.insertAll(DefaultCategories.all())
    }
}

data class PeriodSummary(val spent: Money, val income: Money) {
    val net: Money get() = income - spent
}

class TransactionRepository(private val dao: TransactionDao) {
    fun observeSummary(dates: IntRange): Flow<PeriodSummary> =
        combine(
            dao.observeSpending(dates.first, dates.last),
            dao.observeIncome(dates.first, dates.last),
        ) { spent, income -> PeriodSummary(Money(spent), Money(income)) }

    fun observeRecent(limit: Int): Flow<List<TransactionListItem>> = dao.observeRecent(limit)
}
