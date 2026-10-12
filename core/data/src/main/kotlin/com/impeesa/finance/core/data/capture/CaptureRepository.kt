package com.impeesa.finance.core.data.capture

import android.content.Context
import androidx.room.withTransaction
import com.impeesa.finance.core.data.CategoryResolver
import com.impeesa.finance.core.data.db.AccountSuggestionEntity
import com.impeesa.finance.core.data.db.BalanceSnapshotEntity
import com.impeesa.finance.core.data.db.CardStatementEntity
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
import com.impeesa.finance.core.engine.MovementKind
import com.impeesa.finance.core.engine.Route
import com.impeesa.finance.core.engine.SourceHash
import com.impeesa.finance.core.engine.TransferHints
import com.impeesa.finance.core.engine.account.AccountResolver
import com.impeesa.finance.core.engine.category.CategoryInput
import com.impeesa.finance.core.engine.category.CategorySource
import com.impeesa.finance.core.engine.category.RuleAction
import com.impeesa.finance.core.engine.classify.MessageType
import com.impeesa.finance.core.engine.merchant.MerchantSource
import com.impeesa.finance.core.engine.registry.AppKind
import com.impeesa.finance.core.engine.registry.AppRegistry
import com.impeesa.finance.core.engine.registry.PaymentApp
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** What happened to one message. */
sealed interface IngestResult {
    data object Duplicate : IngestResult

    /** Same payment already captured from another source; it was enriched instead of duplicated. */
    data class Merged(val transactionId: Long) : IngestResult
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

/** A payment app and whether Impeesa reads its notifications. */
data class WatchedApp(val app: PaymentApp, val enabled: Boolean)

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
    private val apps by lazy { AppRegistry.load() }
    private val inbox = SmsInboxReader(context)
    private val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
    private val writer = Mutex()
    private val capture = db.captureDao()
    private val transactions = db.transactionDao()
    private val resolver = CategoryResolver(db, clock)
    private val zone: ZoneId get() = clock.zone

    // ---------------------------------------------------------------- settings

    /** Payment apps Impeesa can read, with the user's on/off choice. Messaging apps (RCS) are off by default. */
    fun watchedApps(): List<WatchedApp> = apps.all.map { WatchedApp(it, isEnabled(it)) }

    fun isWatched(packageName: String): Boolean = apps.find(packageName)?.let(::isEnabled) ?: false

