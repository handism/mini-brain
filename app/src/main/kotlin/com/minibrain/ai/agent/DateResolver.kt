package com.minibrain.ai.agent

import com.minibrain.util.DateValidator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import timber.log.Timber

data class DateRange(val start: LocalDate, val end: LocalDate)

object DateResolver {

    private const val TAG = "DateResolver"
    private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val MONTH_DAY_RE = Regex("""(\d{1,2})月(\d{1,2})日""")
    private val MONTH_ONLY_RE = Regex("""(\d{1,2})月""")
    private val DAYS_AGO_RE = Regex("""(\d+)\s*日[前まえ]""")
    private val YEAR_MONTH_RE = Regex("""(\d{4})年(\d{1,2})月""")
    private val YEAR_ONLY_RE = Regex("""(\d{4})年(?!\d)""")
    private val ERA_MONTH_RE = Regex("""年(\d{1,2})月""")
    private val ERA_MONTH_DAY_RE = Regex("""年(\d{1,2})月(\d{1,2})日""")
    private val N_YEARS_AGO_MONTH_RE = Regex("""(\d+)\s*年[前まえ]の(\d{1,2})月""")
    private val N_YEARS_AGO_SEASON_RE = Regex("""(\d+)\s*年[前まえ](?:の)?(春|夏|秋|冬)""")
    // 相対年は長いもの（「一昨年」「再来年」）を先に並べる。短い方が先だと「昨年」「来年」の部分に一致する
    private val RELATIVE_SEASON_RE = Regex("""(一昨年|再来年|来年|去年|昨年|今年)(?:の)?(春|夏|秋|冬)""")
    private val YEAR_SEASON_RE = Regex("""(\d{4})年(?:の)?(春|夏|秋|冬)""")
    // 「去年の3月」「一昨年の3月5日」。年の数字が無いので YEAR_MONTH_RE では拾えない（ADR-067）
    private val RELATIVE_YEAR_MONTH_RE = Regex("""(一昨年|再来年|来年|去年|昨年|今年)(?:の)?(\d{1,2})月(?:(\d{1,2})日)?""")

    // 年まで入った特定の日付。1 日だけの期間として扱い、日付ヒットを先頭に固定させる（ADR-042）
    private val FULL_DATE_RES = listOf(
        Regex("""(?<!\d)(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)"""),
        Regex("""(?<!\d)(\d{4})年(\d{1,2})月(\d{1,2})日"""),
        Regex("""(?<!\d)(\d{4})(\d{2})(\d{2})(?!\d)"""),
    )

    // 曜日指定: 先週の月/火/水/木/金/土/日（曜日?）
    private val LAST_WEEK_DOW_RE = Regex("""先週(?:の)?(月|火|水|木|金|土|日)(?:曜日?)?""")
    private val THIS_WEEK_DOW_RE = Regex("""今週(?:の)?(月|火|水|木|金|土|日)(?:曜日?)?""")

    // クォーター: Q1〜Q4 / 第1〜4四半期
    private val QUARTER_RE = Regex("""[Qq]([1-4])|第([1-4])四半期""")
    private val YEAR_QUARTER_RE = Regex("""(一昨年|再来年|来年|去年|昨年|今年|\d{4}年)(?:の)?(?:[Qq]([1-4])|第([1-4])四半期)""")

    // ドット/スラッシュ区切り日付: 2024.03.01 / 2024/03/01
    private val DOT_SLASH_DATE_RE = Regex("""(\d{4})[./](\d{1,2})[./](\d{1,2})""")

    // 「いつ」「何月」など時期を尋ねるクエリの判定。
    // AgentPipeline / CoverageChecker / LlmReranker でファイルをまたいで使うため、ここに一本化。
    private val DATE_QUERY_RE = Regex("""いつ|何月|何日|何年|年前|月前|去年|先月|先週|いつから|いつまで""")

    fun isDateQuery(question: String): Boolean = DATE_QUERY_RE.containsMatchIn(question)

