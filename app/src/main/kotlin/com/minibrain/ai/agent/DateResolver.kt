package com.minibrain.ai.agent

import android.util.Log
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class DateRange(val start: LocalDate, val end: LocalDate)

object DateResolver {

    private const val TAG = "DateResolver"
    private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH_DAY_RE = Regex("""(\d{1,2})月(\d{1,2})日""")
    private val MONTH_ONLY_RE = Regex("""(\d{1,2})月""")
    private val DAYS_AGO_RE = Regex("""(\d+)\s*日[前まえ]""")
    private val YEAR_MONTH_RE = Regex("""(\d{4})年(\d{1,2})月""")
    private val YEAR_ONLY_RE = Regex("""(\d{4})年(?!\d)""")

    // 曜日指定: 先週の月/火/水/木/金/土/日（曜日?）
    private val LAST_WEEK_DOW_RE = Regex("""先週(?:の)?(月|火|水|木|金|土|日)(?:曜日?)?""")
    private val THIS_WEEK_DOW_RE = Regex("""今週(?:の)?(月|火|水|木|金|土|日)(?:曜日?)?""")

    // クォーター: Q1〜Q4 / 第1〜4四半期
    private val QUARTER_RE = Regex("""[Qq]([1-4])|第([1-4])四半期""")
    private val YEAR_QUARTER_RE = Regex("""(去年|昨年|今年|一昨年|\d{4}年)(?:の)?(?:[Qq]([1-4])|第([1-4])四半期)""")

    // ドット/スラッシュ区切り日付: 2024.03.01 / 2024/03/01
    private val DOT_SLASH_DATE_RE = Regex("""(\d{4})[./](\d{1,2})[./](\d{1,2})""")

    // 「いつ」「何月」など時期を尋ねるクエリの判定。
    // AgentPipeline / CoverageChecker / LlmReranker でファイルをまたいで使うため、ここに一本化。
    private val DATE_QUERY_RE = Regex("""いつ|何月|何日|何年|年前|月前|去年|先月|先週|いつから|いつまで""")

    fun isDateQuery(question: String): Boolean = DATE_QUERY_RE.containsMatchIn(question)

    // 元号: エントリを追加するだけで resolveEraYear / isDiaryQuery の両方に反映される
    private data class EraEntry(val pattern: Regex, val gregorianOffset: Int)
    private val ERA_LIST = listOf(
        EraEntry(Regex("""(?:令和|R)(\d{1,2})年""", RegexOption.IGNORE_CASE), 2018),
        EraEntry(Regex("""(?:平成|H)(\d{1,2})年""", RegexOption.IGNORE_CASE), 1988),
        EraEntry(Regex("""(?:昭和|S)(\d{1,2})年""", RegexOption.IGNORE_CASE), 1925),
        EraEntry(Regex("""(?:大正|T)(\d{1,2})年""", RegexOption.IGNORE_CASE), 1911),
    )

    private val WEEKDAY_MAP = mapOf(
        "月" to DayOfWeek.MONDAY,
        "火" to DayOfWeek.TUESDAY,
        "水" to DayOfWeek.WEDNESDAY,
        "木" to DayOfWeek.THURSDAY,
        "金" to DayOfWeek.FRIDAY,
        "土" to DayOfWeek.SATURDAY,
        "日" to DayOfWeek.SUNDAY
    )

    private val DIARY_KEYWORDS = listOf(
        "昨日", "今日", "本日", "一昨日", "おととい",
        "先週", "今週", "先月", "今月", "最近",
        "正月", "お正月", "年末", "年始",
        "去年", "昨年", "今年",
        "四半期", "令和", "平成", "昭和", "大正",
    )

    fun isDiaryQuery(question: String): Boolean =
        DIARY_KEYWORDS.any { question.contains(it) }
            || MONTH_DAY_RE.containsMatchIn(question)
            || MONTH_ONLY_RE.containsMatchIn(question)
            || DAYS_AGO_RE.containsMatchIn(question)
            || LAST_WEEK_DOW_RE.containsMatchIn(question)
            || THIS_WEEK_DOW_RE.containsMatchIn(question)
            || QUARTER_RE.containsMatchIn(question)
            || DOT_SLASH_DATE_RE.containsMatchIn(question)
            || ERA_LIST.any { it.pattern.containsMatchIn(question) }

