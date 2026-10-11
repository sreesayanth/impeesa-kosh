package com.impeesa.finance.core.engine

import com.impeesa.finance.core.engine.account.AccountMaskExtractor
import com.impeesa.finance.core.engine.account.AccountResolver
import com.impeesa.finance.core.engine.amount.AmountSelector
import com.impeesa.finance.core.engine.classify.MessageType
import com.impeesa.finance.core.engine.classify.MessageTypeClassifier
import com.impeesa.finance.core.engine.extract.ChannelDetector
import com.impeesa.finance.core.engine.extract.CounterpartyExtractor
import com.impeesa.finance.core.engine.extract.DateExtractor
import com.impeesa.finance.core.engine.extract.ReferenceExtractor
import com.impeesa.finance.core.engine.merchant.MerchantDirectory
import com.impeesa.finance.core.engine.registry.AppKind
import com.impeesa.finance.core.engine.registry.AppRegistry
import com.impeesa.finance.core.engine.registry.SenderRegistry
import com.impeesa.finance.core.engine.sender.SenderClassifier
import com.impeesa.finance.core.engine.sender.SenderInfo
import com.impeesa.finance.core.engine.sender.SenderKind
import com.impeesa.finance.core.engine.template.TemplateSet
import com.impeesa.finance.core.engine.text.TextNormalizer
import com.impeesa.finance.core.model.Channel
import com.impeesa.finance.core.model.Money

/**
 * Reads one message and decides whether it is a transaction, and what it says.
 * Pure Kotlin, deterministic, never throws on bad input (IMPLEMENTATION_PLAN.md §6).
 */
