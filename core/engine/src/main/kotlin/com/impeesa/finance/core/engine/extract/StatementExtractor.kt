package com.impeesa.finance.core.engine.extract

import com.impeesa.finance.core.engine.amount.AmountExtractor
import com.impeesa.finance.core.model.Money
import java.time.LocalDate

/** What a credit-card statement or due reminder says (IMPLEMENTATION_PLAN.md §5.7). */
data class StatementInfo(val totalDue: Money?, val minDue: Money?, val dueDate: LocalDate?)

/**
 * Reads total due, minimum due and due date from statement SMS, e.g.
 * "Total Amt Due Rs.6,240.00; Min Amt Due Rs.312.00; Payable by 02/10/26".
 */
object StatementExtractor {
    private val options = setOf(RegexOption.IGNORE_CASE)
    private val minCue = Regex("min(imum)?\\.?\\s*(amt\\.?|amount)?\\s*(due|payable)?\\W*$", options)
    private val limitCue = Regex("(avl|avail(able)?)\\.?\\s*(credit\\s*)?(lmt|limit)\\W*$", options)
    private val dueCue = Regex("(payment\\s*due\\s*date|due\\s*date|due\\s*on|due\\s*by|payable\\s*(by|on|before)|pay\\s*by|\\bby|\\bbefore)\\W*", options)

    private const val CUE_WINDOW = 30
    private const val DUE_DAYS_BEFORE = 10L
    private const val DUE_DAYS_AFTER = 62L

    fun extract(text: String, received: LocalDate): StatementInfo {
        var minDue: Long? = null
        var total: Long? = null
        for (candidate in AmountExtractor.extract(text)) {
            val before = text.substring(maxOf(0, candidate.start - CUE_WINDOW), candidate.start)
            when {
                limitCue.containsMatchIn(before) -> Unit
                minCue.containsMatchIn(before) -> if (minDue == null) minDue = candidate.minorUnits
                total == null -> total = candidate.minorUnits
            }
        }
        return StatementInfo(total?.let(::Money), minDue?.let(::Money), dueDate(text, received))
    }

    /**
     * The date after a "due"/"payable by" cue; otherwise the first date that is not in the past
     * (a statement's own date, "dated 20/09/26", is before the message arrived).
     */
    private fun dueDate(text: String, received: LocalDate): LocalDate? {
        dueCue.findAll(text).forEach { cue ->
            DateExtractor.extract(text.substring(cue.range.last + 1), received, DUE_DAYS_BEFORE, DUE_DAYS_AFTER)?.let { return it }
        }
        return DateExtractor.extract(text, received, daysBefore = 0, daysAfter = DUE_DAYS_AFTER)
    }
}