    // today パラメータを公開することでユニットテストで固定日付を注入できる
    fun resolveToDateStrings(question: String, today: LocalDate = LocalDate.now()): List<String> {
        val isPastYear = question.contains("去年") || question.contains("昨年")

        DAYS_AGO_RE.find(question)?.let { match ->
            val days = match.groupValues[1].toLongOrNull() ?: 0L
            return listOf(today.minusDays(days).format(FORMATTER))
        }

        // ドット/スラッシュ区切り日付: 2024.03.01 / 2024/03/01
        DOT_SLASH_DATE_RE.find(question)?.let { match ->
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            return runCatching {
                listOf(LocalDate.of(year, month, day).format(FORMATTER))
            }.onFailure { Log.w(TAG, "Invalid dot/slash date: $year/$month/$day", it) }
             .getOrElse { emptyList() }
        }

        // 先週の曜日: 先週の月曜基点で対象曜日を返す
        LAST_WEEK_DOW_RE.find(question)?.let { match ->
            val dow = WEEKDAY_MAP[match.groupValues[1]] ?: return@let
            val lastWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
            val target = lastWeekMonday.with(TemporalAdjusters.nextOrSame(dow))
            return runCatching { listOf(target.format(FORMATTER)) }
                .onFailure { Log.w(TAG, "Failed to resolve last week day: $dow", it) }
                .getOrElse { emptyList() }
        }

        // 今週の曜日: 今週月曜基点で対象曜日（未来は返さない）
        THIS_WEEK_DOW_RE.find(question)?.let { match ->
            val dow = WEEKDAY_MAP[match.groupValues[1]] ?: return@let
            val thisWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val target = thisWeekMonday.with(TemporalAdjusters.nextOrSame(dow))
            if (!target.isAfter(today)) {
                return runCatching { listOf(target.format(FORMATTER)) }
                    .onFailure { Log.w(TAG, "Failed to resolve this week day: $dow", it) }
                    .getOrElse { emptyList() }
            }
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
            }.onFailure { Log.w(TAG, "Invalid month/day: $year/$month/$day", it) }
             .getOrElse { emptyList() }
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
            }.onFailure { Log.w(TAG, "Invalid month-only: $year/$month", it) }
             .getOrElse { emptyList() }
        }

        return emptyList()
    }

    fun resolveDateRange(question: String, today: LocalDate = LocalDate.now()): DateRange? {
        // 元号解決: ERA_LIST を順番にマッチさせて西暦年に変換
        resolveEraYear(question)?.let { year ->
            val monthMatch = Regex("""年(\d{1,2})月""").find(question)
            return if (monthMatch != null) {
                val month = monthMatch.groupValues[1].toInt()
                runCatching {
                    val start = LocalDate.of(year, month, 1)
                    val end = minOf(start.withDayOfMonth(start.lengthOfMonth()), today)
                    DateRange(start, end)
                }.onFailure { Log.w(TAG, "Invalid era year+month: $year/$month", it) }
                 .getOrNull()
            } else {
                runCatching {
                    val start = LocalDate.of(year, 1, 1)
                    val end = if (year == today.year) today else LocalDate.of(year, 12, 31)
                    DateRange(start, end)
                }.onFailure { Log.w(TAG, "Invalid era year: $year", it) }
                 .getOrNull()
            }
        }

        // クォーター解決（年指定あり）: 去年のQ3 / 2023年Q2 / 今年の第1四半期
        YEAR_QUARTER_RE.find(question)?.let { match ->
            val yearRef = match.groupValues[1]
            val q = match.groupValues[2].ifEmpty { match.groupValues[3] }.toIntOrNull() ?: return@let
            val year = when (yearRef) {
                "今年" -> today.year
                "去年", "昨年" -> today.year - 1
                "一昨年" -> today.year - 2
                else -> yearRef.removeSuffix("年").toIntOrNull() ?: return@let
            }
            return quarterRange(q, year, today)
        }

        // クォーター解決（年指定なし）: Q3 / 第2四半期
        QUARTER_RE.find(question)?.let { match ->
            val q = match.groupValues[1].ifEmpty { match.groupValues[2] }.toIntOrNull() ?: return@let
            val isPastYear = question.contains("去年") || question.contains("昨年")
            val year = if (isPastYear) today.year - 1 else today.year
            return quarterRange(q, year, today)
        }

        // N年前のX月 (e.g., 5年前の3月)
        Regex("""(\d+)\s*年[前まえ]の(\d{1,2})月""").find(question)?.let { match ->
            val years = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val year = today.year - years
            return runCatching {
                val start = LocalDate.of(year, month, 1)
                DateRange(start, start.withDayOfMonth(start.lengthOfMonth()))
            }.onFailure { Log.w(TAG, "Invalid N-years-ago month: $year/$month", it) }
             .getOrNull()
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
            }.onFailure { Log.w(TAG, "Invalid year+month: $year/$month", it) }
             .getOrNull()
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
            }.onFailure { Log.w(TAG, "Invalid year-only: $year", it) }
             .getOrNull()
        }

        return null
    }

    private fun resolveEraYear(question: String): Int? {
        for ((pattern, offset) in ERA_LIST) {
            pattern.find(question)?.let { return offset + it.groupValues[1].toInt() }
        }
        return null
    }

    private fun quarterRange(q: Int, year: Int, today: LocalDate): DateRange? = runCatching {
        val startMonth = (q - 1) * 3 + 1
        val start = LocalDate.of(year, startMonth, 1)
        val endMonth = startMonth + 2
        val end = LocalDate.of(year, endMonth, 1).withDayOfMonth(
            LocalDate.of(year, endMonth, 1).lengthOfMonth()
        )
        DateRange(start, minOf(end, today))
    }.onFailure { Log.w(TAG, "Invalid quarter: Q$q $year", it) }
     .getOrNull()

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
    }.onFailure { Log.w(TAG, "Invalid season: $season $year", it) }
     .getOrNull()

    private fun resolveYear(question: String, today: LocalDate, month: Int): Int = when {
        question.contains("去年") || question.contains("昨年") -> today.year - 1
        question.contains("今年") -> today.year
        // 年の指定なし：未来の月なら去年、過去の月なら今年
        month > today.monthValue -> today.year - 1
        else -> today.year
    }
}
