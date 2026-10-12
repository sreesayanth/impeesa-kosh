package com.impeesa.finance.core.model

/**
 * Money formatting, by default rupees with Indian digit grouping (₹1,12,000). The currency and grouping
 * follow the user's setting ([settings]); amounts themselves are never converted. Pure Kotlin so it is
 * unit-testable and identical on every device, regardless of locale data.
 */
object MoneyFormat {
    const val MINUS = '−'

    /** Set once at start-up and whenever the user changes currency settings. */
    @Volatile
    var settings: MoneySettings = MoneySettings()

    val symbol: String get() = settings.currency.symbol

    fun format(money: Money, signed: Boolean = false, showPaise: Boolean = false): String {
        val paise = money.paise
        val abs = kotlin.math.abs(paise)
        val body = buildString {
            append(symbol)
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

    /** Indian: 112000 -> "1,12,000". International: 112000 -> "112,000". */
    fun group(value: Long, grouping: DigitGrouping = settings.grouping): String {
        val digits = value.toString()
        if (digits.length <= 3) return digits
        val lastThree = digits.takeLast(3)
        var rest = digits.dropLast(3)
        val size = if (grouping == DigitGrouping.INDIAN) 2 else 3
        val parts = ArrayDeque<String>()
        while (rest.length > size) {
            parts.addFirst(rest.takeLast(size))
            rest = rest.dropLast(size)
        }
        if (rest.isNotEmpty()) parts.addFirst(rest)
        return parts.joinToString(",") + "," + lastThree
    }

    /** Short form for tight spaces: ₹1.12 L, ₹2.4 Cr, ₹12.5K (Indian) or $1.2M, $3.4B, $12.5K (international). */
    fun compact(money: Money): String {
        val units = kotlin.math.abs(money.paise) / 100
        val sign = if (money.paise < 0) MINUS.toString() else ""
        val body = if (settings.grouping == DigitGrouping.INDIAN) {
            when {
                units >= 1_00_00_000 -> symbol + hundredths(units / 1_00_000) + " Cr"
                units >= 1_00_000 -> symbol + hundredths(units / 1_000) + " L"
                units >= 10_000 -> symbol + tenths(units / 100) + "K"
                else -> symbol + group(units)
            }
        } else {
            when {
                units >= 1_000_000_000 -> symbol + tenths(units / 100_000_000) + "B"
                units >= 1_000_000 -> symbol + tenths(units / 100_000) + "M"
                units >= 10_000 -> symbol + tenths(units / 100) + "K"
                else -> symbol + group(units)
            }
        }
        return sign + body
    }

    /** What a screen reader should say, e.g. "minus 486 rupees". */
    fun spoken(money: Money): String {
        val currency = settings.currency
        val abs = kotlin.math.abs(money.paise)
        val units = abs / 100
        val minor = abs % 100
        val sign = if (money.paise < 0) "minus " else ""
        val word = if (units == 1L) currency.unitOne else currency.unitMany
        return if (minor == 0L) "$sign$units $word" else "$sign$units $word and $minor ${currency.minorUnit}"
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