    // 元号: エントリを追加するだけで resolveEraYear / isDiaryQuery の両方に反映される。
    // 「元年」も 1 年として読む。拾えないと月日だけから年を推定し、別の年を検索していた（ADR-072）
    private data class EraEntry(val pattern: Regex, val gregorianOffset: Int)
    private val ERA_LIST = listOf(
        EraEntry(Regex("""(?:令和|R)(\d{1,2}|元)年""", RegexOption.IGNORE_CASE), 2018),
        EraEntry(Regex("""(?:平成|H)(\d{1,2}|元)年""", RegexOption.IGNORE_CASE), 1988),
        EraEntry(Regex("""(?:昭和|S)(\d{1,2}|元)年""", RegexOption.IGNORE_CASE), 1925),
        EraEntry(Regex("""(?:大正|T)(\d{1,2}|元)年""", RegexOption.IGNORE_CASE), 1911),
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
        // まだ始まっていない期間（「2027年3月5日」「2026年12月」）は resolveDateRange が null を返す。
        // 下の各段は書かれた年を見ずに月日だけで年を推定するので、ここで止めないと別の年の日付になる（ADR-069）
        if (resolveDateRangeUnchecked(question, today)?.start?.isAfter(today) == true) return emptyList()
        val dates = resolveDaysAgo(question, today)
            ?: resolveDotSlashDate(question, today)
            ?: resolveLastWeekDow(question, today)
            ?: resolveThisWeekDow(question, today)
            ?: resolveRelativeKeywords(question, today)
            ?: resolveByNumericPattern(question, today)
        // 「年末」の 12/25〜31 や「正月」の 1/1〜7 は今日をまたぐことがある。未来の日付の文書は無いので外す（ADR-072）
        val todayString = today.format(FORMATTER)
        return dates.filter { it <= todayString }
    }

    private fun resolveDaysAgo(question: String, today: LocalDate): List<String>? {
        return DAYS_AGO_RE.find(question)?.let { match ->
            val days = match.groupValues[1].toLongOrNull() ?: 0L
            listOf(today.minusDays(days).format(FORMATTER))
        }
    }

    private fun resolveDotSlashDate(question: String, today: LocalDate): List<String>? {
        return DOT_SLASH_DATE_RE.find(question)?.let { match ->
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            runCatching {
                // 来年以降の日付は resolveDateRange の年の範囲で落ちて上のガードを通るので、ここでも未来を外す。
                // null でなく空を返し、下の段で月日から別の年を推定させない
                val date = LocalDate.of(year, month, day)
                if (date.isAfter(today)) emptyList() else listOf(date.format(FORMATTER))
            }.onFailure { Timber.tag(TAG).w(it, "Invalid dot/slash date: $year/$month/$day") }
             .getOrElse { emptyList() }
        }
    }

    private fun resolveLastWeekDow(question: String, today: LocalDate): List<String>? {
        return LAST_WEEK_DOW_RE.find(question)?.let { match ->
            val dow = WEEKDAY_MAP[match.groupValues[1]] ?: return@let null
            val lastWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
            val target = lastWeekMonday.with(TemporalAdjusters.nextOrSame(dow))
            runCatching { listOf(target.format(FORMATTER)) }
                .onFailure { Timber.tag(TAG).w(it, "Failed to resolve last week day: $dow") }
                .getOrElse { emptyList() }
        }
    }

    private fun resolveThisWeekDow(question: String, today: LocalDate): List<String>? {
        return THIS_WEEK_DOW_RE.find(question)?.let { match ->
            val dow = WEEKDAY_MAP[match.groupValues[1]] ?: return@let null
            val thisWeekMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val target = thisWeekMonday.with(TemporalAdjusters.nextOrSame(dow))
            if (!target.isAfter(today)) {
                runCatching { listOf(target.format(FORMATTER)) }
                    .onFailure { Timber.tag(TAG).w(it, "Failed to resolve this week day: $dow") }
                    .getOrElse { emptyList() }
            } else {
                null
            }
        }
    }

