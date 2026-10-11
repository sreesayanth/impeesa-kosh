package com.impeesa.finance.core.engine.classify

import com.impeesa.finance.core.model.Direction

/** Words that say money moved, and in which direction. */
internal object Verbs {
    private val options = setOf(RegexOption.IGNORE_CASE)

    val debit = Regex(
        "\\b(debited|spent|(?<!already )(?<!if )paid|sent|withdrawn|purchase|deducted|charged|used at|used for|txn of|trxn of|transaction of|txn(?=\\s+(rs|inr|₹))|" +
            "debit of|thank you for using|dr\\.?(?=\\s+(from|to)\\b)|payment\\s+(?:of|to)\\b.{0,60}?\\bsuccess)",
        options,
    )

    val credit = Regex(
        "\\b(credited|received|deposited|refunded|added to|credit of|sent you|cr\\.?(?=\\s+(to|in)\\b))",
        options,
    )

    /** "sent you" contains the debit word "sent"; check it first. */
    private val receivedPhrase = Regex("\\b(sent you|you received|money received)\\b", options)

    val pastTenseMoney = Regex("\\b(debited|credited|spent|withdrawn|deducted|received|deposited)\\b", options)

    fun hasDebit(text: String) = debit.containsMatchIn(text)
    fun hasCredit(text: String) = credit.containsMatchIn(text)

    fun positions(text: String): List<Int> =
        (debit.findAll(text) + credit.findAll(text)).map { it.range.first }.toList()

    /** When both kinds appear, the earlier one describes the transaction. */
    fun direction(text: String): Direction {
        if (receivedPhrase.containsMatchIn(text)) return Direction.CREDIT
        val debitAt = debit.find(text)?.range?.first ?: Int.MAX_VALUE
        val creditAt = credit.find(text)?.range?.first ?: Int.MAX_VALUE
        return if (debitAt <= creditAt) Direction.DEBIT else Direction.CREDIT
    }
}
