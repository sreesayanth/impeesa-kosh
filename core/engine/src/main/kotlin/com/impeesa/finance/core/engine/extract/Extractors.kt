package com.impeesa.finance.core.engine.extract

import com.impeesa.finance.core.model.Channel

private val IGNORE = setOf(RegexOption.IGNORE_CASE)

/** UPI reference, RRN, transaction id. */
object ReferenceExtractor {
    private val patterns = listOf(
        Regex("UPI/(?:P2[AM]/)?(\\d{9,16})/", IGNORE),
        Regex(
            "(?:upi\\s*ref(?:erence)?(?:\\s*no\\.?|\\s*number)?|imps\\s*ref|ref(?:erence)?\\s*no\\.?|refno|ref(?:erence)?|rrn|txn\\s*id|transaction\\s*id|upi)" +
                "[\\s:#.-]*\\(?\\s*([0-9]{9,16})",
            IGNORE,
        ),
    )

    fun extract(text: String): String? = patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
}

/** How the money moved. */
object ChannelDetector {
    private val upi = Regex("\\b(upi|vpa)\\b|@(ok\\w+|ybl|ibl|axl|paytm|icici|hdfcbank|sbi|apl)\\b", IGNORE)
    private val atm = Regex("\\b(atm|withdrawn|cash withdrawal)\\b", IGNORE)
    private val autopay = Regex("\\b(mandate|nach|ecs|autopay|auto[- ]?debit|standing instruction)\\b", IGNORE)
    private val card = Regex("\\bcard\\b", IGNORE)
    private val netbanking = Regex("\\b(neft|imps|rtgs|net ?banking|mob bk|mobile banking)\\b", IGNORE)

    fun detect(text: String): Channel = when {
        atm.containsMatchIn(text) -> Channel.ATM
        autopay.containsMatchIn(text) -> Channel.AUTOPAY
        upi.containsMatchIn(text) -> Channel.UPI
        card.containsMatchIn(text) -> Channel.CARD
        netbanking.containsMatchIn(text) -> Channel.NETBANKING
        else -> Channel.OTHER
    }
}

/**
 * Who the money went to or came from, as written in the message. Ordered from most to least
 * specific; the first rule that matches wins (IMPLEMENTATION_PLAN.md §6.5).
 */
object CounterpartyExtractor {
    /** Where a payee name ends. A dot after Dr/Mr/Ms/Mrs/St is part of the name, not the end. */
    private val stop = "(?=\\s+on\\s|\\s+on$|(?<!\\b(?:Dr|Mr|Ms|St|Mrs))\\.\\s|\\.$|,|;|\\s+avl|\\s+ref|\\s+upi|\\s+via\\b|\\s+if\\b|\\s+not you|\\s+bal\\b|\\s+debited|\\s*\\(|\\s+-|$)"

    private val rules = listOf(
        Regex("UPI/(?:P2[AM]/)?\\d{6,}/([^/]+?)(?:/|\\.\\s|\\s+not you|\\s+axis|\\s+avl|\\s+total|\\.?$)", IGNORE),
        Regex("\\b(?:to|from)\\s+VPA\\s+([\\w.\\-]+@[\\w]+)", IGNORE),
        Regex("\\b(?:UPI ID|VPA)\\s+([\\w.\\-]+@[\\w]+)", IGNORE),
        Regex("\\bto\\s+([A-Z][A-Z .&']{2,40}?)(?=\\s+via\\b)"),
        Regex("\\btrf to\\s+(.+?)\\s+Ref", IGNORE),
        Regex(";\\s*([A-Za-z0-9][A-Za-z0-9 .&'\\-]{1,40}?)\\s+credited", IGNORE),
        Regex("\\bInfo:\\s*(?:UPI|IMPS|NEFT|POS)[-/]([A-Za-z0-9 .&']+?)[-/]\\d", IGNORE),
        Regex("\\bfor\\s+UPI[-/]([A-Za-z0-9 .&']+?)(?=\\s+on\\s|\\.|$)", IGNORE),
        Regex("\\bTo\\s+([A-Z][A-Za-z0-9 .&']{1,40}?)\\s+On\\s"),
        Regex("^(.{2,40}?)\\s+sent you\\b", IGNORE),
        Regex("\\bpayment of\\s+(?:₹|rs\\.?|inr)\\s*[\\d,.]+\\s+to\\s+(.+?)(?=\\s+(?:was\\s+)?successful|$stop)", IGNORE),
        Regex("\\b(?:paid|sent)\\s+(?:(?:₹|rs\\.?|inr)\\s*[\\d,.]+\\s+)?to\\s+(.+?)$stop", IGNORE),
        // ICICI cards: "spent using ICICI Bank Card XX7703 on 04-Oct-26 on Google Play."
        Regex("\\bon\\s+\\d{1,2}-[A-Za-z]{3}-\\d{2,4}\\s+on\\s+([A-Za-z][A-Za-z0-9 &*.'\\-]{1,40}?)$stop", IGNORE),
        Regex("\\bat\\s+([A-Za-z][A-Za-z0-9 &*.'\\-]{1,40}?)$stop", IGNORE),
        Regex("\\bto\\s+([\\w.\\-]+@[A-Za-z]+)", IGNORE),
        Regex("\\btowards\\s+([A-Za-z0-9][A-Za-z0-9 /&.\\-]{1,60}?)(?=\\s+for\\s|\\s+on\\s|\\.\\s|\\.$|$)", IGNORE),
        Regex("\\b(?:Transferred to|to)\\s+(?:Mr\\.?|Ms\\.?|Mrs\\.?)?\\s*([A-Z][A-Za-z .]{1,40}?)$stop"),
        Regex("\\bfrom\\s+([A-Za-z0-9][A-Za-z0-9 .@/\\-_]{1,40}?)$stop", IGNORE),
        Regex("\\bby\\s+([A-Z][A-Z .]{2,40}?)$stop"),
        // "debited … for JIO RECHARGE." — only all-caps names, so "for NEFT Cr-…" or "for e-mandate" never match.
        Regex("\\bfor\\s+([A-Z][A-Z0-9 &.]{2,40}?)(?=\\.\\s|\\.$|$)"),
    )

    /** Words that mean the rule caught bank boilerplate, not a payee. */
    private val noise = Regex("^(your|the|a/c|ac|account|hdfc bank|icici bank|kotak bank|sbi|bank|card|you|upi|mob bk|neft|imps)\\b", IGNORE)

    fun extract(text: String): String? {
        for (rule in rules) {
            val value = rule.find(text)?.groupValues?.get(1)?.let(::clean) ?: continue
            if (value.length >= 2 && !noise.containsMatchIn(value)) return value
        }
        return null
    }

    private fun clean(raw: String): String =
        raw.trim().trim('.', ',', '-', ':', ';').replace(Regex("\\s+"), " ").take(48)
}