    private fun resolveRelativeKeywords(question: String, today: LocalDate): List<String>? {
        val yearOffset = relativeYearOffset(question)
        return when {
            question.contains("一昨日") || question.contains("おととい") ->
                listOf(today.minusDays(2).format(FORMATTER))
            question.contains("昨日") ->
                listOf(today.minusDays(1).format(FORMATTER))
            question.contains("今日") || question.contains("本日") ->
                listOf(today.format(FORMATTER))
            question.contains("今週") -> {
                val dayOfWeek = today.dayOfWeek.value
                List(dayOfWeek) { today.minusDays(it.toLong()).format(FORMATTER) }
            }
            question.contains("先週") ->
                List(7) { today.minusDays((it + 1).toLong()).format(FORMATTER) }
            question.contains("今月") -> {
                val start = today.withDayOfMonth(1)
                val days = today.dayOfMonth
                List(days) { start.plusDays(it.toLong()).format(FORMATTER) }
            }
            question.contains("先月") -> {
                val lastMonth = today.minusMonths(1)
                val start = lastMonth.withDayOfMonth(1)
                val days = lastMonth.lengthOfMonth()
                List(days) { start.plusDays(it.toLong()).format(FORMATTER) }
            }
            question.contains("正月") || question.contains("お正月") || question.contains("年始") -> {
                val year = today.year - (yearOffset ?: 0)
                List(7) { LocalDate.of(year, 1, it + 1).format(FORMATTER) }
            }
            question.contains("年末") -> {
                // 年の指定が無ければ、今年の年末（12/25〜）が来るまでは去年の年末を指す（ADR-072）
                val year = when {
                    yearOffset != null -> today.year - yearOffset
                    today.isBefore(LocalDate.of(today.year, 12, 25)) -> today.year - 1
                    else -> today.year
                }
                List(7) { LocalDate.of(year, 12, 25 + it).format(FORMATTER) }
            }
            question.contains("最近") ->
                List(15) { today.minusDays(it.toLong()).format(FORMATTER) }
            else -> null
        }
    }

    private fun resolveByNumericPattern(question: String, today: LocalDate): List<String> {
        // X月Y日
        MONTH_DAY_RE.find(question)?.let { match ->
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            val year = resolveYear(question, today, month, day)
            return runCatching {
                listOf(LocalDate.of(year, month, day).format(FORMATTER))
            }.onFailure { Timber.tag(TAG).w(it, "Invalid month/day: $year/$month/$day") }
             .getOrElse { emptyList() }
        }

        // X月（月全体）
        MONTH_ONLY_RE.find(question)?.let { match ->
            val month = match.groupValues[1].toInt()
            val year = resolveYear(question, today, month, day = 1)
            return runCatching {
                val start = LocalDate.of(year, month, 1)
                val end = minOf(start.withDayOfMonth(start.lengthOfMonth()), today)
                val days = maxOf(0, java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt() + 1)
                List(days) { start.plusDays(it.toLong()).format(FORMATTER) }
            }.onFailure { Timber.tag(TAG).w(it, "Invalid month-only: $year/$month") }
             .getOrElse { emptyList() }
        }

        return emptyList()
    }

    // 各段は終わりだけを今日で切るので、まだ始まっていない期間（今日が 10 月の「今年の冬」「2026年12月」）は
    // 開始 > 終了になる。documentDate は未来の日付を持たないのでヒットせず、フォルダ固定も止まるため期間なしにする（ADR-068）
    fun resolveDateRange(question: String, today: LocalDate = LocalDate.now()): DateRange? =
        resolveDateRangeUnchecked(question, today)?.takeUnless { it.start.isAfter(today) }

    private fun resolveDateRangeUnchecked(question: String, today: LocalDate): DateRange? {
        return resolveFullDateRange(question, today)
            ?: resolveEraRange(question, today)
            ?: resolveQuarterRange(question, today)
            ?: resolveNYearsAgoRange(question, today)
            ?: resolveYearMonthRange(question, today)
            ?: resolveRelativeYearMonthRange(question, today)
            ?: resolveRelativeSeasonRange(question, today)
            ?: resolveYearSeasonRange(question, today)
            ?: resolveRelativeDateRange(question, today)
            ?: resolveYearOnlyRange(question, today)
    }

