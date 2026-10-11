package com.impeesa.finance.core.data.capture

import android.content.Context
import androidx.room.withTransaction
import com.impeesa.finance.core.data.db.AccountSuggestionEntity
import com.impeesa.finance.core.data.db.CaptureStatEntity
import com.impeesa.finance.core.data.db.ImpeesaDatabase
import com.impeesa.finance.core.data.db.RawMessageEntity
import com.impeesa.finance.core.data.db.TransactionEntity
import com.impeesa.finance.core.engine.AccountMatch
import com.impeesa.finance.core.engine.Analysis
import com.impeesa.finance.core.engine.DetectionEngine
import com.impeesa.finance.core.engine.EngineContext
import com.impeesa.finance.core.engine.KnownAccount
import com.impeesa.finance.core.engine.MessageEnvelope
import com.impeesa.finance.core.engine.MessageSource
import com.impeesa.finance.core.engine.Route
import com.impeesa.finance.core.engine.SourceHash
import com.impeesa.finance.core.engine.classify.MessageType
import com.impeesa.finance.core.model.AccountType
import com.impeesa.finance.core.model.Channel
import com.impeesa.finance.core.model.DateKey
import com.impeesa.finance.core.model.Direction
import com.impeesa.finance.core.model.Money
import com.impeesa.finance.core.model.TxnSource
import com.impeesa.finance.core.model.TxnStatus
import com.impeesa.finance.core.model.TxnType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

/** What happened to one message. */
sealed interface IngestResult {
    data object Duplicate : IngestResult
    data class Dropped(val type: MessageType) : IngestResult
    data class Info(val type: MessageType) : IngestResult
    data class Logged(
        val transactionId: Long,
        val needsReview: Boolean,
        val amount: Money,
        val direction: Direction,
        val title: String?,
        val senderEntity: String?,
        val mask: String?,
    ) : IngestResult
}

data class ImportProgress(
    val read: Int = 0,
    val total: Int = 0,
    val logged: Int = 0,
    val review: Int = 0,
    val skipped: Int = 0,
    val accounts: Int = 0,
    val done: Boolean = false,
)

data class CaptureStats(val logged: Int, val review: Int, val skipped: Map<String, Int>)

/**
 * Turns incoming messages into transactions (IMPLEMENTATION_PLAN.md §6, §4.2). One writer at a time:
 * a bank SMS and an app notification for the same payment can arrive together, and de-duplication
 * must see the first before the second is written.
 */
