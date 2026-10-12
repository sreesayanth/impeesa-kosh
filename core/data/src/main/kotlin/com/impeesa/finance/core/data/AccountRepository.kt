package com.impeesa.finance.core.data

import androidx.room.withTransaction
import com.impeesa.finance.core.data.db.AccountBalanceRow
import com.impeesa.finance.core.data.db.AccountDao
import com.impeesa.finance.core.data.db.AccountEntity
import com.impeesa.finance.core.data.db.AccountIdentifierEntity
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.engine.account.AccountResolver
import com.impeesa.finance.core.engine.registry.InstitutionKind
import com.impeesa.finance.core.engine.registry.SenderRegistry
import com.impeesa.finance.core.model.Account
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    /** Cards: day of the month the statement is generated and the bill is due (1–31). */
    val statementDay: Int? = null,
    val dueDay: Int? = null,
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
    /** Picks up balances and bills from messages that arrived before an account was added. */
    private val replayLedger: suspend (accountId: Long) -> Unit = {},
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
        val added = database.withTransaction {
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
                    color = colorFor(institution?.institution ?: suggestion.senderCode),
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            // The bank's other sender headers (HDFCBK, HDFCBN…) are the same account.
            val sameBank = senders.codesOf(suggestion.senderCode)
            sameBank.forEach { code ->
                capture.insertIdentifier(AccountIdentifierEntity(accountId = accountId, senderCode = code, mask = suggestion.mask))
                database.transactionDao().backfillAccount(accountId, code, suggestion.mask)
            }
            capture.allSuggestions()
                .filter { it.id == id || (it.senderCode in sameBank && AccountResolver.masksMatch(it.mask, suggestion.mask)) }
                .forEach { capture.deleteSuggestion(it.id) }
            accountId
        }
        added?.let { replayLedger(it) }
        return added
    }

    /**
     * Folds accounts that are really one into the oldest of them: same type, same bank and the same
     * last digits. Earlier versions made one per bank sender header (HDFCBK and HDFCBN). Returns how
     * many were merged away.
     */
    suspend fun mergeDuplicates(): Int {
        val database = requireNotNull(db)
        return database.withTransaction {
            val kept = mutableListOf<AccountEntity>()
            var merged = 0
            for (account in dao.activeAccounts().sortedBy { it.id }) {
                val keeper = kept.firstOrNull { sameAccount(it, account) }
                if (keeper == null) {
                    kept += account
                } else {
                    mergeLocked(account, keeper)
                    merged++
                }
            }
            merged
        }
    }

    /** Moves everything of [fromId] onto [intoId] and removes [fromId]. For accounts the user knows are the same. */
    suspend fun merge(fromId: Long, intoId: Long) {
        require(fromId != intoId) { "Pick a different account" }
        requireNotNull(db).withTransaction {
            val from = dao.getById(fromId) ?: return@withTransaction
            val into = dao.getById(intoId) ?: return@withTransaction
            mergeLocked(from, into)
        }
    }

    /** Active accounts [account] could be merged into: same type, not itself. */
    suspend fun mergeTargets(account: Long): List<Account> {
        val type = dao.getById(account)?.type ?: return emptyList()
        return observeAccounts().first().filter { it.id != account && it.type == type }
    }

    private fun sameAccount(a: AccountEntity, b: AccountEntity): Boolean {
        if (a.type != b.type || a.type == AccountType.CASH) return false
        val maskA = a.mask ?: return false
        val maskB = b.mask ?: return false
        val bankA = (a.institution ?: a.name).trim().lowercase()
        val bankB = (b.institution ?: b.name).trim().lowercase()
        return bankA == bankB && AccountResolver.masksMatch(maskA, maskB)
    }

    private suspend fun mergeLocked(from: AccountEntity, into: AccountEntity) {
        dao.moveTransactions(from.id, into.id)
        dao.moveIdentifiers(from.id, into.id)
        dao.moveRules(from.id, into.id)
        dao.moveSnapshots(from.id, into.id)
        dao.moveStatements(from.id, into.id)
        dao.deleteStatementsOf(from.id)
        // Keep what the user set on either.
        dao.update(
            into.copy(
                openingBalancePaise = into.openingBalancePaise.takeIf { it != 0L } ?: from.openingBalancePaise,
                creditLimitPaise = into.creditLimitPaise ?: from.creditLimitPaise,
                statementDay = into.statementDay ?: from.statementDay,
                dueDay = into.dueDay ?: from.dueDay,
                lowBalancePaise = into.lowBalancePaise ?: from.lowBalancePaise,
                institution = into.institution ?: from.institution,
                updatedAt = clock.millis(),
            ),
        )
        dao.delete(from.id)
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
            statementDay = entity.statementDay,
            dueDay = entity.dueDay,
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
                    statementDay = draft.statementDay.takeIf { draft.type == AccountType.CARD },
                    dueDay = draft.dueDay.takeIf { draft.type == AccountType.CARD },
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
                    statementDay = draft.statementDay.takeIf { draft.type == AccountType.CARD },
                    dueDay = draft.dueDay.takeIf { draft.type == AccountType.CARD },
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
    syncedAt = syncedAt,
    syncedFromBank = syncSource == "SMS",
    lowBalance = lowBalancePaise?.let(::Money),
)