    private fun resolveFullDateRange(question: String, today: LocalDate): DateRange? {
        for (re in FULL_DATE_RES) {
            for (match in re.findAll(question)) {
                val (y, m, d) = match.destructured
                // 8 桁の注文番号などを日付と取り違えないよう、実在する日付かつ年の範囲内だけ採る
                val date = DateValidator.parseDay(y, m, d, today)?.let(LocalDate::parse) ?: continue
                return DateRange(date, date)
            }
        }
        return null
    }

    private fun resolveEraRange(question: String, today: LocalDate): DateRange? {
        val year = resolveEraYear(question) ?: return null
        // 日まであれば西暦の特定日付と同じく 1 日だけの期間にする（ADR-042 / ADR-076）
        ERA_MONTH_DAY_RE.find(question)?.let { match ->
            val (m, d) = match.destructured
            return runCatching { LocalDate.of(year, m.toInt(), d.toInt()).let { DateRange(it, it) } }
                .onFailure { Timber.tag(TAG).w(it, "Invalid era date: $year/$m/$d") }
                .getOrNull()
        }
        val monthMatch = ERA_MONTH_RE.find(question)
        return if (monthMatch != null) {
            val month = monthMatch.groupValues[1].toInt()
            runCatching {
                val start = LocalDate.of(year, month, 1)
                val end = minOf(start.withDayOfMonth(start.lengthOfMonth()), today)
                DateRange(start, end)
            }.onFailure { Timber.tag(TAG).w(it, "Invalid era year+month: $year/$month") }
             .getOrNull()
        } else {
            runCatching {
                val start = LocalDate.of(year, 1, 1)
                val end = if (year == today.year) today else LocalDate.of(year, 12, 31)
                DateRange(start, end)
            }.onFailure { Timber.tag(TAG).w(it, "Invalid era year: $year") }
             .getOrNull()
        }
    }

    private fun resolveQuarterRange(question: String, today: LocalDate): DateRange? {
        YEAR_QUARTER_RE.find(question)?.let { match ->
            val yearRef = match.groupValues[1]
            val q = match.groupValues[2].ifEmpty { match.groupValues[3] }.toIntOrNull() ?: return@let
            val year = relativeYearOffset(yearRef)?.let { today.year - it }
                ?: yearRef.removeSuffix("年").toIntOrNull() ?: return@let
            return quarterRange(q, year, today)
        }

        QUARTER_RE.find(question)?.let { match ->
            val q = match.groupValues[1].ifEmpty { match.groupValues[2] }.toIntOrNull() ?: return@let
            val year = today.year - (relativeYearOffset(question) ?: 0)
            return quarterRange(q, year, today)
        }

        return null
    }

    private fun resolveNYearsAgoRange(question: String, today: LocalDate): DateRange? {
        N_YEARS_AGO_MONTH_RE.find(question)?.let { match ->
            val years = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            val year = today.year - years
            return runCatching {
                val start = LocalDate.of(year, month, 1)
                DateRange(start, start.withDayOfMonth(start.lengthOfMonth()))
            }.onFailure { Timber.tag(TAG).w(it, "Invalid N-years-ago month: $year/$month") }
             .getOrNull()
        }

        N_YEARS_AGO_SEASON_RE.find(question)?.let { match ->
            val years = match.groupValues[1].toInt()
            return seasonRange(match.groupValues[2], today.year - years, today)
        }

        return null
    }

    private fun resolveYearMonthRange(question: String, today: LocalDate): DateRange? {
        YEAR_MONTH_RE.find(question)?.let { match ->
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt()
            return runCatching {
                val start = LocalDate.of(year, month, 1)
                val end = minOf(start.withDayOfMonth(start.lengthOfMonth()), today)
                DateRange(start, end)
            }.onFailure { Timber.tag(TAG).w(it, "Invalid year+month: $year/$month") }
             .getOrNull()
        }
        return null
    }

