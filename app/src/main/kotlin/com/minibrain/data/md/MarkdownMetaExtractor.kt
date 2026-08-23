package com.minibrain.data.md

import com.minibrain.util.DateValidator
import java.time.LocalDate

object MarkdownMetaExtractor {

    private val HEADING_REGEX = Regex("^#{1,6}\\s+(.+)$", RegexOption.MULTILINE)
    private val TAG_REGEX = Regex("(?<![#\\w])#([\\w\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff-]+)")
    private val YAML_DATE_REGEX = Regex(
        """^(?:date|created|published|updated|日付|作成日|記録日)\s*:\s*['"]?(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})""",
        RegexOption.IGNORE_CASE,
    )
    private val LABELED_DATE_REGEX = Regex(
        """(?:初回訪問日|訪問日|来訪日|日付|date|created|published|updated|作成日|記録日|イベント日|visited)[：:]\s*(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})""",
        RegexOption.IGNORE_CASE,
    )
    private val BODY_DATE_REGEX = Regex("""(?<!\d)(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})(?!\d)""")
    private val HEADING_JP_DATE_REGEX = Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日""")
    private val HEADING_JP_MONTH_REGEX = Regex("""(\d{4})年(\d{1,2})月(?!\d)""")

    fun extractHeadings(markdown: String): List<String> =
        HEADING_REGEX.findAll(markdown).map { it.groupValues[1].trim() }.toList()

    fun extractFirstParagraph(markdown: String, maxChars: Int = 200): String {
        val lineSeq = markdown.lineSequence().iterator()
        if (!lineSeq.hasNext()) return ""

        var firstLine = lineSeq.next()
        val sb = StringBuilder()

        // skip YAML frontmatter
        if (firstLine.trim() == "---") {
            while (lineSeq.hasNext()) {
                val trimmed = lineSeq.next().trim()
                if (trimmed == "---" || trimmed == "...") break
            }
        } else {
            val line = firstLine.trim()
            if (line.isNotEmpty() && !line.startsWith("#") && !line.startsWith("!")) {
                sb.append(line).append(" ")
            }
        }

        while (lineSeq.hasNext() && sb.length < maxChars) {
            val line = lineSeq.next().trim()
            if (line.isEmpty()) {
                if (sb.isNotEmpty()) break
                continue
            }
            if (line.startsWith("#")) continue
            if (line.startsWith("!")) continue
            sb.append(line).append(" ")
        }

        return sb.toString().trim().take(maxChars)
    }

    fun extractTags(markdown: String): List<String> =
        TAG_REGEX.findAll(markdown).map { it.groupValues[1] }.distinct().toList()

    internal fun safeDate(y: String, m: String, d: String = "1", today: LocalDate = LocalDate.now()): String? =
        DateValidator.parseDayNotInFuture(y, m, d, today)

    fun extractDateFromContent(content: String): String? {
        val today = LocalDate.now()

        // 1. YAML frontmatter の date 系フィールド
        val lineSeq = content.lineSequence().iterator()
        if (lineSeq.hasNext() && lineSeq.next().trim() == "---") {
            while (lineSeq.hasNext()) {
                val t = lineSeq.next().trim()
                if (t == "---" || t == "...") break
                YAML_DATE_REGEX.find(t)?.destructured?.let { (y, m, d) ->
                    safeDate(y, m, d, today)?.let { return it }
                }
            }
        }

        // 2. 「ラベル: YYYY/MM/DD」形式の行
        LABELED_DATE_REGEX.findAll(content).firstNotNullOfOrNull {
            val (y, m, d) = it.destructured
            safeDate(y, m, d, today)
        }?.let { return it }

        // 3. 本文中の最初の YYYY/MM/DD または YYYY-MM-DD（1990〜今日の範囲のみ）
        BODY_DATE_REGEX.findAll(content).firstNotNullOfOrNull {
            val (y, m, d) = it.destructured
            safeDate(y, m, d, today)
        }?.let { return it }

        // 4. 見出し中の YYYY年MM月DD日 / YYYY年MM月
        // ※ 本文全行を走査すると「2024年5月に行った」のようなカジュアル言及にも反応し、
        //    日記でないノートに誤って documentDate が付いて Reranker の競合候補を増やしてしまう。
        //    日記ファイルは date を見出し（# 2024年12月15日 等）に置く慣例が強いため見出し限定にする。
        val headings = extractHeadings(content)
        for (heading in headings) {
            HEADING_JP_DATE_REGEX.find(heading)?.destructured?.let { (y, m, d) ->
                safeDate(y, m, d, today)?.let { return it }
            }
        }
        for (heading in headings) {
            HEADING_JP_MONTH_REGEX.find(heading)?.destructured?.let { (y, m) ->
                safeDate(y, m, today = today)?.let { return it }
            }
        }

        return null
    }
}
