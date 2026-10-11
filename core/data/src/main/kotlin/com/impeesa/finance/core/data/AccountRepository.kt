package com.impeesa.finance.core.data

import com.impeesa.finance.core.data.db.AccountBalanceRow
import com.impeesa.finance.core.data.db.AccountDao
import com.impeesa.finance.core.data.db.AccountEntity
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock

/** What the account form edits. For credit cards, [openingBalance] is the amount owed today. */
data class AccountDraft(
    val id: Long? = null,
    val type: AccountType = AccountType.BANK,
    val name: String = "",
    val mask: String? = null,
    val color: Int = 0xFF2C5BD6.toInt(),
    val openingBalance: Money = Money.ZERO,
    val creditLimit: Money? = null,
    val includeInNet: Boolean = true,
)

class AccountRepository internal constructor(
    private val dao: AccountDao,
    private val clock: Clock,
) {
    fun observeAccounts(): Flow<List<Account>> =
        dao.observeActiveWithBalance().map { rows -> rows.map { it.toModel() } }

    /** Every install has a cash wallet so cash spends always have a home. */
    suspend fun ensureCashWallet() {
        if (dao.cashCount() > 0) return
        val now = clock.millis()
        dao.insert(
            AccountEntity(
                type = AccountType.CASH,
                institution = null,
                name = "Cash",
                mask = null,
                color = 0xFF1F8A84.toInt(),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun draftFor(id: Long): AccountDraft? = dao.getById(id)?.let { entity ->
        AccountDraft(
            id = entity.id,
            type = entity.type,
            name = entity.name,
            mask = entity.mask,
            color = entity.color,
            openingBalance = Money(
                if (entity.type == AccountType.CARD) -entity.openingBalancePaise else entity.openingBalancePaise,
            ),
            creditLimit = entity.creditLimitPaise?.let(::Money),
            includeInNet = entity.includeInNet,
        )
    }

    suspend fun save(draft: AccountDraft): Long {
        require(draft.name.isNotBlank()) { "Account name is required" }
        val now = clock.millis()
        val opening = if (draft.type == AccountType.CARD) -draft.openingBalance.paise else draft.openingBalance.paise
        val mask = draft.mask?.filter(Char::isDigit)?.takeLast(4)?.ifEmpty { null }
        val existing = draft.id?.let { dao.getById(it) }
        return if (existing != null) {
            dao.update(
                existing.copy(
                    type = draft.type,
                    name = draft.name.trim(),
                    mask = mask,
                    color = draft.color,
                    openingBalancePaise = opening,
                    creditLimitPaise = draft.creditLimit?.paise.takeIf { draft.type == AccountType.CARD },
                    includeInNet = draft.includeInNet,
                    updatedAt = now,
                ),
            )
            existing.id
        } else {
            dao.insert(
                AccountEntity(
                    type = draft.type,
                    institution = null,
                    name = draft.name.trim(),
                    mask = mask,
                    color = draft.color,
                    openingBalancePaise = opening,
                    creditLimitPaise = draft.creditLimit?.paise.takeIf { draft.type == AccountType.CARD },
                    includeInNet = draft.includeInNet,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun setArchived(id: Long, archived: Boolean) = dao.setArchived(id, archived, clock.millis())
}

private fun AccountBalanceRow.toModel() = Account(
    id = id,
    type = type,
    name = name,
    mask = mask,
    color = color,
    balance = Money(balancePaise),
    creditLimit = creditLimitPaise?.let(::Money),
    includeInNet = includeInNet,
)
