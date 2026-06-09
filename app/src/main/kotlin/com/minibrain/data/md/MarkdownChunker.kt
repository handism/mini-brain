package com.minibrain.data.md

data class Chunk(
    val headingPath: String,
    val text: String,
)

object MarkdownChunker {

    private const val MAX_CHUNK_CHARS = 800
    private const val OVERLAP_CHARS = 50
    private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$", RegexOption.MULTILINE)

    fun chunk(markdown: String, fileName: String): List<Chunk> {
        if (markdown.isBlank()) return emptyList()

        val sections = splitBySections(markdown, fileName)
        return sections.flatMap { (headingPath, body) ->
            if (body.length <= MAX_CHUNK_CHARS) {
                listOf(Chunk(headingPath, body.trim()))
            } else {
                splitLongSection(headingPath, body)
            }
        }.filter { it.text.isNotBlank() }
    }

    private fun splitBySections(markdown: String, fileName: String): List<Pair<String, String>> {
        val matches = HEADING_REGEX.findAll(markdown).toList()
        if (matches.isEmpty()) {
            return listOf(Pair(fileName, markdown))
        }

        val sections = mutableListOf<Pair<String, String>>()
        val headingStack = mutableListOf<Pair<Int, String>>()

        // テキストが最初の見出しより前にある場合
        val firstMatchStart = matches.first().range.first
        if (firstMatchStart > 0) {
            val preamble = markdown.substring(0, firstMatchStart).trim()
            if (preamble.isNotBlank()) {
                sections.add(Pair(fileName, preamble))
            }
        }

        for (i in matches.indices) {
            val match = matches[i]
            val level = match.groupValues[1].length
            val title = match.groupValues[2].trim()

            // 同レベル以上のものを pop
            while (headingStack.isNotEmpty() && headingStack.last().first >= level) {
                headingStack.removeLast()
            }
            headingStack.add(Pair(level, title))

            val headingPath = (listOf(fileName) + headingStack.map { it.second }).joinToString(" > ")
            val bodyStart = match.range.last + 1
            val bodyEnd = if (i + 1 < matches.size) matches[i + 1].range.first else markdown.length
            val body = markdown.substring(bodyStart, bodyEnd).trim()

            sections.add(Pair(headingPath, body))
        }

        return sections
    }

    private fun splitLongSection(headingPath: String, body: String): List<Chunk> {
        val paragraphs = body.split(Regex("\\n{2,}")).filter { it.isNotBlank() }
        val chunks = mutableListOf<Chunk>()
        var buffer = StringBuilder()

        for (para in paragraphs) {
            if (buffer.isNotEmpty() && buffer.length + para.length + 2 > MAX_CHUNK_CHARS) {
                chunks.add(Chunk(headingPath, buffer.toString().trim()))
                // overlap: 最後の OVERLAP_CHARS 文字を引き継ぐ
                val overlap = buffer.takeLast(OVERLAP_CHARS)
                buffer = StringBuilder(overlap).append("\n\n")
            }
            buffer.append(para).append("\n\n")
        }

        if (buffer.isNotBlank()) {
            chunks.add(Chunk(headingPath, buffer.toString().trim()))
        }

        return chunks
    }
}
