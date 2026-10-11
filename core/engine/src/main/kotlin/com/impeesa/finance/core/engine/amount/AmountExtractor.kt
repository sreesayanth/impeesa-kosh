package com.impeesa.finance.core.engine.amount

/** An amount found in a message, with where it was found so callers can judge context. */
data class AmountCandidate(
    val minorUnits: Long,
    val currency: String,
    val start: Int,
    val end: Int,
)

object AmountExtractor {
    private val pattern = Regex(
        "(?<![A-Za-z])(rs\\.?|inr|₹|usd|eur|gbp|aed|sgd)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        RegexOption.IGNORE_CASE,
    )

    fun extract(text: String): List<AmountCandidate> =
        pattern.findAll(text).mapNotNull { match ->
            val minor = parseMinorUnits(match.groupValues[2]) ?: return@mapNotNull null
            AmountCandidate(
                minorUnits = minor,
                currency = currencyCode(match.groupValues[1]),
                start = match.range.first,
                end = match.range.last + 1,
            )
        }.toList()

    /** Parses "1,23,456.78" into 12345678. Returns null for anything that is not a plain amount. */
    fun parseMinorUnits(raw: String): Long? {
        val cleaned = raw.trimEnd(',', '.').replace(",", "")
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        if (parts.size > 2) return null
        val whole = parts[0]
        if (whole.isEmpty() || whole.length > 13 || !whole.all(Char::isDigit)) return null
        val fraction = parts.getOrNull(1).orEmpty()
        if (fraction.length > 2 || !fraction.all(Char::isDigit)) return null
        return whole.toLong() * 100 + fraction.padEnd(2, '0').toLong()
    }

    private fun currencyCode(marker: String): String =
        when (marker.lowercase().trimEnd('.')) {
            "rs", "inr", "₹" -> "INR"
            else -> marker.uppercase()
        }
}
