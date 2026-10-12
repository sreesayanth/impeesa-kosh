package com.impeesa.finance.core.data

import androidx.room.withTransaction
import com.impeesa.finance.core.data.capture.CaptureRepository
import com.impeesa.finance.core.data.db.AlertEntity
import com.impeesa.finance.core.data.db.BalanceSnapshotEntity
import com.impeesa.finance.core.data.db.CardStatementEntity
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.data.db.TransactionRow
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.analytics.Period
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

/** A credit-card bill. */
data class CardStatement(
    val id: Long,
    val accountId: Long,
    val statementDate: LocalDate,
    val dueDate: LocalDate,
    val totalDue: Money?,
    val minDue: Money?,
    val paid: Money,
    val paidAt: Long?,
    val fromSms: Boolean,
) {
    val isPaid: Boolean get() = paidAt != null
    val remaining: Money? get() = totalDue?.let { Money((it.paise - paid.paise).coerceAtLeast(0)) }
}

/** Card and alert settings kept on the account row. */
data class AccountSettings(val type: AccountType, val statementDay: Int?, val dueDay: Int?, val lowBalance: Money?, val creditLimit: Money?)

data class Alert(val id: Long, val kind: String, val title: String, val body: String, val link: String, val createdAt: Long, val read: Boolean)

/** A reconciliation big enough to tell the user about. */
data class BalanceDifference(val snapshotId: Long, val accountId: Long, val difference: Money)

