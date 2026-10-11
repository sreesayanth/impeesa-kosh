package com.impeesa.finance.core.engine.merchant

import com.impeesa.finance.core.engine.registry.EngineJson
import com.impeesa.finance.core.engine.registry.readResource
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** How a counterparty was recognised. */
enum class MerchantSource { DICTIONARY, RAW }

data class MerchantMatch(
    /** Stable key for rules and learning, e.g. "SWIGGY". */
    val key: String,
    val displayName: String,
    /** Category name from the default taxonomy, when the dictionary knows it. */
    val categoryName: String?,
    val source: MerchantSource,
    /** Looks like a person rather than a business (P2P transfer). */
    val looksPersonal: Boolean = false,
)

/**
 * Turns raw payee text into a stable key: "UPI/P2M/628…/BUNDL TECHNOLOGIES PVT LTD" -> "BUNDL TECHNOLOGIES".
 * Pure and idempotent (IMPLEMENTATION_PLAN.md §7.2).
 */
object MerchantNormalizer {
    private val prefixes = setOf(
        "UPI", "P2M", "P2A", "POS", "ECOM", "IMPS", "NEFT", "RTGS", "NACH", "ACH", "DR", "CR", "BIL", "ATM",
        "REFUND", "PAYMENT", "TO", "FROM", "MR", "MS", "MRS", "SHRI", "SMT", "VPA", "TXN",
    )
    private val suffixes = setOf(
        "PVT", "PRIVATE", "LTD", "LIMITED", "LLP", "INC", "INDIA", "IN", "CO", "COMPANY", "CORPORATION",
        "BANGALORE", "BENGALURU", "MUMBAI", "DELHI", "NEW", "GURGAON", "GURUGRAM", "PUNE", "HYDERABAD", "CHENNAI",
        "KOLKATA", "NOIDA", "AHMEDABAD", "IND", "BANK", "HDFC", "ICICI", "SBI", "AXIS",
    )
    private val vpaNoise = Regex("^(paytmqr|bharatpe|q\\d+|mab|gpay-|paytm-)", RegexOption.IGNORE_CASE)

    fun key(raw: String): String {
        val base = if ('@' in raw) vpaLocalPart(raw) else raw
        val tokens = base.uppercase()
            .replace(Regex("[^A-Z0-9& ]"), " ")
            .split(' ')
            .filter { it.isNotBlank() }
            .filterNot { it.length >= 4 && it.all(Char::isDigit) }
            .toMutableList()
        while (tokens.isNotEmpty() && tokens.first() in prefixes) tokens.removeAt(0)
        while (tokens.size > 1 && tokens.last() in suffixes) tokens.removeAt(tokens.lastIndex)
        return tokens.take(3).joinToString(" ")
    }

    fun vpaLocalPart(vpa: String): String =
        vpa.substringBefore('@').replace(vpaNoise, "").trimEnd { it.isDigit() }.ifEmpty { vpa.substringBefore('@') }

    fun isVpa(raw: String) = Regex("^[\\w.\\-]+@[A-Za-z]+$").matches(raw.trim())
}

@Serializable
private data class MerchantEntry(
    val name: String,
    val category: String,
    val aliases: List<String> = emptyList(),
    val vpas: List<String> = emptyList(),
)

/** The bundled dictionary of known merchants (IMPLEMENTATION_PLAN.md §7.3). */
class MerchantDirectory private constructor(entries: List<MerchantEntry>) {
    private val byKey = HashMap<String, MerchantEntry>()
    private val byVpa = HashMap<String, MerchantEntry>()

    init {
        for (entry in entries) {
            (listOf(entry.name) + entry.aliases).forEach { byKey.putIfAbsent(MerchantNormalizer.key(it), entry) }
            entry.vpas.forEach { byVpa[it.lowercase()] = entry }
        }
    }

    val size: Int get() = byKey.size

    fun lookup(raw: String): MerchantMatch {
        val text = raw.trim()
        val isVpa = MerchantNormalizer.isVpa(text)
        val key = MerchantNormalizer.key(text)
        val entry = (if (isVpa) byVpa[MerchantNormalizer.vpaLocalPart(text).lowercase()] else null)
            ?: byKey[key]
            ?: key.split(' ').let { tokens ->
                // "SWIGGY INSTAMART BLR" -> try the two-word, then one-word prefix.
                (tokens.size downTo 1).asSequence()
                    .map { tokens.take(it).joinToString(" ") }
                    .filter { it.length >= 3 }
                    .firstNotNullOfOrNull { byKey[it] }
            }
        if (entry != null) {
            return MerchantMatch(MerchantNormalizer.key(entry.name), entry.name, entry.category, MerchantSource.DICTIONARY)
        }
        return MerchantMatch(
            key = key.ifEmpty { text.uppercase() },
            displayName = if (isVpa) text.lowercase() else titleCase(text),
            categoryName = null,
            source = MerchantSource.RAW,
            looksPersonal = looksPersonal(text, isVpa),
        )
    }

    private fun titleCase(text: String): String =
        text.split(' ').filter { it.isNotBlank() }.joinToString(" ") { word ->
            if (word.length <= 3 && word.all { it.isUpperCase() || !it.isLetter() } && word.any(Char::isDigit)) word
            else word.lowercase().replaceFirstChar { it.uppercase() }
        }

    private fun looksPersonal(text: String, isVpa: Boolean): Boolean {
        if (isVpa) {
            val local = text.substringBefore('@')
            return local.all(Char::isDigit) && local.length == 10 || Regex("^[a-z]+(\\.[a-z]+)?\\d{0,3}$", RegexOption.IGNORE_CASE).matches(local)
        }
        val words = text.trim().split(' ').filter { it.isNotBlank() }
        return words.size in 1..3 && words.all { w -> w.all { it.isLetter() || it == '.' } }
    }

    companion object {
        fun load(): MerchantDirectory =
            MerchantDirectory(EngineJson.decodeFromString(ListSerializer(MerchantEntry.serializer()), readResource("/impeesa/merchants.json")))
    }
}
