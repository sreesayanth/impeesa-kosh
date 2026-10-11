package com.impeesa.finance.core.model

import java.time.LocalDate
import java.time.YearMonth

/** Dates stored as yyyyMMdd integers so grouping by day or month is a cheap indexed query. */
object DateKey {
    fun of(date: LocalDate): Int = date.year * 10_000 + date.monthValue * 100 + date.dayOfMonth

    fun toLocalDate(key: Int): LocalDate = LocalDate.of(key / 10_000, (key / 100) % 100, key % 100)

    fun monthRange(month: YearMonth): IntRange = of(month.atDay(1))..of(month.atEndOfMonth())
}
