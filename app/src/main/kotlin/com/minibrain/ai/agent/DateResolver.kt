package com.minibrain.ai.agent

import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class DateRange(val start: LocalDate, val end: LocalDate)

object DateResolver {

    private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH_DAY_RE = Regex("""(\d{1,2})月(\d{1,2})日""")
    private val MONTH_ONLY_RE = Regex("""(\d{1,2})月""")
    private val DAYS_AGO_RE = Regex("""(\d+)\s*日[前まえ]""")
    private val YEAR_MONTH_RE = Regex("""(\d{4})年(\d{1,2})月""")
    private val YEAR_ONLY_RE = Regex("""(\d{4})年(?!\d)""")

    private val DIARY_KEYWORDS = listOf(
        "昨日", "今日", "本日", "一昨日", "おととい",
        "先週", "今週", "先月", "今月", "最近",
        "正月", "お正月", "年末", "年始",
        "去年", "昨年", "今年",
    )

    fun isDiaryQuery(question: String): Boolean =
        DIARY_KEYWORDS.any { question.contains(it) }
            || MONTH_DAY_RE.containsMatchIn(question)
            || MONTH_ONLY_RE.containsMatchIn(question)
            || DAYS_AGO_RE.containsMatchIn(question)

    fun resolveToDateStrings(question: String): List<String> {
        val today = LocalDate.now()
        val isPastYear = question.contains("去年") || question.contains("昨年")

        DAYS_AGO_RE.find(question)?.let { match ->
            val days = match.groupValues[1].toLongOrNull() ?: 0L
            return listOf(today.minusDays(days).format(FORMATTER))
        }

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
            question.contains("正月") || question.contains("お正月") || question.contains("年始") -> {
                val year = if (isPastYear) today.year - 1 else today.year
                (1..7).map { LocalDate.of(year, 1, it).format(FORMATTER) }
            }
            question.contains("年末") -> {
                // 上半期に「年末」と言ったら去年の年末を指す可能性が高い
                val year = if (isPastYear || today.monthValue <= 6) today.year - 1 else today.year
                (25..31).map { LocalDate.of(year, 12, it).format(FORMATTER) }
            }
            question.contains("最近") ->
                (0..14).map { today.minusDays(it.toLong()).format(FORMATTER) }
            else -> resolveByNumericPattern(question, today)
        }
    }

    private fun resolveByNumericPattern(question: String, today: LocalDate): List<String> {
        // X月Y日
        MONTH_DAY_RE.find(question)?.let { match ->
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            val year = resolveYear(question, today, month)
            return runCatching {
                listOf(LocalDate.of(year, month, day).format(FORMATTER))
            }.getOrElse { emptyList() }
        }

        // X月（月全体）
        MONTH_ONLY_RE.find(question)?.let { match ->
            val month = match.groupValues[1].toInt()
            val year = resolveYear(question, today, month)
            return runCatching {
                val start = LocalDate.of(year, month, 1)
                val end = minOf(start.withDayOfMonth(start.lengthOfMonth()), today)
                generateSequence(start) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(end) }
                    .map { it.format(FORMATTER) }
                    .toList()
            }.getOrElse { emptyList() }
        }

        return emptyList()
    }

    fun resolveDateRange(question: String, today: LocalDate = LocalDate.now()): DateRange? {
        // N年前のX月 (e.g., 5年前の3月)
        Regex("""(\d+)\s*年[前まえ]の(\d{1,2})月""").find(question)?.let { match ->
            val years = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val year = today.year - years
            return runCatching {
                val start = LocalDate.of(year, month, 1)
                DateRange(start, start.withDayOfMonth(start.lengthOfMonth()))
            }.getOrNull()
        }

        // N年前の春/夏/秋/冬
        Regex("""(\d+)\s*年[前まえ](?:の)?(春|夏|秋|冬)""").find(question)?.let { match ->
            val years = match.groupValues[1].toInt()
            return seasonRange(match.groupValues[2], today.year - years, today)
        }

        // YYYY年X月
        YEAR_MONTH_RE.find(question)?.let { match ->
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            return runCatching {
                val start = LocalDate.of(year, month, 1)
                val end = minOf(start.withDayOfMonth(start.lengthOfMonth()), today)
                DateRange(start, end)
            }.getOrNull()
        }

        // 去年の春/夏/秋/冬 / 今年の春 / 一昨年の夏
        Regex("""(去年|昨年|一昨年|今年)(?:の)?(春|夏|秋|冬)""").find(question)?.let { match ->
            val yearRef = match.groupValues[1]
            val year = when (yearRef) {
                "今年" -> today.year
                "一昨年" -> today.year - 2
                else -> today.year - 1
            }
            return seasonRange(match.groupValues[2], year, today)
        }

        // 去年/昨年（年全体）
        if (question.contains("去年") || question.contains("昨年")) {
            val year = today.year - 1
            return DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
        }

        // 一昨年（年全体）
        if (question.contains("一昨年")) {
            val year = today.year - 2
            return DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
        }

        // 今年（年初から今日まで）
        if (question.contains("今年")) {
            return DateRange(LocalDate.of(today.year, 1, 1), today)
        }

        // 先月
        if (question.contains("先月")) {
            val lastMonth = today.minusMonths(1)
            val start = lastMonth.withDayOfMonth(1)
            val end = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth())
            return DateRange(start, end)
        }

        // 今月
        if (question.contains("今月")) {
            return DateRange(today.withDayOfMonth(1), today)
        }

        // YYYY年（年のみ）
        YEAR_ONLY_RE.find(question)?.let { match ->
            val year = match.groupValues[1].toInt()
            return runCatching {
                val start = LocalDate.of(year, 1, 1)
                val end = if (year == today.year) today else LocalDate.of(year, 12, 31)
                DateRange(start, end)
            }.getOrNull()
        }

        return null
    }

    private fun seasonRange(season: String, year: Int, today: LocalDate): DateRange? = runCatching {
        val range = when (season) {
            "春" -> DateRange(LocalDate.of(year, 3, 1), LocalDate.of(year, 5, 31))
            "夏" -> DateRange(LocalDate.of(year, 6, 1), LocalDate.of(year, 8, 31))
            "秋" -> DateRange(LocalDate.of(year, 9, 1), LocalDate.of(year, 11, 30))
            "冬" -> {
                val febEnd = LocalDate.of(year + 1, 2, 1).let { it.withDayOfMonth(it.lengthOfMonth()) }
                DateRange(LocalDate.of(year, 12, 1), febEnd)
            }
            else -> return@runCatching null
        }
        if (range.end.isAfter(today)) DateRange(range.start, today) else range
    }.getOrNull()

    private fun resolveYear(question: String, today: LocalDate, month: Int): Int = when {
        question.contains("去年") || question.contains("昨年") -> today.year - 1
        question.contains("今年") -> today.year
        // 年の指定なし：未来の月なら去年、過去の月なら今年
        month > today.monthValue -> today.year - 1
        else -> today.year
    }
}
