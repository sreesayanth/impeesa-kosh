package com.impeesa.finance.core.engine.classify

import com.impeesa.finance.core.engine.account.AccountMaskExtractor
import com.impeesa.finance.core.engine.amount.AmountSelector
import com.impeesa.finance.core.engine.sender.HeaderCategory
import com.impeesa.finance.core.engine.sender.SenderInfo
import com.impeesa.finance.core.model.Direction

enum class MessageType {
    OTP,
    COLLECT_REQUEST,
    PROMOTIONAL,
    FAILED,
    FAILED_BUT_DEBITED,
    STATEMENT,
    UPCOMING,
    REFUND,
    TRANSACTION,
    BALANCE,
    UNKNOWN,
}

data class Classification(val type: MessageType, val direction: Direction? = null)

/**
 * Decides what kind of message this is. Rules run in a fixed order and the first match wins;
 * see IMPLEMENTATION_PLAN.md §6.4. Input should already be normalised.
 */
object MessageTypeClassifier {
    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val otpKeyword = rx("\\b(otp|one[ -]time password|verification code|passcode|security code)\\b")
    /** A 4–8 digit code near the keyword; "(?![.,]\d)" skips amounts such as 3999.00. */
    private val otpCode = rx(
        "(otp|code|password|passcode)\\b.{0,120}?\\b\\d{4,8}\\b(?![.,]\\d)|\\b\\d{4,8}\\b(?![.,]\\d).{0,30}?\\b(is|as)\\b.{0,40}?(otp|code|password)",
    )

    private val collect = rx("\\b(has requested|have requested|requested by|collect request|requested money|request(ed)? (rs|inr|₹)|approve .{0,30}to pay)")
    private val promo = rx(
        "\\b(pre-?approved|offers?\\b|cashback (of )?up to|apply now|win\\b|won\\b|claim|congratulations|eligible|limited period|" +
            "click|hurry|exclusive|discount|voucher|use code|order now|shop now|recharge now|sale\\b|flat .{0,12}off|% off)|bit\\.ly|t\\.co/",
    )

    private val failed = rx("\\b(failed|declined|unsuccessful|could not be (processed|completed)|was not successful|rejected)\\b")
    private val willReverse = rx("\\b(will be (reversed|refunded|credited back)|reversal (will|shall))")

    private val statement = rx(
        "\\b(total\\s+(amount|amt)\\s+due|min(imum)?\\.?\\s+(amount\\s+|amt\\s+)?due|statement (is )?generated|statement for|e-?statement|payment due\\b|ignore if already paid)",
    )
    private val upcomingStrong = rx("\\b(will be debited|will be charged|is scheduled|scheduled (for|on)|due on|pre-debit)")
    private val mandateSetup = rx("\\b(mandate|e-mandate|standing instruction|autopay|auto[- ]?debit)\\b.*\\b(registered|set ?up|created|activated|successful)")

    private val refund = rx("\\b(refund|refunded|reversed|reversal|chargeback)\\b")
    private val balanceWord = rx("\\b(available bal(ance)?|avl\\.? bal|bal(ance)? is|avbl bal|balance of|available limit|avl\\.? lmt)")

    fun classify(text: String, sender: SenderInfo? = null): Classification {
        val amounts = AmountSelector.pick(text)
        val hasTxnAmount = amounts.transaction != null
        val isDebit = Verbs.hasDebit(text)
        val isCredit = Verbs.hasCredit(text)
        val hasMask = AccountMaskExtractor.extract(text) != null
        val pastTense = Verbs.pastTenseMoney.containsMatchIn(text)

        if (otpKeyword.containsMatchIn(text) && otpCode.containsMatchIn(text) && !pastTense) {
            return Classification(MessageType.OTP)
        }
        if (collect.containsMatchIn(text) && !pastTense) {
            return Classification(MessageType.COLLECT_REQUEST)
        }
        val looksLikeRealTxn = (isDebit || isCredit) && hasMask
        if (sender?.category == HeaderCategory.PROMOTIONAL || (promo.containsMatchIn(text) && !looksLikeRealTxn)) {
            return Classification(MessageType.PROMOTIONAL)
        }
        // "Credited … reversal of failed txn" is money coming back, not a failure.
        if (hasTxnAmount && refund.containsMatchIn(text) && isCredit && !willReverse.containsMatchIn(text)) {
            return Classification(MessageType.REFUND, Direction.CREDIT)
        }
        if (failed.containsMatchIn(text)) {
            return if (rx("\\bdebited\\b").containsMatchIn(text) && willReverse.containsMatchIn(text)) {
                Classification(MessageType.FAILED_BUT_DEBITED, Direction.DEBIT)
            } else {
                Classification(MessageType.FAILED)
            }
        }
        if (statement.containsMatchIn(text)) return Classification(MessageType.STATEMENT)
        if (upcomingStrong.containsMatchIn(text) || (mandateSetup.containsMatchIn(text) && !pastTense)) {
            return Classification(MessageType.UPCOMING, Direction.DEBIT)
        }
        if (hasTxnAmount && (isDebit || isCredit)) {
            return Classification(MessageType.TRANSACTION, Verbs.direction(text))
        }
        if (amounts.hasAny && balanceWord.containsMatchIn(text)) return Classification(MessageType.BALANCE)
        return Classification(MessageType.UNKNOWN)
    }
}
