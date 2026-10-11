package com.impeesa.finance.core.data

import com.impeesa.finance.core.data.db.CategoryDao
import com.impeesa.finance.core.data.db.CategoryEntity
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.model.CategoryKind
import com.impeesa.finance.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CategoryRepository internal constructor(private val dao: CategoryDao) {
    /** Inserts the default categories on first run. Safe to call on every start. */
    suspend fun ensureSeeded() {
        if (dao.count() == 0) dao.insertAll(DefaultCategories.all())
    }

    fun observe(kind: CategoryKind): Flow<List<Category>> =
        dao.observeByKind(kind).map { list -> list.map { it.toModel() } }

    fun observeAll(): Flow<List<Category>> = dao.observeAll().map { list -> list.map { it.toModel() } }
}

internal fun CategoryEntity.toModel() = Category(id = id, name = name, kind = kind, color = color)

data class PeriodSummary(val spent: Money, val income: Money) {
    val net: Money get() = income - spent
}
