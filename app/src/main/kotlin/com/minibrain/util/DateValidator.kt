package com.minibrain.util

import java.time.LocalDate

/** 年の範囲は 1990..[today] の年。[today] はテストで固定の日付を渡すため（端末の時計と食い違わないよう全関数で受け取る） */
object DateValidator {
    private fun yearRange(today: LocalDate): IntRange = 1990..today.year

    fun parseDay(y: String, m: String, d: String = "1", today: LocalDate = LocalDate.now()): String? = runCatching {
        val date = LocalDate.of(y.toInt(), m.toInt(), d.toInt())
        if (date.year in yearRange(today)) date.toString() else null
    }.getOrNull()

    fun parseMonth(y: String, m: String, today: LocalDate = LocalDate.now()): String? = parseDay(y, m, "1", today)

    fun parseDayNotInFuture(y: String, m: String, d: String = "1", today: LocalDate = LocalDate.now()): String? = runCatching {
        val date = LocalDate.of(y.toInt(), m.toInt(), d.toInt())
        if (date.year in yearRange(today) && !date.isAfter(today)) date.toString() else null
    }.getOrNull()

    fun parseMonthNotInFuture(y: String, m: String, today: LocalDate = LocalDate.now()): String? =
        parseDayNotInFuture(y, m, "1", today)
}
