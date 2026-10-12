package com.impeesa.finance.core.model

/** What the user is recording. Transfers move money between their own accounts. */
enum class EntryKind { EXPENSE, INCOME, TRANSFER }

data class Account(
    val id: Long,
    val type: AccountType,
    val name: String,
    val mask: String?,
    val color: Int,
    /** Signed balance. Credit cards are negative while money is owed. */
    val balance: Money,
    val creditLimit: Money?,
    val includeInNet: Boolean,
) {
    /** Amount owed on a credit card; zero for other accounts. */
    val owed: Money get() = if (type == AccountType.CARD && balance.isNegative) -balance else Money.ZERO

    val displayName: String get() = if (mask.isNullOrBlank()) name else "$name ••$mask"
}

data class Category(
    val id: Long,
    val name: String,
    val kind: CategoryKind,
    val color: Int,
    /** Built-in categories keep their names: automatic sorting refers to them by name. */
    val isSystem: Boolean = true,
)
