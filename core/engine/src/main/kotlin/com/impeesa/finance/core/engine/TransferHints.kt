package com.impeesa.finance.core.engine

import com.impeesa.finance.core.engine.classify.MessageType
import com.impeesa.finance.core.engine.text.TextNormalizer
import com.impeesa.finance.core.model.Channel
import com.impeesa.finance.core.model.Direction

/** What kind of money movement a transaction message describes (IMPLEMENTATION_PLAN.md §6.10). */
enum class MovementKind {
    /** Ordinary spend or income. */
    NORMAL,

    /** Cash taken out at an ATM: bank → cash wallet. */
    ATM_WITHDRAWAL,

    /** Paying a credit card bill, seen from the bank (debit) or the card issuer (credit). */
    CARD_PAYMENT,

    /** Loading a wallet such as Paytm: bank → wallet. */
    WALLET_TOPUP,

    /** Money coming back for a failed debit. */
    REVERSAL,
}

/** Rules that turn "spent"/"received" messages into transfers, so they never count as spending or income. */
object TransferHints {
    private val options = setOf(RegexOption.IGNORE_CASE)
    /** Money sent to a credit card. Card spends read "spent on … Credit Card at X" and never match. */
    private val creditCardPayee = Regex("\\b(paid to|towards|to|for)\\s+(your\\s+)?.{0,30}credit card|\\bcc (bill|payment)\\b|card bill payment|\\bbilldesk\\b", options)
    private val cardPaymentReceived = Regex(
        "payment\\s+(of\\s+.{0,25}\\s+)?(has been\\s+)?received.{0,60}card|received payment.{0,60}card|payment of .{0,60}card.{0,20}received|towards your .{0,30}card",
        options,
    )
    private val reversal = Regex("\\b(reversal|reversed|failed txn|failed transaction|failed upi)\\b", options)

    /**
     * The card itself was charged: "Rs.300 debited to your … RuPay Credit Card" is a UPI spend on the card,
     * not a bill payment. A bank account in between ("debited from A/c XX12 towards your Credit Card") keeps
     * it a bill payment.
     */
    private val cardCharged = Regex(
        "\\b(?:debited|charged|spent|for using)\\s+(?:to|on|from|using|via|with)?\\s*(?:your\\s+)?" +
            "(?:(?!\\b(?:a/?c|acct|account)\\b)[^.]){0,40}?credit card",
        options,
    )

    fun classify(
        envelope: MessageEnvelope,
        analysis: Analysis,
        accountIsCard: Boolean,
    ): MovementKind {
        val text = TextNormalizer.normalize(listOfNotNull(envelope.title, envelope.body).joinToString(" "))
        val direction = analysis.direction ?: return MovementKind.NORMAL
        val payee = analysis.counterparty.orEmpty()
        return when {
            direction == Direction.DEBIT && analysis.channel == Channel.ATM -> MovementKind.ATM_WITHDRAWAL
            direction == Direction.DEBIT && !cardCharged.containsMatchIn(text) && (
                payee.contains("credit card", ignoreCase = true) ||
                    analysis.merchant?.key == "CRED" ||
                    creditCardPayee.containsMatchIn(text)
                ) -> MovementKind.CARD_PAYMENT
            direction == Direction.DEBIT && analysis.merchant?.categoryName == "Wallet top-up" -> MovementKind.WALLET_TOPUP
            direction == Direction.CREDIT && analysis.type == MessageType.REFUND && reversal.containsMatchIn(text) -> MovementKind.REVERSAL
            direction == Direction.CREDIT && (accountIsCard || cardPaymentReceived.containsMatchIn(text)) &&
                analysis.type != MessageType.REFUND -> MovementKind.CARD_PAYMENT
            else -> MovementKind.NORMAL
        }
    }
}
