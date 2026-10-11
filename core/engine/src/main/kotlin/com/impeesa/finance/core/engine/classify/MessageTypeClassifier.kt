package com.impeesa.finance.core.engine.classify

import com.impeesa.finance.core.engine.account.AccountMaskExtractor
import com.impeesa.finance.core.engine.amount.AmountExtractor
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
    private val otpCode = rx("(otp|code|password|passcode).{0,50}?\\b\\d{4,8}\\b|\\b\\d{4,8}\\b.{0,30}?(is your|as your|is the).{0,20}?(otp|code|password)")
    private val pastTenseMoney = rx("\\b(debited|credited|spent|withdrawn|deducted|received)\\b")

    private val collect = rx("\\b(has requested|collect request|requested money|request(ed)? (rs|inr|₹)|approve .{0,30}to pay)")
    private val promo = rx("\\b(pre-?approved|offer|cashback (of )?up to|apply now|win\\b|eligible|limited period|click|hurry|exclusive|discount|voucher|bit\\.ly|t\\.co/)")

    private val failed = rx("\\b(failed|declined|unsuccessful|could not be processed|was not successful|rejected)\\b")
    private val willReverse = rx("\\b(will be (reversed|refunded|credited back)|reversal (will|shall))")

    private val statement = rx("\\b(total amount due|minimum amount due|min\\.? amt due|statement (is )?generated|statement for)")
    private val upcomingStrong = rx("\\b(will be debited|will be charged|is scheduled|scheduled (for|on)|due on|pre-debit)")
    private val mandateSetup = rx("\\b(mandate|e-mandate|standing instruction|autopay|auto[- ]?debit)\\b.*\\b(registered|set ?up|created|activated|successful)")

    private val refund = rx("\\b(refund|refunded|reversed|reversal|chargeback)\\b")
    private val debitVerb = rx("\\b(debited|spent|paid|sent|withdrawn|purchase|deducted|charged|used at|txn of|transaction of)\\b")
    private val creditVerb = rx("\\b(credited|received|deposited|added to)\\b")
    private val balanceWord = rx("\\b(available balance|avl\\.? bal|bal(ance)? is|avbl bal|available limit|avl\\.? lmt)")

    fun classify(text: String, sender: SenderInfo? = null): Classification {
        val hasAmount = AmountExtractor.extract(text).isNotEmpty()
        val isDebit = debitVerb.containsMatchIn(text)
        val isCredit = creditVerb.containsMatchIn(text)
        val hasMask = AccountMaskExtractor.extract(text) != null

        if (otpKeyword.containsMatchIn(text) && otpCode.containsMatchIn(text) && !pastTenseMoney.containsMatchIn(text)) {
            return Classification(MessageType.OTP)
        }
        if (collect.containsMatchIn(text) && !pastTenseMoney.containsMatchIn(text)) {
            return Classification(MessageType.COLLECT_REQUEST)
        }
        val looksLikeRealTxn = (isDebit || isCredit) && hasMask
        if (sender?.category == HeaderCategory.PROMOTIONAL ||
            (promo.containsMatchIn(text) && !looksLikeRealTxn)
        ) {
            return Classification(MessageType.PROMOTIONAL)
        }
        if (failed.containsMatchIn(text)) {
            return if (rx("\\bdebited\\b").containsMatchIn(text) && willReverse.containsMatchIn(text)) {
                Classification(MessageType.FAILED_BUT_DEBITED, Direction.DEBIT)
            } else {
                Classification(MessageType.FAILED)
            }
        }
        if (statement.containsMatchIn(text)) return Classification(MessageType.STATEMENT)
        if (upcomingStrong.containsMatchIn(text) ||
            (mandateSetup.containsMatchIn(text) && !pastTenseMoney.containsMatchIn(text))
        ) {
            return Classification(MessageType.UPCOMING, Direction.DEBIT)
        }
        if (hasAmount && refund.containsMatchIn(text) && isCredit) {
            return Classification(MessageType.REFUND, Direction.CREDIT)
        }
        if (hasAmount && (isDebit || isCredit)) {
            val direction = directionOf(text)
            return Classification(MessageType.TRANSACTION, direction)
        }
        if (hasAmount && balanceWord.containsMatchIn(text)) return Classification(MessageType.BALANCE)
        return Classification(MessageType.UNKNOWN)
    }

    /** When both kinds of verb appear, the one that comes first describes the transaction. */
    private fun directionOf(text: String): Direction {
        val debitAt = debitVerb.find(text)?.range?.first ?: Int.MAX_VALUE
        val creditAt = creditVerb.find(text)?.range?.first ?: Int.MAX_VALUE
        return if (debitAt <= creditAt) Direction.DEBIT else Direction.CREDIT
    }
}
