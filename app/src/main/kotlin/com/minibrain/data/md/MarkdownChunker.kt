package com.minibrain.data.md

data class Chunk(
    val headingPath: String,
    val text: String,
)

object MarkdownChunker {

    private const val MAX_CHUNK_CHARS = 800
    // OVERLAP_CHARS: 隣接チャンクで重複させる文字数。境界での文脈断絶（先行する代名詞・主語の欠落）を
    // 緩和し、Recall を底上げするためのトレードオフ。大きくするとインデックスサイズが増える。
    // 旧 50 → 120。日本語の 1〜2 文程度が収まる長さ。
    private const val OVERLAP_CHARS = 120
    // セクション境界（見出し直前）に直前セクション末尾の文脈を付け足す。0 で無効。
    private const val SECTION_TAIL_CARRY = 80
    private val HEADING_REGEX = Regex("^(#{1,6})\\s+(.+)$", RegexOption.MULTILINE)

    fun chunk(markdown: String, fileName: String): List<Chunk> {
        if (markdown.isBlank()) return emptyList()

        val sections = splitBySections(markdown, fileName)
        val raw = sections.flatMapIndexed { idx, pair ->
            val (headingPath, body) = pair
            // 直前セクションの末尾を tail として付け足し、見出しまたぎの文脈断絶を緩和する。
            val tail = if (SECTION_TAIL_CARRY > 0 && idx > 0) {
                val prevBody = sections[idx - 1].second
                prevBody.takeLast(SECTION_TAIL_CARRY).trim()
            } else ""
            val carried = if (tail.isNotEmpty()) "$tail\n\n$body" else body
            if (carried.length <= MAX_CHUNK_CHARS) {
                listOf(Chunk(headingPath, carried.trim()))
            } else {
                splitLongSection(headingPath, carried)
            }
        }
        return raw.filter { it.text.isNotBlank() }
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
            // removeLast() は API 35 から java.util.List 由来に解決されるため、minSdk 31 では使えない
            while (headingStack.isNotEmpty() && headingStack.last().first >= level) {
                headingStack.removeAt(headingStack.lastIndex)
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

    private fun splitIntoParagraphs(body: String): List<String> {
        val paragraphs = mutableListOf<String>()
        val buf = StringBuilder()
        var inCode = false
        var blanks = 0

        for (line in body.lineSequence()) {
            if (line.trimStart().startsWith("```")) inCode = !inCode

            when {
                !inCode && line.isBlank() -> {
                    blanks++
                    if (blanks >= 2 && buf.isNotBlank()) {
                        paragraphs += buf.toString()
                        buf.clear()
                    }
                }
                else -> {
                    if (blanks == 1 && buf.isNotBlank()) buf.append('\n')
                    blanks = 0
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(line)
                }
            }
        }
        if (buf.isNotBlank()) paragraphs += buf.toString()
        return paragraphs
    }

    private fun splitLongSection(headingPath: String, body: String): List<Chunk> {
        val paragraphs = splitIntoParagraphs(body)
        val chunks = mutableListOf<Chunk>()
        val buffer = StringBuilder()

        for (para in paragraphs) {
            // もし単一の段落が最大サイズを超えている場合、文字数で分割する
            if (para.length > MAX_CHUNK_CHARS) {
                // 現在のバッファをフラッシュ
                if (buffer.isNotEmpty()) {
                    chunks.add(Chunk(headingPath, buffer.toString().trim()))
                    val keepLen = minOf(buffer.length, OVERLAP_CHARS)
                    buffer.delete(0, buffer.length - keepLen)
                    buffer.append("\n\n")
                }

                var remainingPara = para
                while (remainingPara.length > MAX_CHUNK_CHARS) {
                    val part = remainingPara.take(MAX_CHUNK_CHARS)
                    chunks.add(Chunk(headingPath, part.trim()))
                    remainingPara = remainingPara.drop(MAX_CHUNK_CHARS - OVERLAP_CHARS)
                }
                buffer.append(remainingPara).append("\n\n")
                continue
            }

            if (buffer.isNotEmpty() && buffer.length + para.length + 2 > MAX_CHUNK_CHARS) {
                chunks.add(Chunk(headingPath, buffer.toString().trim()))
                // overlap: 最後の OVERLAP_CHARS 文字を引き継ぐ
                val keepLen = minOf(buffer.length, OVERLAP_CHARS)
                buffer.delete(0, buffer.length - keepLen)
                buffer.append("\n\n")
            }
            buffer.append(para).append("\n\n")
        }

        if (buffer.isNotBlank()) {
            chunks.add(Chunk(headingPath, buffer.toString().trim()))
        }

        return chunks
    }
}
