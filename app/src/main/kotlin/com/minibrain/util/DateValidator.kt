package com.minibrain.util

import java.time.LocalDate

object DateValidator {
    val YEAR_RANGE: IntRange
        get() = 1990..LocalDate.now().year

    fun parseDay(y: String, m: String, d: String = "1"): String? = runCatching {
        val date = LocalDate.of(y.toInt(), m.toInt(), d.toInt())
        if (date.year in YEAR_RANGE) date.toString() else null
    }.getOrNull()

    fun parseMonth(y: String, m: String): String? = parseDay(y, m, "1")

    fun parseDayNotInFuture(y: String, m: String, d: String = "1", today: LocalDate = LocalDate.now()): String? = runCatching {
        val date = LocalDate.of(y.toInt(), m.toInt(), d.toInt())
        if (date.year in YEAR_RANGE && !date.isAfter(today)) date.toString() else null
    }.getOrNull()

    fun parseMonthNotInFuture(y: String, m: String, today: LocalDate = LocalDate.now()): String? =
        parseDayNotInFuture(y, m, "1", today)
}
