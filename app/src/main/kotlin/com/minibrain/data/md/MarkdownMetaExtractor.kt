package com.minibrain.data.md

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
}
