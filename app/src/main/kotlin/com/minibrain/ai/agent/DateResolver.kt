package com.minibrain.ai.agent

import java.time.LocalDate
import java.time.format.DateTimeFormatter

object DateResolver {

    private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val DIARY_KEYWORDS = listOf(
        "昨日", "今日", "本日", "一昨日", "おととい",
        "先週", "今週", "先月", "今月", "最近",
    )

    fun isDiaryQuery(question: String): Boolean =
        DIARY_KEYWORDS.any { question.contains(it) }

    fun resolveToDateStrings(question: String): List<String> {
        val today = LocalDate.now()
        return when {
            question.contains("一昨日") || question.contains("おととい") ->
                listOf(today.minusDays(2).format(FORMATTER))
            question.contains("昨日") ->
                listOf(today.minusDays(1).format(FORMATTER))
            question.contains("今日") || question.contains("本日") ->
                listOf(today.format(FORMATTER))
            question.contains("今週") -> {
                val dayOfWeek = today.dayOfWeek.value
                (0 until dayOfWeek).map { today.minusDays(it.toLong()).format(FORMATTER) }
            }
            question.contains("先週") ->
                (1..7).map { today.minusDays(it.toLong()).format(FORMATTER) }
            question.contains("今月") -> {
                val start = today.withDayOfMonth(1)
                generateSequence(start) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(today) }
                    .map { it.format(FORMATTER) }
                    .toList()
            }
            question.contains("先月") -> {
                val lastMonth = today.minusMonths(1)
                val start = lastMonth.withDayOfMonth(1)
                val end = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth())
                generateSequence(start) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(end) }
                    .map { it.format(FORMATTER) }
                    .toList()
            }
            question.contains("最近") ->
                (0..14).map { today.minusDays(it.toLong()).format(FORMATTER) }
            else -> emptyList()
        }
    }
}
