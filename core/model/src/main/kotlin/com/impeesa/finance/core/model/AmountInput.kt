package com.impeesa.finance.core.model

/**
 * What the user has typed on the amount keypad, e.g. "1234.5". Keeps input valid as it is
 * typed: at most two decimals, no leading zeros, a sensible maximum.
 */
@JvmInline
value class AmountInput(val text: String = "") {

    val money: Money get() = parse(text) ?: Money.ZERO

    val isEmpty: Boolean get() = text.isEmpty()

    fun press(key: Char): AmountInput {
        val dot = text.indexOf('.')
        return when {
            key == '.' -> when {
                dot >= 0 -> this
                text.isEmpty() -> AmountInput("0.")
                else -> AmountInput("$text.")
            }
            key.isDigit() -> when {
                dot >= 0 -> if (text.length - dot - 1 >= 2) this else AmountInput(text + key)
                text == "0" -> AmountInput(key.toString())
                text.length >= MAX_WHOLE_DIGITS -> this
                else -> AmountInput(text + key)
            }
            else -> this
        }
    }

    fun delete(): AmountInput = AmountInput(text.dropLast(1))

    /** "123456.5" -> "1,23,456.5". Keeps a trailing dot so the user sees what they typed. */
    fun display(): String {
        if (text.isEmpty()) return "0"
        val dot = text.indexOf('.')
        val whole = if (dot >= 0) text.substring(0, dot) else text
        val grouped = MoneyFormat.group(whole.ifEmpty { "0" }.toLong())
        return if (dot >= 0) grouped + text.substring(dot) else grouped
    }

    companion object {
        const val MAX_WHOLE_DIGITS = 9

        fun of(money: Money): AmountInput {
            val abs = kotlin.math.abs(money.paise)
            if (abs == 0L) return AmountInput()
            val rupees = abs / 100
            val paise = abs % 100
            val text = if (paise == 0L) "$rupees" else "$rupees." + paise.toString().padStart(2, '0').trimEnd('0')
            return AmountInput(text)
        }

        /** Parses free text such as "₹1,20,000.50" typed into a form field. */
        fun parse(raw: String): Money? {
            val cleaned = raw.trim().removePrefix(MoneyFormat.symbol.trim()).removePrefix("₹").trim().replace(",", "")
            if (cleaned.isEmpty() || cleaned == ".") return null
            val parts = cleaned.split('.')
            if (parts.size > 2) return null
            val whole = parts[0].ifEmpty { "0" }
            val fraction = parts.getOrElse(1) { "" }
            if (!whole.all(Char::isDigit) || !fraction.all(Char::isDigit)) return null
            if (fraction.length > 2 || whole.length > MAX_WHOLE_DIGITS + 4) return null
            return Money(whole.toLong() * 100 + fraction.padEnd(2, '0').toLong())
        }
    }
}