    private fun resolveRelativeYearMonthRange(question: String, today: LocalDate): DateRange? {
        val match = RELATIVE_YEAR_MONTH_RE.find(question) ?: return null
        val year = today.year - (relativeYearOffset(match.groupValues[1]) ?: return null)
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toIntOrNull()
        return runCatching {
            if (day != null) {
                LocalDate.of(year, month, day).let { DateRange(it, it) }
            } else {
                val start = LocalDate.of(year, month, 1)
                DateRange(start, minOf(start.withDayOfMonth(start.lengthOfMonth()), today))
            }
        }.onFailure { Timber.tag(TAG).w(it, "Invalid relative year+month: $year/$month/$day") }
         .getOrNull()
    }

    private fun resolveRelativeSeasonRange(question: String, today: LocalDate): DateRange? {
        RELATIVE_SEASON_RE.find(question)?.let { match ->
            val year = today.year - (relativeYearOffset(match.groupValues[1]) ?: return null)
            return seasonRange(match.groupValues[2], year, today)
        }
        return null
    }

    private fun resolveYearSeasonRange(question: String, today: LocalDate): DateRange? {
        YEAR_SEASON_RE.find(question)?.let { match ->
            return seasonRange(match.groupValues[2], match.groupValues[1].toInt(), today)
        }
        return null
    }

    private fun resolveRelativeDateRange(question: String, today: LocalDate): DateRange? {
        when (val offset = relativeYearOffset(question)) {
            null -> Unit
            0 -> return DateRange(LocalDate.of(today.year, 1, 1), today)
            else -> {
                val year = today.year - offset
                return DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
            }
        }

        if (question.contains("先月")) {
            val lastMonth = today.minusMonths(1)
            val start = lastMonth.withDayOfMonth(1)
            val end = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth())
            return DateRange(start, end)
        }

        if (question.contains("今月")) {
            return DateRange(today.withDayOfMonth(1), today)
        }

        return null
    }

    private fun resolveYearOnlyRange(question: String, today: LocalDate): DateRange? {
        YEAR_ONLY_RE.find(question)?.let { match ->
            val year = match.groupValues[1].toInt()
            return runCatching {
                val start = LocalDate.of(year, 1, 1)
                val end = if (year == today.year) today else LocalDate.of(year, 12, 31)
                DateRange(start, end)
            }.onFailure { Timber.tag(TAG).w(it, "Invalid year-only: $year") }
             .getOrNull()
        }
        return null
    }

    private fun resolveEraYear(question: String): Int? {
        for ((pattern, offset) in ERA_LIST) {
            pattern.find(question)?.let { match ->
                val n = match.groupValues[1].let { if (it == "元") 1 else it.toInt() }
                return offset + n
            }
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
    }.onFailure { Timber.tag(TAG).w(it, "Invalid quarter: Q$q $year") }
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
    }.onFailure { Timber.tag(TAG).w(it, "Invalid season: $season $year") }
     .getOrNull()

    private fun resolveYear(question: String, today: LocalDate, month: Int, day: Int): Int {
        relativeYearOffset(question)?.let { return today.year - it }
        // 年の指定なし：今年のその日がまだ来ていなければ去年。月だけでなく日まで比べる（今日が 10/14 の「10月20日」、ADR-072）
        val notYet = month > today.monthValue || (month == today.monthValue && day > today.dayOfMonth)
        return if (notYet) today.year - 1 else today.year
    }

    // 今年からの年数（来年は -1）。「一昨年」は「昨年」を、「再来年」は「来年」を含むので先に見る（ADR-067 / ADR-072）。
    // 来年以降は未来の期間になり、resolveDateRange / resolveToDateStrings のガードで期間なし・空になる
    private fun relativeYearOffset(question: String): Int? = when {
        question.contains("一昨年") -> 2
        question.contains("再来年") -> -2
        question.contains("来年") -> -1
        question.contains("去年") || question.contains("昨年") -> 1
        question.contains("今年") -> 0
        else -> null
    }
}
