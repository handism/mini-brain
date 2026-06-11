package com.minibrain.data.md

import java.time.LocalDate

object MarkdownMetaExtractor {

    private val HEADING_REGEX = Regex("^#{1,6}\\s+(.+)$", RegexOption.MULTILINE)
    private val TAG_REGEX = Regex("(?<![#\\w])#([\\w\\u4e00-\\u9fff\\u3040-\\u309f\\u30a0-\\u30ff-]+)")

    fun extractHeadings(markdown: String): List<String> =
        HEADING_REGEX.findAll(markdown).map { it.groupValues[1].trim() }.toList()

    fun extractFirstParagraph(markdown: String, maxChars: Int = 200): String {
        val lines = markdown.lines()
        val sb = StringBuilder()
        var i = 0

        // skip YAML frontmatter
        if (lines.firstOrNull()?.trim() == "---") {
            i = 1
            while (i < lines.size) {
                val trimmed = lines[i].trim()
                i++
                if (trimmed == "---" || trimmed == "...") break
            }
        }

        while (i < lines.size && sb.length < maxChars) {
            val line = lines[i].trim()
            i++
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

    fun extractDateFromContent(content: String): String? {
        val lines = content.lines()

        // 1. YAML frontmatter の date: フィールド
        if (lines.firstOrNull()?.trim() == "---") {
            val yamlDateRegex = Regex("""^date:\s*(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})""", RegexOption.IGNORE_CASE)
            for (line in lines.drop(1)) {
                val t = line.trim()
                if (t == "---" || t == "...") break
                yamlDateRegex.find(t)?.destructured?.let { (y, m, d) ->
                    return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()).toString() }.getOrNull()
                }
            }
        }

        // 2. 「ラベル: YYYY/MM/DD」形式の行
        val labeledDateRegex = Regex(
            """(?:初回訪問日|訪問日|来訪日|日付|date|created|作成日|記録日|イベント日|visited)[：:]\s*(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})""",
            RegexOption.IGNORE_CASE,
        )
        for (line in lines) {
            labeledDateRegex.find(line)?.destructured?.let { (y, m, d) ->
                return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()).toString() }.getOrNull()
            }
        }

        // 3. 本文中の最初の YYYY/MM/DD または YYYY-MM-DD（1990〜今日の範囲のみ）
        val bodyDateRegex = Regex("""(?<!\d)(\d{4})[/.-](\d{1,2})[/.-](\d{1,2})(?!\d)""")
        val today = LocalDate.now()
        for (line in lines) {
            bodyDateRegex.find(line)?.destructured?.let { (y, m, d) ->
                return runCatching {
                    val date = LocalDate.of(y.toInt(), m.toInt(), d.toInt())
                    if (date.year < 1990 || date.isAfter(today)) return@let null
                    date.toString()
                }.getOrNull()
            }
        }
        return null
    }
}