class CaptureRepository internal constructor(
    context: Context,
    private val db: ImpeesaDatabase,
    engineProvider: () -> DetectionEngine,
    private val clock: Clock,
) {
    private val engine by lazy(engineProvider)
    private val inbox = SmsInboxReader(context)
    private val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
    private val writer = Mutex()
    private val capture = db.captureDao()
    private val transactions = db.transactionDao()
    private val zone: ZoneId get() = clock.zone

    suspend fun ingest(envelope: MessageEnvelope): IngestResult = writer.withLock { ingestLocked(envelope, loadContext()) }

    /** Reads the inbox since [sinceMillis]; safe to repeat — already-seen messages are skipped. */
    suspend fun importInbox(sinceMillis: Long, onProgress: suspend (ImportProgress) -> Unit = {}): ImportProgress {
        val total = inbox.count(sinceMillis)
        var progress = ImportProgress(total = total)
        val accountsSeen = HashSet<String>()
        onProgress(progress)
        writer.withLock {
            val context = loadContext()
            inbox.forEach(sinceMillis) { envelope ->
                val result = ingestLocked(envelope, context)
                progress = when (result) {
                    is IngestResult.Logged -> {
                        if (result.mask != null) accountsSeen += "${result.senderEntity}:${result.mask}"
                        if (result.needsReview) progress.copy(review = progress.review + 1) else progress.copy(logged = progress.logged + 1)
                    }
                    is IngestResult.Dropped -> progress.copy(skipped = progress.skipped + 1)
                    else -> progress
                }.copy(read = progress.read + 1, accounts = accountsSeen.size)
                if (progress.read % PROGRESS_EVERY == 0) onProgress(progress)
            }
        }
        prefs.edit().putLong(KEY_WATERMARK, clock.millis()).apply()
        return progress.copy(done = true).also { onProgress(it) }
    }

    /** Catches messages that arrived while the app was not running (killed, rebooted, OEM limits). */
    suspend fun reconcileInbox(): ImportProgress {
        val now = clock.millis()
        val watermark = prefs.getLong(KEY_WATERMARK, 0L)
        val since = if (watermark == 0L) now - Duration.ofDays(2).toMillis() else watermark - Duration.ofDays(2).toMillis()
        return importInbox(since)
    }

    fun observeStats(month: Int): Flow<CaptureStats> = capture.observeStats(month).map { rows ->
        CaptureStats(
            logged = rows.filter { it.outcome == Route.AUTO_LOG.name }.sumOf { it.count },
            review = rows.filter { it.outcome == Route.REVIEW.name }.sumOf { it.count },
            skipped = rows.filter { it.outcome.startsWith("DROP:") }.groupBy { it.outcome.removePrefix("DROP:") }.mapValues { (_, v) -> v.sumOf { it.count } },
        )
    }

    private suspend fun loadContext(): EngineContext {
        val identifiers = capture.identifiers().groupBy { it.accountId }
        val accounts = db.accountDao().activeAccounts().map { account ->
            KnownAccount(
                id = account.id,
                mask = account.mask,
                senderCodes = identifiers[account.id].orEmpty().map { it.senderCode }.toSet(),
                isCard = account.type == AccountType.CARD,
            )
        }
        return EngineContext(accounts = accounts, zone = zone)
    }

    private suspend fun ingestLocked(envelope: MessageEnvelope, context: EngineContext): IngestResult = db.withTransaction {
        val hash = SourceHash.of(envelope, zone)
        if (capture.rawIdByHash(hash) != null) return@withTransaction IngestResult.Duplicate

        val analysis = engine.analyze(envelope, context)
        bumpStat(envelope, analysis)
        if (analysis.route == Route.DROP) return@withTransaction IngestResult.Dropped(analysis.type)

        val rawId = capture.insertRaw(
            RawMessageEntity(
                source = envelope.source.name,
                sender = envelope.sender,
                title = envelope.title,
                body = envelope.body,
                receivedAt = envelope.receivedAt.toEpochMilli(),
                sourceHash = hash,
                parserVersion = PARSER_VERSION,
                messageType = analysis.type.name,
                route = analysis.route.name,
            ),
        )
        if (analysis.route == Route.INFO) return@withTransaction IngestResult.Info(analysis.type)

        val amount = analysis.amount ?: return@withTransaction IngestResult.Info(analysis.type)
        val direction = analysis.direction ?: Direction.DEBIT
        analysis.reference?.let { ref ->
            transactions.findByReference(ref, amount.paise, direction.name)?.let { existing ->
                capture.linkRaw(rawId, existing)
                return@withTransaction IngestResult.Duplicate
            }
        }

        val accountId = when (val match = analysis.account) {
            is AccountMatch.Matched -> match.accountId
            is AccountMatch.Suggested -> {
                rememberSuggestion(match, analysis)
                null
            }
            AccountMatch.Unknown -> null
        }
        val txnId = insertTransaction(envelope, analysis, amount, direction, accountId, rawId)
        capture.linkRaw(rawId, txnId)
        IngestResult.Logged(
            transactionId = txnId,
            needsReview = analysis.route == Route.REVIEW,
            amount = amount,
            direction = direction,
            title = analysis.merchant?.displayName,
            senderEntity = analysis.senderEntity,
            mask = analysis.mask,
        )
    }

    private suspend fun insertTransaction(
        envelope: MessageEnvelope,
        analysis: Analysis,
        amount: Money,
        direction: Direction,
        accountId: Long?,
        rawId: Long,
    ): Long {
        val receivedLocal = envelope.receivedAt.atZone(zone)
        val occurred = analysis.date
            ?.takeIf { it != receivedLocal.toLocalDate() }
            ?.atTime(receivedLocal.toLocalTime())
            ?.atZone(zone)
            ?: receivedLocal
        val now = clock.millis()
        val categories = db.categoryDao()
        val isAtm = analysis.channel == Channel.ATM && direction == Direction.DEBIT
        val type = when {
            isAtm -> TxnType.TRANSFER
            analysis.type == MessageType.REFUND -> TxnType.REFUND
            direction == Direction.CREDIT -> TxnType.INCOME
            else -> TxnType.EXPENSE
        }
        val categoryName = when {
            isAtm -> "Cash withdrawal"
            type == TxnType.REFUND -> "Refunds"
            type == TxnType.EXPENSE -> analysis.merchant?.categoryName
            else -> null
        }
        val foreign = analysis.currency != "INR"
        val base = TransactionEntity(
            accountId = accountId,
            amountPaise = amount.paise,
            currency = analysis.currency,
            direction = direction,
            type = type,
            categoryId = categoryName?.let { categories.idByName(it) },
            categorySource = if (categoryName != null) "DICTIONARY" else null,
            counterparty = analysis.merchant?.displayName ?: analysis.counterparty,
            occurredAt = occurred.toInstant().toEpochMilli(),
            localDate = DateKey.of(occurred.toLocalDate()),
            channel = analysis.channel,
            reference = analysis.reference,
            status = if (analysis.route == Route.REVIEW || foreign) TxnStatus.PENDING_REVIEW else TxnStatus.CONFIRMED,
            source = if (envelope.source == MessageSource.SMS) TxnSource.SMS else TxnSource.NOTIFICATION,
            confidence = analysis.confidence.toFloat(),
            excludedFromSpending = isAtm,
            createdAt = now,
            updatedAt = now,
            reviewReason = analysis.reviewReason?.name,
            rawMessageId = rawId,
            merchantKey = analysis.merchant?.key,
            senderCode = analysis.senderEntity,
            accountMask = analysis.mask,
        )
        if (!isAtm) return transactions.insert(base)

        // ATM withdrawal: money moves from the bank to the cash wallet; it is not spending.
        val group = UUID.randomUUID().toString()
        val outId = transactions.insert(base.copy(transferGroupId = group, counterparty = "ATM withdrawal"))
        db.accountDao().cashAccountId()?.let { cash ->
            transactions.insert(
                base.copy(
                    accountId = cash,
                    direction = Direction.CREDIT,
                    transferGroupId = group,
                    counterparty = "ATM withdrawal",
                    senderCode = null,
                    accountMask = null,
                    rawMessageId = null,
                ),
            )
        }
        return outId
    }

    private suspend fun rememberSuggestion(match: AccountMatch.Suggested, analysis: Analysis) {
        val sender = match.senderEntity ?: return
        val now = clock.millis()
        val existing = capture.suggestion(sender, match.mask)
        if (existing == null) {
            capture.insertSuggestion(
                AccountSuggestionEntity(
                    senderCode = sender,
                    mask = match.mask,
                    institution = analysis.senderEntity,
                    looksLikeCard = analysis.channel == Channel.CARD,
                    sightings = 1,
                    firstSeen = now,
                    lastSeen = now,
                ),
            )
        } else {
            capture.updateSuggestion(
                existing.copy(
                    sightings = existing.sightings + 1,
                    lastSeen = now,
                    looksLikeCard = existing.looksLikeCard || analysis.channel == Channel.CARD,
                ),
            )
        }
    }

    private suspend fun bumpStat(envelope: MessageEnvelope, analysis: Analysis) {
        val local = envelope.receivedAt.atZone(zone)
        val month = local.year * 100 + local.monthValue
        val outcome = if (analysis.route == Route.DROP) "DROP:${analysis.type.name}" else analysis.route.name
        if (capture.bumpStat(month, envelope.source.name, outcome) == 0) {
            capture.insertStat(CaptureStatEntity(month, envelope.source.name, outcome, 1))
        }
    }

    private companion object {
        const val KEY_WATERMARK = "inbox_watermark"
        const val PARSER_VERSION = 1
        const val PROGRESS_EVERY = 50
    }
}
