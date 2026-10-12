package com.impeesa.finance.core.data

import androidx.room.withTransaction
import com.impeesa.finance.core.data.db.CategoryDao
import com.impeesa.finance.core.data.db.CategoryEntity
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.model.Category
import com.impeesa.finance.core.model.CategoryKind
import com.impeesa.finance.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

class CategoryRepository internal constructor(
    private val db: ImpeesaDatabase,
    private val clock: Clock,
) {
    private val dao: CategoryDao = db.categoryDao()

    /** Inserts the default categories on first run. Safe to call on every start. */
    suspend fun ensureSeeded() {
        if (dao.count() == 0) dao.insertAll(DefaultCategories.all())
    }

    fun observe(kind: CategoryKind): Flow<List<Category>> =
        dao.observeByKind(kind).map { list -> list.map { it.toModel() } }

    fun observeAll(): Flow<List<Category>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    suspend fun get(id: Long): Category? = dao.getById(id)?.toModel()

    /** How many transactions use a category (shown before archiving). */
    suspend fun usage(id: Long): Int = dao.usage(id)

    suspend fun nameTaken(name: String, excludeId: Long = 0): Boolean = dao.countNamed(name.trim(), excludeId) > 0

    /** Adds a category of the user's own. Spending categories count towards spending; the others never do. */
    suspend fun create(name: String, kind: CategoryKind, color: Int): Long {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Name is empty" }
        return dao.insert(
            CategoryEntity(
                name = clean,
                kind = kind,
                color = color,
                icon = "other",
                countsAsSpending = kind == CategoryKind.EXPENSE,
                isSystem = false,
                sortOrder = dao.maxSortOrder() + 1,
            ),
        )
    }

    /** Renames and recolours a category. Built-in categories can only be recoloured. */
    suspend fun rename(id: Long, name: String, color: Int) {
        val existing = dao.getById(id) ?: return
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Name is empty" }
        dao.update(existing.copy(name = if (existing.isSystem) existing.name else clean, color = color))
    }

    /**
     * Archives a category and moves its transactions, rules and learning to [moveTo]
     * (§5.11: "archiving asks where to move existing transactions").
     */
    suspend fun archive(id: Long, moveTo: Long) = db.withTransaction {
        require(id != moveTo) { "Pick another category" }
        val existing = dao.getById(id) ?: return@withTransaction
        require(!existing.isSystem) { "Built-in categories cannot be archived" }
        dao.reassignTransactions(id, moveTo, clock.millis())
        dao.reassignRules(id, moveTo)
        dao.reassignCorrections(id, moveTo)
        dao.update(existing.copy(archived = true))
    }
}

internal fun CategoryEntity.toModel() = Category(id = id, name = name, kind = kind, color = color, isSystem = isSystem)

data class PeriodSummary(val spent: Money, val income: Money) {
    val net: Money get() = income - spent
}
