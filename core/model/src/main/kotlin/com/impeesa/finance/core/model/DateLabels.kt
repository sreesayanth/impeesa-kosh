package com.impeesa.finance.core.model

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Human date labels used in lists and pickers. */
object DateLabels {
    private val dayMonth = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val dayMonthYear = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    /** Section header: "Today · Sun 11 Oct", "Yesterday · Sat 10 Oct", "Fri 9 Oct". */
    fun header(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today · " + date.format(dayMonth)
        today.minusDays(1) -> "Yesterday · " + date.format(dayMonth)
        else -> if (date.year == today.year) date.format(dayMonth) else date.format(dayMonthYear)
    }

    /** Compact chip label: "Today", "Yesterday", "Fri 9 Oct". */
    fun chip(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (date.year == today.year) date.format(dayMonth) else date.format(dayMonthYear)
    }

    /** Row metadata: time for today, otherwise a short date. */
    fun whenShort(date: LocalDate, time: LocalTime, today: LocalDate): String =
        if (date == today) time.format(clock) else date.format(shortDate)

    fun time(time: LocalTime): String = time.format(clock)
}
