package com.minibrain.data.md

object Frontmatter {

    private val LINE_BREAK_REGEX = Regex("\r?\n")

    /**
     * 先頭の YAML フロントマター（`---` で始まり `---` か `...` で閉じる）を、区切り線を除いた中身と
     * 残りの本文に分ける。閉じが無ければフロントマターとみなさず、中身は null、本文はそのまま返す。
     */
    fun split(markdown: String): Pair<String?, String> {
        val lines = markdown.split(LINE_BREAK_REGEX)
        if (lines.firstOrNull()?.trim() != "---") return null to markdown
        val end = (1 until lines.size).firstOrNull { lines[it].trim().let { t -> t == "---" || t == "..." } }
            ?: return null to markdown
        val yaml = lines.subList(1, end).joinToString("\n").trim()
        return yaml.ifEmpty { null } to lines.drop(end + 1).joinToString("\n")
    }

    fun strip(markdown: String): String = split(markdown).second
}