    fun setAppEnabled(packageName: String, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_PREFIX + packageName, enabled).apply()
    }

    private fun isEnabled(app: PaymentApp): Boolean =
        prefs.getBoolean(KEY_APP_PREFIX + app.packageName, app.kind != AppKind.MESSAGING)

    /**
     * How long original message texts are kept (IMPLEMENTATION_PLAN.md §9.7): days, [KEEP_FOREVER] or
     * [KEEP_NEVER]. Extracted fields always stay on the transaction.
     */
    /** The user's currency (ISO code); captures in any other go to Review. */
    var homeCurrency: String
        get() = prefs.getString(KEY_HOME_CURRENCY, "INR") ?: "INR"
        set(value) = prefs.edit().putString(KEY_HOME_CURRENCY, value).apply()

    var messageRetentionDays: Int
        get() = prefs.getInt(KEY_RETENTION, DEFAULT_RETENTION_DAYS)
        set(value) = prefs.edit().putInt(KEY_RETENTION, value).apply()

    /** Clears expired message texts and removes transactions deleted more than 30 days ago. */
    suspend fun purgeExpired(): Int = writer.withLock {
        val now = clock.millis()
        db.withTransaction {
            val retention = messageRetentionDays
            val cleared = when (retention) {
                KEEP_FOREVER -> 0
                KEEP_NEVER -> capture.clearBodiesBefore(Long.MAX_VALUE)
                else -> capture.clearBodiesBefore(now - Duration.ofDays(retention.toLong()).toMillis())
            }
            capture.purgeDeletedTransactions(now - Duration.ofDays(DELETED_KEEP_DAYS).toMillis())
            cleared
        }
    }

    // ---------------------------------------------------------------- entry points

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

    /**
     * Undoes an automatic merge: the message becomes its own transaction again
     * ("not the same payment"). Returns the new transaction id.
     */
    suspend fun unmerge(rawId: Long): Long? = writer.withLock {
        db.withTransaction {
            val raw = capture.raw(rawId) ?: return@withTransaction null
            if (raw.route != ROUTE_MERGED || raw.body == null) return@withTransaction null
            val envelope = MessageEnvelope(
                source = MessageSource.valueOf(raw.source),
                sender = raw.sender,
                body = raw.body,
                receivedAt = Instant.ofEpochMilli(raw.receivedAt),
                title = raw.title,
            )
            val context = loadContext()
            val analysis = engine.analyze(envelope, context)
            val amount = analysis.amount ?: return@withTransaction null
            val accountId = (analysis.account as? AccountMatch.Matched)?.accountId
            val id = insertTransaction(envelope, analysis, amount, analysis.direction ?: Direction.DEBIT, accountId, rawId, context, duplicateOf = null)
            capture.relinkRaw(rawId, id, analysis.route.name)
            id
        }
    }

    fun observeStats(month: Int): Flow<CaptureStats> = capture.observeStats(month).map { rows ->
        CaptureStats(
            logged = rows.filter { it.outcome == Route.AUTO_LOG.name }.sumOf { it.count },
            review = rows.filter { it.outcome == Route.REVIEW.name }.sumOf { it.count },
            skipped = rows.filter { it.outcome.startsWith("DROP:") }.groupBy { it.outcome.removePrefix("DROP:") }.mapValues { (_, v) -> v.sumOf { it.count } },
        )
    }

    // ---------------------------------------------------------------- pipeline

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
        val disabledApps = apps.all.filterNot(::isEnabled).map { it.packageName }.toSet()
        return EngineContext(accounts = accounts, blockedSenders = disabledApps, zone = zone, homeCurrency = homeCurrency)
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
                // With retention set to never, the text isn't stored at all.
                body = envelope.body.takeIf { messageRetentionDays != KEEP_NEVER },
                receivedAt = envelope.receivedAt.toEpochMilli(),
                sourceHash = hash,
                parserVersion = PARSER_VERSION,
                messageType = analysis.type.name,
                route = analysis.route.name,
            ),
        )
        val matchedAccount = (analysis.account as? AccountMatch.Matched)?.accountId
        if (analysis.route == Route.INFO) {
            matchedAccount?.let { recordLedger(it, analysis, envelope, rawId, context) }
            return@withTransaction IngestResult.Info(analysis.type)
        }

        val amount = analysis.amount ?: return@withTransaction IngestResult.Info(analysis.type)
        val direction = analysis.direction ?: Direction.DEBIT
        val accountId = when (val match = analysis.account) {
            is AccountMatch.Matched -> match.accountId
            is AccountMatch.Suggested -> {
                rememberSuggestion(match, analysis)
                null
            }
            AccountMatch.Unknown -> null
        }
        val occurred = occurredAt(envelope, analysis)

        // 1. Exact: same bank reference.
        analysis.reference?.let { ref ->
            transactions.findByReference(ref, amount.paise, direction.name)?.let { existingId ->
                transactions.getById(existingId)?.let { mergeInto(it, analysis, envelope, accountId, rawId) }
                accountId?.let { recordLedger(it, analysis, envelope, rawId, context) }
                return@withTransaction IngestResult.Merged(existingId)
            }
        }

        // 2. Same payment seen by the other source (bank SMS vs payment-app notification).
        var duplicateOf: Long? = null
        val window = Duration.ofMinutes(DUPLICATE_WINDOW_MINUTES).toMillis()
        val around = occurred.toInstant().toEpochMilli()
        val candidates = transactions.crossSourceCandidates(
            amountPaise = amount.paise,
            direction = direction.name,
            source = txnSource(envelope).name,
            fromMillis = around - window,
            toMillis = around + window,
            aroundMillis = around,
        ).filter { accountsCompatible(it, accountId, analysis.mask) }
        if (candidates.isNotEmpty()) {
            val best = candidates.first()
            if (candidates.size == 1 && !merchantsConflict(best, analysis)) {
                mergeInto(best, analysis, envelope, accountId, rawId)
                accountId?.let { recordLedger(it, analysis, envelope, rawId, context) }
                return@withTransaction IngestResult.Merged(best.id)
            }
            duplicateOf = best.id
        }

        val txnId = insertTransaction(envelope, analysis, amount, direction, accountId, rawId, context, duplicateOf)
        capture.linkRaw(rawId, txnId)
        val saved = transactions.getById(txnId)
        if (accountId != null) {
            if (saved != null && direction == Direction.CREDIT && saved.type != TxnType.REFUND && context.accounts.any { it.id == accountId && it.isCard }) {
                applyCardPayment(accountId, amount)
            }
            recordLedger(accountId, analysis, envelope, rawId, context)
        }
        IngestResult.Logged(
            transactionId = txnId,
            // Rules and ambiguous merchants can also send a capture to review (Phase 5).
            needsReview = saved?.status == TxnStatus.PENDING_REVIEW,
            amount = amount,
            direction = direction,
            title = analysis.merchant?.displayName,
            senderEntity = analysis.senderEntity,
            mask = analysis.mask,
        )
    }

    private fun occurredAt(envelope: MessageEnvelope, analysis: Analysis): ZonedDateTime {
        val receivedLocal = envelope.receivedAt.atZone(zone)
        return analysis.date
            ?.takeIf { it != receivedLocal.toLocalDate() }
            ?.atTime(receivedLocal.toLocalTime())
            ?.atZone(zone)
            ?: receivedLocal
    }

    private fun categoryInput(envelope: MessageEnvelope, analysis: Analysis, accountId: Long?, amount: Money, direction: Direction) = CategoryInput(
        merchantKey = analysis.merchant?.key,
        // The raw payee keeps the UPI ID that VPA rules match on.
        counterparty = analysis.counterparty ?: analysis.merchant?.displayName,
        text = listOfNotNull(envelope.title, envelope.body).joinToString("\n"),
        accountId = accountId,
        amountPaise = amount.paise,
        isDebit = direction == Direction.DEBIT,
        dictionaryCategory = analysis.merchant?.categoryName,
    )

    private fun txnSource(envelope: MessageEnvelope) =
        if (envelope.source == MessageSource.SMS) TxnSource.SMS else TxnSource.NOTIFICATION

    private fun accountsCompatible(existing: TransactionEntity, accountId: Long?, mask: String?): Boolean {
        if (existing.accountId != null && accountId != null && existing.accountId != accountId) return false
        val a = existing.accountMask
        return a == null || mask == null || AccountResolver.masksMatch(a, mask)
    }

    /** Two different known businesses means two different payments. */
    private fun merchantsConflict(existing: TransactionEntity, analysis: Analysis): Boolean {
        val newMerchant = analysis.merchant ?: return false
        if (newMerchant.source != MerchantSource.DICTIONARY || existing.categorySource != "DICTIONARY") return false
        return existing.merchantKey != null && existing.merchantKey != newMerchant.key
    }

    /**
     * Adds what the second source knows to the first: the bank SMS knows the account and reference,
     * the payment app knows the clean merchant name. User edits are never overwritten.
     */
    private suspend fun mergeInto(existing: TransactionEntity, analysis: Analysis, envelope: MessageEnvelope, accountId: Long?, rawId: Long) {
        var merged = existing
        if (merged.accountId == null && accountId != null) merged = merged.copy(accountId = accountId)
        if (merged.senderCode == null) merged = merged.copy(senderCode = analysis.senderEntity)
        if (merged.accountMask == null) merged = merged.copy(accountMask = analysis.mask)
        if (merged.reference == null) merged = merged.copy(reference = analysis.reference)

        val merchant = analysis.merchant
        val editable = merged.userEditedFields == 0 && (merged.type == TxnType.EXPENSE || merged.type == TxnType.INCOME)
        if (merchant != null && editable) {
            val cleaner = merged.counterparty == null ||
                (merchant.source == MerchantSource.DICTIONARY && merged.categorySource != "DICTIONARY") ||
                (envelope.source == MessageSource.NOTIFICATION && merchant.source == MerchantSource.DICTIONARY)
            if (cleaner) merged = merged.copy(counterparty = merchant.displayName, merchantKey = merchant.key)
            // A clearer merchant can mean a better category, but never over the user's own rules, learning or choice.
            if (merged.type == TxnType.EXPENSE && merged.categorySource in REPLACEABLE_SOURCES && (merged.categoryId == null || cleaner)) {
                val input = categoryInput(envelope, analysis, merged.accountId, Money(merged.amountPaise), merged.direction)
                    .copy(merchantKey = merged.merchantKey)
                val decision = resolver.decide(input, resolver.rules())
                val id = resolver.categoryIdFor(decision)
                if (decision.action == RuleAction.SET_CATEGORY && !decision.ask && id != null) {
                    resolver.recordMatch(decision.ruleId)
                    merged = merged.copy(categoryId = id, categorySource = decision.source?.name, categoryReason = decision.reason)
                }
            }
        }
        // Two independent sources agree: no need to ask the user about a low-confidence capture.
        if (merged.status == TxnStatus.PENDING_REVIEW && merged.reviewReason in AGREEMENT_RESOLVES && analysis.route == Route.AUTO_LOG) {
            merged = merged.copy(status = TxnStatus.CONFIRMED, reviewReason = null)
        }
        transactions.update(merged.copy(updatedAt = clock.millis()))
        capture.relinkRaw(rawId, existing.id, ROUTE_MERGED)
    }

    private suspend fun insertTransaction(
        envelope: MessageEnvelope,
        analysis: Analysis,
        amount: Money,
        direction: Direction,
        accountId: Long?,
        rawId: Long,
        context: EngineContext,
        duplicateOf: Long?,
    ): Long {
        val occurred = occurredAt(envelope, analysis)
        val now = clock.millis()
        val categories = db.categoryDao()
        val accountIsCard = context.accounts.any { it.id == accountId && it.isCard }
        val movement = TransferHints.classify(envelope, analysis, accountIsCard)
        val isTransfer = movement == MovementKind.ATM_WITHDRAWAL || movement == MovementKind.CARD_PAYMENT || movement == MovementKind.WALLET_TOPUP
        var type = when {
            isTransfer -> TxnType.TRANSFER
            analysis.type == MessageType.REFUND -> TxnType.REFUND
            direction == Direction.CREDIT -> TxnType.INCOME
            else -> TxnType.EXPENSE
        }
        val fixedCategory = when (movement) {
            MovementKind.ATM_WITHDRAWAL -> "Cash withdrawal"
            MovementKind.CARD_PAYMENT -> "Card payments"
            MovementKind.WALLET_TOPUP -> "Wallet top-up"
            else -> if (type == TxnType.REFUND) "Refunds" else null
        }
        var categoryId: Long? = fixedCategory?.let { categories.idByName(it) }
        var categorySource: String? = if (fixedCategory != null) CategorySource.DICTIONARY.name else null
        var categoryReason: String? = null
        var excluded = isTransfer
        var askReason: String? = null
        if (fixedCategory == null && (type == TxnType.EXPENSE || type == TxnType.INCOME)) {
            val decision = resolver.decide(categoryInput(envelope, analysis, accountId, amount, direction), resolver.rules())
            resolver.recordMatch(decision.ruleId)
            categorySource = decision.source?.name
            categoryReason = decision.reason
            when (decision.action) {
                RuleAction.MARK_TRANSFER -> {
                    type = TxnType.TRANSFER
                    excluded = true
                    categoryId = categories.idByName("Transfers")
                }
                RuleAction.EXCLUDE -> excluded = true
                RuleAction.SET_CATEGORY, RuleAction.ASK -> categoryId = resolver.categoryIdFor(decision)
            }
            if (decision.ask) askReason = if (decision.source == CategorySource.RULE) REASON_ASK_CATEGORY else REASON_AMBIGUOUS_CATEGORY
        }
        val foreign = analysis.currency != homeCurrency
        val needsReview = analysis.route == Route.REVIEW || foreign || duplicateOf != null || askReason != null
        val base = TransactionEntity(
            accountId = accountId,
            amountPaise = amount.paise,
            currency = analysis.currency,
            direction = direction,
            type = type,
            categoryId = categoryId,
            categorySource = categorySource,
            categoryReason = categoryReason,
            counterparty = when (movement) {
                MovementKind.ATM_WITHDRAWAL -> "ATM withdrawal"
                MovementKind.CARD_PAYMENT -> "Credit card payment"
                else -> analysis.merchant?.displayName ?: analysis.counterparty
            },
            occurredAt = occurred.toInstant().toEpochMilli(),
            localDate = DateKey.of(occurred.toLocalDate()),
            channel = analysis.channel,
            reference = analysis.reference,
            status = if (needsReview) TxnStatus.PENDING_REVIEW else TxnStatus.CONFIRMED,
            source = txnSource(envelope),
            confidence = analysis.confidence.toFloat(),
            excludedFromSpending = excluded,
            createdAt = now,
            updatedAt = now,
            reviewReason = when {
                duplicateOf != null -> REASON_POSSIBLE_DUPLICATE
                else -> analysis.reviewReason?.name ?: askReason
            },
            rawMessageId = rawId,
            merchantKey = analysis.merchant?.key,
            senderCode = analysis.senderEntity,
            accountMask = analysis.mask,
            duplicateOf = duplicateOf,
        )

        if (movement == MovementKind.ATM_WITHDRAWAL) {
            // Money moves from the bank to the cash wallet; it is not spending.
            val group = UUID.randomUUID().toString()
            val outId = transactions.insert(base.copy(transferGroupId = group))
            db.accountDao().cashAccountId()?.let { cash ->
                transactions.insert(
                    base.copy(accountId = cash, direction = Direction.CREDIT, transferGroupId = group, senderCode = null, accountMask = null, rawMessageId = null),
                )
            }
            return outId
        }

        val id = transactions.insert(base)
        if (duplicateOf == null) link(base.copy(id = id), movement)
        return id
    }

    // ---------------------------------------------------------------- linking (§6.10)

    private suspend fun link(txn: TransactionEntity, movement: MovementKind) {
        when {
            movement == MovementKind.CARD_PAYMENT -> pairTransfer(txn, Duration.ofDays(3), listOf(TxnType.TRANSFER.name)) { it.excludedFromSpending }
            movement == MovementKind.REVERSAL -> linkReversal(txn)
            txn.type == TxnType.REFUND -> linkRefund(txn)
            (txn.type == TxnType.EXPENSE || txn.type == TxnType.INCOME) && txn.status == TxnStatus.CONFIRMED &&
                txn.accountId != null && txn.categorySource == null ->
                pairTransfer(txn, Duration.ofHours(24), listOf(TxnType.EXPENSE.name, TxnType.INCOME.name)) { other ->
                    // Own-account transfer: both sides on (different) known accounts, neither with a known payee or category.
                    other.accountId != null && other.accountId != txn.accountId && other.categorySource == null &&
                        other.status == TxnStatus.CONFIRMED && other.source != TxnSource.MANUAL
                }
        }
    }

    private suspend fun pairTransfer(
        txn: TransactionEntity,
        window: Duration,
        types: List<String>,
        accept: (TransactionEntity) -> Boolean,
    ) {
        val opposite = if (txn.direction == Direction.DEBIT) Direction.CREDIT else Direction.DEBIT
        val candidates = transactions.transferCounterparts(
            amountPaise = txn.amountPaise,
            direction = opposite.name,
            excludeId = txn.id,
            types = types,
            fromMillis = txn.occurredAt - window.toMillis(),
            toMillis = txn.occurredAt + window.toMillis(),
            aroundMillis = txn.occurredAt,
        ).filter(accept)
        // Only link when the match is unambiguous.
        val other = candidates.singleOrNull() ?: return
        val group = UUID.randomUUID().toString()
        val transfersId = db.categoryDao().idByName(if (txn.type == TxnType.TRANSFER) "Card payments" else "Transfers")
        listOf(txn, other).forEach { row ->
            transactions.update(
                row.copy(
                    type = TxnType.TRANSFER,
                    transferGroupId = group,
                    excludedFromSpending = true,
                    categoryId = if (row.categorySource == "USER") row.categoryId else transfersId ?: row.categoryId,
                    updatedAt = clock.millis(),
                ),
            )
        }
    }

    private suspend fun linkRefund(refund: TransactionEntity) {
        val key = refund.merchantKey ?: return
        val original = transactions.refundOriginal(
            merchantKey = key,
            minAmountPaise = refund.amountPaise,
            fromMillis = refund.occurredAt - Duration.ofDays(REFUND_WINDOW_DAYS).toMillis(),
            toMillis = refund.occurredAt,
        ) ?: return
        transactions.update(
            refund.copy(
                linkedTxnId = original.id,
                linkKind = LINK_REFUND,
                // Counted against the original category, so analytics can net it out.
                categoryId = original.categoryId ?: refund.categoryId,
                updatedAt = clock.millis(),
            ),
        )
    }

    /** "Debited but failed" + "reversed": the two cancel out and disappear from totals and review. */
    private suspend fun linkReversal(credit: TransactionEntity) {
        val failed = transactions.failedDebit(
            amountPaise = credit.amountPaise,
            fromMillis = credit.occurredAt - Duration.ofDays(REVERSAL_WINDOW_DAYS).toMillis(),
            toMillis = credit.occurredAt,
        ) ?: return
        val now = clock.millis()
        transactions.update(failed.copy(status = TxnStatus.IGNORED, linkedTxnId = credit.id, linkKind = LINK_REVERSAL, updatedAt = now))
        transactions.update(credit.copy(status = TxnStatus.IGNORED, linkedTxnId = failed.id, linkKind = LINK_REVERSAL, updatedAt = now))
    }

    // ---------------------------------------------------------------- balances and statements (§9.5, §5.7)

    /**
     * Keeps what the bank says about an account: "Avl Bal" and a card's available limit become snapshots
     * (the snapshot wins over Impeesa's own sum), and statement SMS become bills.
     */
    private suspend fun recordLedger(accountId: Long, analysis: Analysis, envelope: MessageEnvelope, rawId: Long, context: EngineContext) {
        val ledger = db.ledgerDao()
        val asOf = envelope.receivedAt.toEpochMilli()
        val isCard = context.accounts.any { it.id == accountId && it.isCard }
        val balance = analysis.balance
        val limit = analysis.availableLimit
        val snapshot = when {
            balance != null && !isCard -> SNAPSHOT_BALANCE to balance.paise
            limit != null && isCard -> SNAPSHOT_AVAILABLE_LIMIT to limit.paise
            else -> null
        }
        if (snapshot != null) {
            val previous = ledger.latestSnapshotAt(accountId)
            // Only the newest snapshot reconciles, and an account's first one has nothing to reconcile against.
            val diff = if (previous != null && asOf >= previous) {
                val computed = db.accountDao().balanceOf(accountId) ?: 0L
                val reported = if (snapshot.first == SNAPSHOT_AVAILABLE_LIMIT) {
                    db.accountDao().getById(accountId)?.creditLimitPaise?.let { snapshot.second - it }
                } else {
                    snapshot.second
                }
                reported?.let { it - computed } ?: 0L
            } else {
                0L
            }
            ledger.insertSnapshot(
                BalanceSnapshotEntity(accountId = accountId, kind = snapshot.first, balancePaise = snapshot.second, asOf = asOf, source = SOURCE_SMS, diffPaise = diff, rawId = rawId),
            )
        }
        val statement = analysis.statement
        val dueDate = statement?.dueDate
        if (isCard && statement != null && dueDate != null) {
            val due = DateKey.of(dueDate)
            val existing = ledger.statementFor(accountId, due)
            if (existing == null) {
                ledger.insertStatement(
                    CardStatementEntity(
                        accountId = accountId,
                        statementDate = DateKey.of(envelope.receivedAt.atZone(zone).toLocalDate()),
                        dueDate = due,
                        totalDuePaise = statement.totalDue?.paise,
                        minDuePaise = statement.minDue?.paise,
                        source = SOURCE_SMS,
                    ),
                )
            } else {
                // A reminder after the statement may add what the first message left out.
                ledger.updateStatement(
                    existing.copy(
                        totalDuePaise = existing.totalDuePaise ?: statement.totalDue?.paise,
                        minDuePaise = existing.minDuePaise ?: statement.minDue?.paise,
                    ),
                )
            }
        }
    }

    /** A credit on a card goes towards its open bill; paying it in full marks it paid (§5.7). */
    private suspend fun applyCardPayment(accountId: Long, amount: Money) {
        val ledger = db.ledgerDao()
        val open = ledger.openStatement(accountId) ?: return
        val paid = open.paidPaise + amount.paise
        val settled = open.totalDuePaise == null || paid >= open.totalDuePaise
        ledger.updateStatement(open.copy(paidPaise = paid, paidAt = if (settled) clock.millis() else null))
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun rememberSuggestion(match: AccountMatch.Suggested, analysis: Analysis) {
        val sender = match.senderEntity ?: return
        val now = clock.millis()
        val existing = capture.suggestion(sender, match.mask)
        val looksLikeCard = analysis.channel == Channel.CARD
        if (existing == null) {
            capture.insertSuggestion(
                AccountSuggestionEntity(
                    senderCode = sender,
                    mask = match.mask,
                    institution = analysis.senderEntity,
                    looksLikeCard = looksLikeCard,
                    sightings = 1,
                    firstSeen = now,
                    lastSeen = now,
                ),
            )
        } else {
            capture.updateSuggestion(
                existing.copy(sightings = existing.sightings + 1, lastSeen = now, looksLikeCard = existing.looksLikeCard || looksLikeCard),
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

    companion object {
        const val REASON_POSSIBLE_DUPLICATE = "POSSIBLE_DUPLICATE"
        const val REASON_AMBIGUOUS_CATEGORY = "AMBIGUOUS_CATEGORY"
        const val REASON_ASK_CATEGORY = "ASK_CATEGORY"
        const val SNAPSHOT_BALANCE = "BALANCE"
        const val SNAPSHOT_AVAILABLE_LIMIT = "AVAILABLE_LIMIT"
        const val SOURCE_SMS = "SMS"
        const val SOURCE_MANUAL = "MANUAL"
        const val ROUTE_MERGED = "MERGED"
        const val LINK_REFUND = "REFUND_OF"
        const val LINK_REVERSAL = "REVERSAL_OF"

        const val KEEP_FOREVER = -1
        const val KEEP_NEVER = 0
        const val DEFAULT_RETENTION_DAYS = 180
        val RETENTION_CHOICES = listOf(30, 90, 180, KEEP_FOREVER, KEEP_NEVER)
        private const val KEY_RETENTION = "message_retention_days"
        private const val KEY_HOME_CURRENCY = "home_currency"
        private const val DELETED_KEEP_DAYS = 30L
        private const val KEY_WATERMARK = "inbox_watermark"
        private const val KEY_APP_PREFIX = "app_enabled:"
        private const val PARSER_VERSION = 1
        private const val PROGRESS_EVERY = 50
        private const val DUPLICATE_WINDOW_MINUTES = 15L
        private const val REFUND_WINDOW_DAYS = 90L
        private const val REVERSAL_WINDOW_DAYS = 7L
        private val AGREEMENT_RESOLVES = setOf("LOW_CONFIDENCE", "UNKNOWN_SENDER")
        private val REPLACEABLE_SOURCES = setOf(null, CategorySource.DICTIONARY.name, CategorySource.KEYWORD.name)
    }
}
