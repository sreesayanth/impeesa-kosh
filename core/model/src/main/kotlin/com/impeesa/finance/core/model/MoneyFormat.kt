package com.impeesa.finance.core.model

/**
 * Rupee formatting with Indian digit grouping (₹1,12,000). Pure Kotlin so it is unit-testable
 * and identical on every device, regardless of locale data.
 */
object MoneyFormat {
    const val MINUS = '−'

    fun format(money: Money, signed: Boolean = false, showPaise: Boolean = false): String {
        val paise = money.paise
        val abs = kotlin.math.abs(paise)
        val body = buildString {
            append('₹')
            append(group(abs / 100))
            if (showPaise) {
                append('.')
                append((abs % 100).toString().padStart(2, '0'))
            }
        }
        return when {
            paise < 0 -> "$MINUS$body"
            signed && paise > 0 -> "+$body"
            else -> body
        }
    }

    /** 112000 -> "1,12,000"; 1234567 -> "12,34,567". */
    fun group(value: Long): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val lastThree = digits.takeLast(3)
        var rest = digits.dropLast(3)
        val parts = ArrayDeque<String>()
        while (rest.length > 2) {
            parts.addFirst(rest.takeLast(2))
            rest = rest.dropLast(2)
        }
        if (rest.isNotEmpty()) parts.addFirst(rest)
        return parts.joinToString(",") + "," + lastThree
    }

    /** Short form for tight spaces: ₹1.12 L, ₹2.4 Cr, ₹12.5K. */
    fun compact(money: Money): String {
        val rupees = kotlin.math.abs(money.paise) / 100
        val sign = if (money.paise < 0) MINUS.toString() else ""
        val body = when {
            rupees >= 1_00_00_000 -> "₹" + hundredths(rupees / 1_00_000) + " Cr"
            rupees >= 1_00_000 -> "₹" + hundredths(rupees / 1_000) + " L"
            rupees >= 10_000 -> "₹" + tenths(rupees / 100) + "K"
            else -> "₹" + group(rupees)
        }
        return sign + body
    }

    /** What a screen reader should say, e.g. "minus 486 rupees". */
    fun spoken(money: Money): String {
        val abs = kotlin.math.abs(money.paise)
        val rupees = abs / 100
        val paise = abs % 100
        val sign = if (money.paise < 0) "minus " else ""
        val rupeeWord = if (rupees == 1L) "rupee" else "rupees"
        return if (paise == 0L) "$sign$rupees $rupeeWord" else "$sign$rupees $rupeeWord and $paise paise"
    }

    private fun hundredths(value: Long): String {
        val whole = value / 100
        val fraction = (value % 100).toString().padStart(2, '0').trimEnd('0')
        return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
    }

    private fun tenths(value: Long): String {
        val whole = value / 10
        val fraction = value % 10
        return if (fraction == 0L) whole.toString() else "$whole.$fraction"
    }
}
