package com.impeesa.finance.core.data

import com.impeesa.finance.core.data.db.CategoryEntity
import com.impeesa.finance.core.model.CategoryKind

/** The starting taxonomy from IMPLEMENTATION_PLAN.md §7.6. Colours match the design tokens. */
internal object DefaultCategories {
    private data class Seed(val name: String, val kind: CategoryKind, val color: Long, val icon: String, val spending: Boolean)

    private val seeds = listOf(
        Seed("Food & dining", CategoryKind.EXPENSE, 0xFFFF9F5A, "food", true),
        Seed("Groceries", CategoryKind.EXPENSE, 0xFF3FD0C9, "cart", true),
        Seed("Travel", CategoryKind.EXPENSE, 0xFF5CC8F5, "car", true),
        Seed("Shopping", CategoryKind.EXPENSE, 0xFFA493FF, "bag", true),
        Seed("Bills & utilities", CategoryKind.EXPENSE, 0xFFF5C94C, "bolt", true),
        Seed("Rent & housing", CategoryKind.EXPENSE, 0xFFC9A27A, "home", true),
        Seed("Entertainment", CategoryKind.EXPENSE, 0xFFF27BB5, "film", true),
        Seed("Health", CategoryKind.EXPENSE, 0xFF7FB2FF, "health", true),
        Seed("Education", CategoryKind.EXPENSE, 0xFFE0B84F, "book", true),
        Seed("Personal care", CategoryKind.EXPENSE, 0xFFE98AC9, "care", true),
        Seed("Gifts & donations", CategoryKind.EXPENSE, 0xFFB79CFF, "gift", true),
        Seed("Fees & charges", CategoryKind.EXPENSE, 0xFFB0B5BD, "fee", true),
        Seed("EMI & loans", CategoryKind.EXPENSE, 0xFFD9896B, "loan", true),
        Seed("Taxes", CategoryKind.EXPENSE, 0xFFA8AEB8, "tax", true),
        Seed("Other", CategoryKind.EXPENSE, 0xFF9AA0AA, "other", true),
        Seed("Salary", CategoryKind.INCOME, 0xFFC5F26B, "salary", false),
        Seed("Business", CategoryKind.INCOME, 0xFFA9DB5A, "business", false),
        Seed("Interest", CategoryKind.INCOME, 0xFFF5C94C, "interest", false),
        Seed("Refunds", CategoryKind.INCOME, 0xFF3FD0C9, "refund", false),
        Seed("Cashback & rewards", CategoryKind.INCOME, 0xFFF5C94C, "reward", false),
        Seed("Gifts received", CategoryKind.INCOME, 0xFFF27BB5, "gift", false),
        Seed("Other income", CategoryKind.INCOME, 0xFF9AA0AA, "other", false),
        Seed("Transfers", CategoryKind.TRANSFER, 0xFF9AA0AA, "transfer", false),
        Seed("Card payments", CategoryKind.TRANSFER, 0xFF9AA0AA, "card", false),
        Seed("Cash withdrawal", CategoryKind.TRANSFER, 0xFF1F8A84, "cash", false),
        Seed("Wallet top-up", CategoryKind.TRANSFER, 0xFF9AA0AA, "wallet", false),
        Seed("Investments", CategoryKind.TRANSFER, 0xFF7FB2FF, "invest", false),
        Seed("Lent money", CategoryKind.TRANSFER, 0xFF5CC8F5, "people", false),
    )

    fun all(): List<CategoryEntity> = seeds.mapIndexed { index, seed ->
        CategoryEntity(
            name = seed.name,
            kind = seed.kind,
            color = seed.color.toInt(),
            icon = seed.icon,
            countsAsSpending = seed.spending,
            sortOrder = index,
        )
    }
}
