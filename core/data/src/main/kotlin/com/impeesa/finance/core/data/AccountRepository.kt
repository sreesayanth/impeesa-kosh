package com.impeesa.finance.core.data

import androidx.room.withTransaction
import com.impeesa.finance.core.data.db.AccountBalanceRow
import com.impeesa.finance.core.data.db.AccountDao
import com.impeesa.finance.core.data.db.AccountEntity
import com.impeesa.finance.core.data.db.AccountIdentifierEntity
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.engine.registry.InstitutionKind
import com.impeesa.finance.core.engine.registry.SenderRegistry
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

/** An account seen in messages, offered to the user before it is added. */
data class AccountSuggestion(
    val id: Long,
    val institution: String,
    val mask: String,
    val looksLikeCard: Boolean,
    val sightings: Int,
)

class AccountRepository internal constructor(
    private val dao: AccountDao,
    private val clock: Clock,
    private val db: ImpeesaDatabase? = null,
) {
    private val senders by lazy { SenderRegistry.load() }

    fun observeSuggestions(): Flow<List<AccountSuggestion>> =
        requireNotNull(db).captureDao().observeSuggestions().map { rows ->
            rows.map {
                val institution = senders.find(it.senderCode)
                AccountSuggestion(
                    id = it.id,
                    institution = institution?.institution ?: it.senderCode,
                    mask = it.mask,
                    looksLikeCard = it.looksLikeCard || institution?.kind == InstitutionKind.CARD,
                    sightings = it.sightings,
                )
            }
        }

    /** Adds a suggested account, links its SMS identity and claims the transactions already captured for it. */
    suspend fun acceptSuggestion(id: Long): Long? {
        val database = requireNotNull(db)
        val capture = database.captureDao()
        return database.withTransaction {
            val suggestion = capture.suggestionById(id) ?: return@withTransaction null
            val institution = senders.find(suggestion.senderCode)
            val isCard = suggestion.looksLikeCard || institution?.kind == InstitutionKind.CARD
            val now = clock.millis()
            val accountId = dao.insert(
                AccountEntity(
                    type = if (isCard) AccountType.CARD else AccountType.BANK,
                    institution = institution?.institution,
                    name = institution?.institution ?: suggestion.senderCode,
                    mask = suggestion.mask,
                    color = colorFor(suggestion.senderCode),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            capture.insertIdentifier(AccountIdentifierEntity(accountId = accountId, senderCode = suggestion.senderCode, mask = suggestion.mask))
            database.transactionDao().backfillAccount(accountId, suggestion.senderCode, suggestion.mask)
            capture.deleteSuggestion(id)
            accountId
        }
    }

    suspend fun dismissSuggestion(id: Long) {
        val capture = requireNotNull(db).captureDao()
        capture.suggestionById(id)?.let { capture.updateSuggestion(it.copy(dismissed = true)) }
    }

    private fun colorFor(sender: String): Int {
        val palette = listOf(0xFF2C5BD6, 0xFF1673B5, 0xFFB4462B, 0xFF8E2453, 0xFFC2283A, 0xFF5F259F)
        return palette[Math.floorMod(sender.hashCode(), palette.size)].toInt()
    }
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
            val newId = dao.insert(
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
            if (mask != null) db?.transactionDao()?.backfillByMask(newId, mask)
            newId
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
