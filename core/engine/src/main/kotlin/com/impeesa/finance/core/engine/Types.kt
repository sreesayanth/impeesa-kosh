package com.impeesa.finance.core.engine

import com.impeesa.finance.core.engine.classify.MessageType
import com.impeesa.finance.core.engine.extract.StatementInfo
import com.impeesa.finance.core.engine.merchant.MerchantMatch
import com.impeesa.finance.core.model.Channel
import com.impeesa.finance.core.model.Direction
import com.impeesa.finance.core.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class MessageSource { SMS, NOTIFICATION }

/** One incoming message, normalised enough for the engine to read. */
data class MessageEnvelope(
    val source: MessageSource,
    /** SMS originating address, or the notification's package name. */
    val sender: String,
    val body: String,
    val receivedAt: Instant,
    /** Notification title; null for SMS. */
    val title: String? = null,
)

/** A user account the engine can match messages against. */
data class KnownAccount(
    val id: Long,
    val mask: String?,
    val senderCodes: Set<String> = emptySet(),
    val isCard: Boolean = false,
)

data class EngineContext(
    val accounts: List<KnownAccount> = emptyList(),
    val blockedSenders: Set<String> = emptySet(),
    val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
    /** Amounts in any other currency go to Review as foreign (the user's display currency). */
    val homeCurrency: String = "INR",
)

/** What to do with a message. */
enum class Route {
    /** Confident transaction: save without asking. */
    AUTO_LOG,

    /** Probably a transaction, but the user should confirm. */
    REVIEW,

    /** Not a transaction, but useful: balance, statement, upcoming debit. */
    INFO,

    /** Ignore. Never stored. */
    DROP,
}

enum class DropReason {
    PERSONAL_SENDER,
    BLOCKED_SENDER,
    UNSUPPORTED_APP,
    NOT_FINANCIAL,
    LOW_CONFIDENCE,
}

enum class ReviewReason { LOW_CONFIDENCE, FOREIGN_CURRENCY, FAILED_BUT_DEBITED, UNKNOWN_SENDER }

sealed interface AccountMatch {
    data class Matched(val accountId: Long) : AccountMatch
    data class Suggested(val senderEntity: String?, val mask: String) : AccountMatch
    data object Unknown : AccountMatch
}

/** The engine's verdict on one message. */
data class Analysis(
    val type: MessageType,
    val route: Route,
    val dropReason: DropReason? = null,
    val reviewReason: ReviewReason? = null,
    val direction: Direction? = null,
    /** Transaction amount in minor units of [currency]. */
    val amount: Money? = null,
    val currency: String = "INR",
    val mask: String? = null,
    val counterparty: String? = null,
    val merchant: MerchantMatch? = null,
    val date: LocalDate? = null,
    val reference: String? = null,
    val balance: Money? = null,
    val availableLimit: Money? = null,
    val channel: Channel = Channel.OTHER,
    val confidence: Double = 0.0,
    val templateId: String? = null,
    val senderEntity: String? = null,
    val account: AccountMatch = AccountMatch.Unknown,
    /** Set for credit-card statements and due reminders. */
    val statement: StatementInfo? = null,
) {
    val isTransaction: Boolean get() = route == Route.AUTO_LOG || route == Route.REVIEW
}
