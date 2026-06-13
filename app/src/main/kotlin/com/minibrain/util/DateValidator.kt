package com.minibrain.util

import java.time.LocalDate

object DateValidator {
    val YEAR_RANGE: IntRange = 1990..LocalDate.now().year

    fun parseDay(y: String, m: String, d: String): String? = runCatching {
        val date = LocalDate.of(y.toInt(), m.toInt(), d.toInt())
        if (date.year in YEAR_RANGE) date.toString() else null
    }.getOrNull()

    fun parseMonth(y: String, m: String): String? = runCatching {
        val date = LocalDate.of(y.toInt(), m.toInt(), 1)
        if (date.year in YEAR_RANGE) date.toString() else null
    }.getOrNull()

    fun parseDayNotInFuture(y: String, m: String, d: String, today: LocalDate = LocalDate.now()): String? = runCatching {
        val date = LocalDate.of(y.toInt(), m.toInt(), d.toInt())
        if (date.year in YEAR_RANGE && !date.isAfter(today)) date.toString() else null
    }.getOrNull()

    fun parseMonthNotInFuture(y: String, m: String, today: LocalDate = LocalDate.now()): String? = runCatching {
        val date = LocalDate.of(y.toInt(), m.toInt(), 1)
        if (date.year in YEAR_RANGE && !date.isAfter(today)) date.toString() else null
    }.getOrNull()
}
