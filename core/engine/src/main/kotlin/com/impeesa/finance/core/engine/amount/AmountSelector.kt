package com.impeesa.finance.core.engine.amount

import com.impeesa.finance.core.engine.classify.Verbs

/** The amounts in a message, sorted by role. */
data class AmountPick(
    val transaction: AmountCandidate?,
    val balance: Long?,
    val limit: Long?,
    /** True when the transaction amount had a currency marker (Rs, INR, ₹ …). */
    val hasMarker: Boolean,
    /** True when several amounts could be the transaction amount and none sits next to a verb. */
    val ambiguous: Boolean,
) {
    val hasAny: Boolean get() = transaction != null || balance != null || limit != null
}

/**
 * Decides which amount in a message is the transaction, which is the balance and which is
 * the available limit (IMPLEMENTATION_PLAN.md §6.5).
 */
object AmountSelector {
    private val options = setOf(RegexOption.IGNORE_CASE)
    private val limitCue = Regex("(avl\\.?\\s*l(i)?m(i)?t|avail(able)?\\.?\\s*(credit\\s*)?(limit|credit)|credit\\s*limit|limit)\\W*(is|of)?\\W*$", options)
    private val balanceCue = Regex("(\\bbal(ance)?|avail(able)?\\.?\\s*bal(ance)?)\\s*(is|:|of)?\\W*$", options)

    /** SBI-style amounts with no currency marker: "debited by 250.0". */
    private val bare = Regex("\\b(?:debited|credited)\\s+(?:by|for|with)\\s+([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\b", options)

    private const val CUE_WINDOW = 30
    private const val VERB_WINDOW = 45

    fun pick(text: String): AmountPick {
        var balance: Long? = null
        var limit: Long? = null
        val transactional = mutableListOf<AmountCandidate>()
        for (candidate in AmountExtractor.extract(text)) {
            val before = text.substring(maxOf(0, candidate.start - CUE_WINDOW), candidate.start)
            when {
                limitCue.containsMatchIn(before) -> if (limit == null) limit = candidate.minorUnits
                balanceCue.containsMatchIn(before) -> if (balance == null) balance = candidate.minorUnits
                else -> transactional += candidate
            }
        }
        if (transactional.isEmpty()) {
            val match = bare.find(text)
            val minor = match?.let { AmountExtractor.parseMinorUnits(it.groupValues[1]) }
            val candidate = if (match != null && minor != null) {
                val group = match.groups[1]!!
                AmountCandidate(minor, "INR", group.range.first, group.range.last + 1)
            } else {
                null
            }
            return AmountPick(candidate, balance, limit, hasMarker = false, ambiguous = false)
        }
        val verbPositions = Verbs.positions(text)
        val nearVerb = transactional.firstOrNull { candidate ->
            verbPositions.any { pos -> pos in (candidate.start - VERB_WINDOW)..(candidate.end + VERB_WINDOW) }
        }
        return AmountPick(
            transaction = nearVerb ?: transactional.first(),
            balance = balance,
            limit = limit,
            hasMarker = true,
            ambiguous = nearVerb == null && transactional.size > 1,
        )
    }
}
