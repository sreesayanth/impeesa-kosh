package com.impeesa.finance.core.model

/** How digits are grouped: Indian 1,00,000 (lakh/crore) or international 100,000 (thousand/million). */
enum class DigitGrouping { INDIAN, INTERNATIONAL }

/**
 * A display currency. Amounts are stored in minor units (paise, cents…) and are never converted:
 * the setting changes how they're shown, not their value.
 */
data class Currency(
    val code: String,
    val symbol: String,
    val name: String,
    /** Spoken by TalkBack: "rupee"/"rupees", "dollar"/"dollars". */
    val unitOne: String,
    val unitMany: String,
    val minorUnit: String,
    val defaultGrouping: DigitGrouping = DigitGrouping.INTERNATIONAL,
)

object Currencies {
    val INR = Currency("INR", "₹", "Indian rupee", "rupee", "rupees", "paise", DigitGrouping.INDIAN)
    val USD = Currency("USD", "$", "US dollar", "dollar", "dollars", "cents")
    val EUR = Currency("EUR", "€", "Euro", "euro", "euros", "cents")
    val GBP = Currency("GBP", "£", "British pound", "pound", "pounds", "pence")
    val AED = Currency("AED", "AED ", "UAE dirham", "dirham", "dirhams", "fils")
    val SAR = Currency("SAR", "SAR ", "Saudi riyal", "riyal", "riyals", "halalas")
    val SGD = Currency("SGD", "S$", "Singapore dollar", "dollar", "dollars", "cents")
    val AUD = Currency("AUD", "A$", "Australian dollar", "dollar", "dollars", "cents")
    val CAD = Currency("CAD", "C$", "Canadian dollar", "dollar", "dollars", "cents")
    val NPR = Currency("NPR", "Rs ", "Nepalese rupee", "rupee", "rupees", "paisa", DigitGrouping.INDIAN)
    val LKR = Currency("LKR", "Rs ", "Sri Lankan rupee", "rupee", "rupees", "cents")
    val BDT = Currency("BDT", "৳", "Bangladeshi taka", "taka", "taka", "poisha", DigitGrouping.INDIAN)

    val ALL = listOf(INR, USD, EUR, GBP, AED, SAR, SGD, AUD, CAD, NPR, LKR, BDT)

    fun byCode(code: String?): Currency = ALL.firstOrNull { it.code == code } ?: INR
}

/** The user's choice: a currency, and its grouping (which may differ from the currency's default). */
data class MoneySettings(val currency: Currency = Currencies.INR, val grouping: DigitGrouping = currency.defaultGrouping)
