package com.impeesa.finance.core.engine.extract

import java.time.DateTimeException
import java.time.LocalDate

/**
 * Finds the transaction date stated in a message. Accepts the formats Indian banks use and
 * rejects anything implausible relative to when the message arrived (IMPLEMENTATION_PLAN.md §6.5).
 */
object DateExtractor {
    private val options = setOf(RegexOption.IGNORE_CASE)
    private val iso = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})", options)
    private val numeric = Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2,4})\\b", options)
    private val named = Regex("\\b(\\d{1,2})[-\\s]?([A-Za-z]{3})[a-z]*[-\\s,]*(\\d{2,4})\\b", options)

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun extract(text: String, received: LocalDate): LocalDate? {
        val candidates = buildList {
            iso.findAll(text).forEach { m -> add(m.range.first to date(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt())) }
            numeric.findAll(text).forEach { m -> add(m.range.first to date(m.groupValues[3], m.groupValues[2].toInt(), m.groupValues[1].toInt())) }
            named.findAll(text).forEach { m ->
                val month = months.indexOf(m.groupValues[2].lowercase()) + 1
                if (month > 0) add(m.range.first to date(m.groupValues[3], month, m.groupValues[1].toInt()))
            }
        }
        return candidates
            .sortedBy { it.first }
            .mapNotNull { it.second }
            .firstOrNull { isPlausible(it, received) }
    }

    private fun date(year: String, month: Int, day: Int): LocalDate? {
        val fullYear = when (year.length) {
            2 -> 2000 + year.toInt()
            4 -> year.toInt()
            else -> return null
        }
        return try {
            LocalDate.of(fullYear, month, day)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun isPlausible(date: LocalDate, received: LocalDate): Boolean =
        !date.isBefore(received.minusDays(45)) && !date.isAfter(received.plusDays(1))
}