class DetectionEngine(
    private val senders: SenderRegistry,
    private val apps: AppRegistry,
    private val templates: TemplateSet,
    private val merchants: MerchantDirectory,
) {
    fun analyze(envelope: MessageEnvelope, context: EngineContext = EngineContext()): Analysis =
        runCatching { analyzeUnsafe(envelope, context) }
            .getOrElse { Analysis(type = MessageType.UNKNOWN, route = Route.DROP, dropReason = DropReason.NOT_FINANCIAL) }

    private fun analyzeUnsafe(envelope: MessageEnvelope, context: EngineContext): Analysis {
        val text = TextNormalizer.normalize(listOfNotNull(envelope.title, envelope.body).joinToString(" "))
        val gate = gatekeep(envelope, context) ?: return drop(MessageType.UNKNOWN, DropReason.UNSUPPORTED_APP)
        if (gate.dropReason != null) return drop(MessageType.UNKNOWN, gate.dropReason)

        val classification = MessageTypeClassifier.classify(text, gate.senderInfo)
        val type = classification.type
        if (type in DROPPED_TYPES) return drop(type, DropReason.NOT_FINANCIAL, gate.entity)

        val received = envelope.receivedAt.atZone(context.zone).toLocalDate()
        val amounts = AmountSelector.pick(text)
        val template = if (type == MessageType.TRANSACTION || type == MessageType.REFUND) templates.match(gate.templateKey, text) else null

        val amountMinor = template?.amount ?: amounts.transaction?.minorUnits
        val currency = if (template?.amount != null) "INR" else amounts.transaction?.currency ?: "INR"
        val mask = template?.mask ?: AccountMaskExtractor.extract(text)
        val counterparty = template?.merchant ?: CounterpartyExtractor.extract(text)
        val reference = template?.reference ?: ReferenceExtractor.extract(text)
        val date = template?.date?.let { DateExtractor.extract(it, received) } ?: DateExtractor.extract(text, received)
        val channel = template?.spec?.channel?.takeIf { it != Channel.OTHER } ?: ChannelDetector.detect(text)
        val balance = template?.balance ?: amounts.balance ?: amountMinor.takeIf { type == MessageType.BALANCE }
        val limit = template?.limit ?: amounts.limit
        val direction = template?.spec?.direction ?: classification.direction
        val merchant = counterparty?.takeUnless { channel == Channel.ATM }?.let(merchants::lookup)

        var score = 0.0
        if (gate.trusted) score += 0.35 else if (gate.senderInfo?.kind == SenderKind.COMMERCIAL) score -= 0.20
        if (amounts.transaction != null && direction != null) score += if (verbNearAmount(text, amounts.transaction.start)) 0.25 else 0.15
        if (amounts.hasMarker) score += 0.20
        if (mask != null) score += 0.10
        if (reference != null) score += 0.05
        if (counterparty != null) score += 0.05
        if (amounts.ambiguous) score -= 0.20
        if (template != null) score = maxOf(score, 0.95)
        score = score.coerceIn(0.0, 1.0)

        val (route, reviewReason) = route(type, score, currency, amountMinor, gate, mask)
        return Analysis(
            type = type,
            route = route,
            dropReason = if (route == Route.DROP) DropReason.LOW_CONFIDENCE else null,
            reviewReason = reviewReason,
            direction = direction,
            amount = amountMinor?.let(::Money),
            currency = currency,
            mask = mask,
            counterparty = counterparty,
            merchant = merchant,
            date = date,
            reference = reference,
            balance = balance?.let(::Money),
            availableLimit = limit?.let(::Money),
            channel = channel,
            confidence = score,
            templateId = template?.spec?.id,
            senderEntity = gate.entity,
            account = AccountResolver.resolve(gate.entity, mask, context.accounts),
        )
    }

    private fun route(
        type: MessageType,
        score: Double,
        currency: String,
        amount: Long?,
        gate: Gate,
        mask: String?,
    ): Pair<Route, ReviewReason?> = when (type) {
        MessageType.STATEMENT, MessageType.UPCOMING, MessageType.BALANCE -> Route.INFO to null
        MessageType.FAILED_BUT_DEBITED -> Route.REVIEW to ReviewReason.FAILED_BUT_DEBITED
        MessageType.TRANSACTION, MessageType.REFUND -> when {
            amount == null -> Route.DROP to null
            currency != "INR" -> Route.REVIEW to ReviewReason.FOREIGN_CURRENCY
            score >= AUTO_LOG_THRESHOLD -> Route.AUTO_LOG to null
            score >= REVIEW_THRESHOLD -> Route.REVIEW to ReviewReason.LOW_CONFIDENCE
            gate.trusted -> Route.REVIEW to ReviewReason.LOW_CONFIDENCE
            gate.senderInfo?.kind == SenderKind.COMMERCIAL && mask != null -> Route.REVIEW to ReviewReason.UNKNOWN_SENDER
            else -> Route.DROP to null
        }
        else -> Route.DROP to null
    }

    private class Gate(
        val senderInfo: SenderInfo?,
        val entity: String?,
        /** Template lookup key: SMS entity code or notification package. */
        val templateKey: String?,
        val trusted: Boolean,
        val dropReason: DropReason? = null,
    )

    private fun gatekeep(envelope: MessageEnvelope, context: EngineContext): Gate? = when (envelope.source) {
        MessageSource.SMS -> {
            val info = SenderClassifier.classify(envelope.sender)
            val entity = info.entity
            when {
                info.kind == SenderKind.PERSONAL -> Gate(info, null, null, false, DropReason.PERSONAL_SENDER)
                envelope.sender in context.blockedSenders || entity in context.blockedSenders ->
                    Gate(info, entity, entity, false, DropReason.BLOCKED_SENDER)
                else -> Gate(info, entity, entity, trusted = senders.find(entity) != null)
            }
        }
        MessageSource.NOTIFICATION -> {
            val app = apps.find(envelope.sender)
            when {
                app == null -> null
                envelope.sender in context.blockedSenders -> Gate(null, null, null, false, DropReason.BLOCKED_SENDER)
                app.kind == AppKind.MESSAGING -> {
                    // RCS bank alerts: only trust chats whose title is a known institution.
                    val institution = senders.findByName(envelope.title)
                    if (institution == null) Gate(null, null, null, false, DropReason.NOT_FINANCIAL)
                    else Gate(SenderInfo(SenderKind.COMMERCIAL, institution.code), institution.code, institution.code, trusted = true)
                }
                else -> Gate(null, null, envelope.sender, trusted = true)
            }
        }
    }

    private fun verbNearAmount(text: String, amountStart: Int): Boolean {
        val window = text.substring(maxOf(0, amountStart - 45), minOf(text.length, amountStart + 60))
        return com.impeesa.finance.core.engine.classify.Verbs.positions(window).isNotEmpty()
    }

    private fun drop(type: MessageType, reason: DropReason, entity: String? = null) =
        Analysis(type = type, route = Route.DROP, dropReason = reason, senderEntity = entity)

    companion object {
        const val AUTO_LOG_THRESHOLD = 0.85
        const val REVIEW_THRESHOLD = 0.60

        private val DROPPED_TYPES = setOf(
            MessageType.OTP,
            MessageType.COLLECT_REQUEST,
            MessageType.PROMOTIONAL,
            MessageType.FAILED,
            MessageType.UNKNOWN,
        )

        /** Engine with the bundled sender list, apps, templates and merchant dictionary. */
        fun withBundledData(): DetectionEngine =
            DetectionEngine(SenderRegistry.load(), AppRegistry.load(), TemplateSet.load(), MerchantDirectory.load())
    }
}
