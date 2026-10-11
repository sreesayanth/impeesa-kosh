package com.impeesa.finance.core.engine.account

/** Finds the visible digits of an account or card number, e.g. "A/c XX4821" -> "4821". */
object AccountMaskExtractor {
    private val pattern = Regex(
        "(?:a/?c|acct|account|card)(?:\\s*account)?(?:\\s*no\\.?)?[\\s:]*(?:ending(?:\\s+(?:with|in))?)?\\s*(?:\\d{0,4}[x*]+)?\\s*(\\d{3,6})\\b",
        RegexOption.IGNORE_CASE,
    )

    fun extract(text: String): String? =
        pattern.find(text)?.groupValues?.get(1)?.takeLast(4)
}