/** Account depth, card statements and alerts (IMPLEMENTATION_PLAN.md §5.7, §5.10, §9.5). */
class LedgerRepository internal constructor(
    private val db: ImpeesaDatabase,
    private val clock: Clock,
) {
    private val dao = db.ledgerDao()

    fun observeSettings(accountId: Long): Flow<AccountSettings?> = dao.observeAccountEntity(accountId).map { e ->
        e?.let { AccountSettings(it.type, it.statementDay, it.dueDay, it.lowBalancePaise?.let(::Money), it.creditLimitPaise?.let(::Money)) }
    }

    fun observeStatements(accountId: Long, limit: Int = 6): Flow<List<CardStatement>> =
        dao.observeStatements(accountId, limit).map { rows -> rows.map { it.toModel() } }

    fun observeTransactions(accountId: Long, limit: Int = 20): Flow<List<TransactionRow>> = dao.observeAccountTransactions(accountId, limit)

    fun observeCycleSpend(accountId: Long, period: Period): Flow<List<CategoryTotal>> =
        dao.observeAccountCategoryTotals(accountId, period.keys.first, period.keys.last).map { rows ->
            rows.filter { it.total != 0L }.map { CategoryTotal(it.categoryId, it.name ?: "Uncategorised", it.color, Money(it.total), it.count) }
        }

    /**
     * "Count cash" / "Set balance": the user's figure becomes a snapshot and wins from now on (§9.5).
     * For cards, [balance] is negative while money is owed.
     */
    suspend fun setBalance(accountId: Long, balance: Money) = db.withTransaction {
        val computed = db.accountDao().balanceOf(accountId) ?: 0L
        dao.insertSnapshot(
            BalanceSnapshotEntity(
                accountId = accountId,
                kind = CaptureRepository.SNAPSHOT_BALANCE,
                balancePaise = balance.paise,
                asOf = clock.millis(),
                source = CaptureRepository.SOURCE_MANUAL,
                diffPaise = balance.paise - computed,
            ),
        )
    }

    suspend fun setLowBalance(accountId: Long, threshold: Money?) = dao.setLowBalance(accountId, threshold?.paise?.takeIf { it > 0 }, clock.millis())

    suspend fun setCardDays(accountId: Long, statementDay: Int?, dueDay: Int?) = dao.setCardDays(accountId, statementDay, dueDay, clock.millis())

    /** A bill entered by hand (from the card app or paper statement). Replaces one with the same due date. */
    suspend fun addStatement(accountId: Long, totalDue: Money, minDue: Money?, dueDate: LocalDate): Long = db.withTransaction {
        val due = DateKey.of(dueDate)
        val existing = dao.statementFor(accountId, due)
        if (existing != null) {
            dao.updateStatement(existing.copy(totalDuePaise = totalDue.paise, minDuePaise = minDue?.paise))
            existing.id
        } else {
            dao.insertStatement(
                CardStatementEntity(
                    accountId = accountId,
                    statementDate = DateKey.of(LocalDate.now(clock)),
                    dueDate = due,
                    totalDuePaise = totalDue.paise,
                    minDuePaise = minDue?.paise,
                    source = CaptureRepository.SOURCE_MANUAL,
                ),
            )
        }
    }

    /** "Mark as paid". Returns the bill as it was, for undo. */
    suspend fun markPaid(statementId: Long): CardStatement? {
        val existing = dao.statement(statementId) ?: return null
        dao.updateStatement(existing.copy(paidPaise = existing.totalDuePaise ?: existing.paidPaise, paidAt = clock.millis()))
        return existing.toModel()
    }

    suspend fun restoreStatement(statement: CardStatement) {
        dao.statement(statement.id)?.let { dao.updateStatement(it.copy(paidPaise = statement.paid.paise, paidAt = statement.paidAt)) }
    }

    suspend fun deleteStatement(id: Long) = dao.deleteStatement(id)

    // ------------------------------------------------------------ alerts

    /** Posts an alert unless one with the same key exists. Returns true when it is new (and should notify). */
    suspend fun postAlert(kind: String, dedupeKey: String, title: String, body: String, link: String): Boolean =
        dao.insertAlert(AlertEntity(kind = kind, title = title, body = body, link = link, createdAt = clock.millis(), dedupeKey = dedupeKey)) != -1L

    fun observeAlerts(): Flow<List<Alert>> = dao.observeAlerts(retentionStart()).map { rows ->
        rows.map { Alert(it.id, it.kind, it.title, it.body, it.link, it.createdAt, it.readAt != null) }
    }

    fun observeUnreadAlerts(): Flow<Int> = dao.observeUnread(retentionStart())

    suspend fun markAllAlertsRead() = dao.markAllRead(clock.millis())

    suspend fun markAlertRead(id: Long) = dao.markRead(id, clock.millis())

    suspend fun purgeOldAlerts() = dao.purgeAlerts(retentionStart())

    // ------------------------------------------------------------ alert inputs

    suspend fun latestStatements(): List<CardStatement> = dao.latestStatements().map { it.toModel() }

    suspend fun recentBalanceDifferences(since: Long, minDifference: Money): List<BalanceDifference> =
        dao.recentDifferences(since, minDifference.paise).map { BalanceDifference(it.id, it.accountId, Money(it.diffPaise)) }

    /** The account's median debit over the last 90 days, for "3× your usual". */
    suspend fun medianDebit(accountId: Long): Money? {
        val amounts = dao.debitAmounts(accountId, clock.millis() - Duration.ofDays(90).toMillis()).sorted()
        if (amounts.size < 5) return null
        return Money(amounts[amounts.size / 2])
    }

    private fun retentionStart(): Long = clock.millis() - Duration.ofDays(ALERT_RETENTION_DAYS).toMillis()

    private fun CardStatementEntity.toModel() = CardStatement(
        id = id,
        accountId = accountId,
        statementDate = DateKey.toLocalDate(statementDate),
        dueDate = DateKey.toLocalDate(dueDate),
        totalDue = totalDuePaise?.let(::Money),
        minDue = minDuePaise?.let(::Money),
        paid = Money(paidPaise),
        paidAt = paidAt,
        fromSms = source == CaptureRepository.SOURCE_SMS,
    )

    private companion object {
        const val ALERT_RETENTION_DAYS = 60L
    }
}
