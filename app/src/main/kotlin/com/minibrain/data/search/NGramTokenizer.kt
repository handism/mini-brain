package com.minibrain.data.search

object NGramTokenizer {

    /**
     * テキストを FTS4 用のバイグラムトークン列に変換する。
     *
     * 戦略:
     *   - ASCII 英数字の連続 → 単語単位で 1 トークン（英語の部分一致は word-boundary で十分）
     *   - 非 ASCII（CJK・日本語など）の連続 → ユニグラム + バイグラムの両方を生成
     *     ユニグラムは 1 文字検索を可能にし、バイグラムは隣接文字の共起で精度を上げる
     *   - 空白・ASCII 記号はすべてセパレータとして除去
     */
    fun toBigrams(text: String): String {
        val tokens = mutableListOf<String>()
        var i = 0
        val sb = java.lang.StringBuilder()

        while (i < text.length) {
            val c = normalize(text[i])
            when {
                c == ' ' -> i++
                c.code < 128 -> {
                    i = processAscii(text, i, c, sb, tokens)
                }
                else -> {
                    i = processNonAscii(text, i, c, sb, tokens)
                }
            }
        }
        return tokens.joinToString(" ")
    }

    private fun processAscii(
        text: String,
        startIndex: Int,
        firstChar: Char,
        sb: java.lang.StringBuilder,
        tokens: MutableList<String>
    ): Int {
        var i = startIndex
        // ASCII 英数字の連続 → 単一トークン（例: "hello", "2024"）
        sb.clear()
        sb.append(firstChar)
        i++
        while (i < text.length) {
            val nextC = normalize(text[i])
            if (nextC == ' ' || nextC.code >= 128) break
            sb.append(nextC)
            i++
        }
        tokens.add(sb.toString())
        return i
    }

    private fun processNonAscii(
        text: String,
        startIndex: Int,
        firstChar: Char,
        sb: java.lang.StringBuilder,
        tokens: MutableList<String>
    ): Int {
        var i = startIndex
        // 非 ASCII の連続（日本語・中国語等）→ ユニグラム + バイグラム
        sb.clear()
        sb.append(firstChar)
        i++
        while (i < text.length) {
            val nextC = normalize(text[i])
            if (nextC == ' ' || nextC.code < 128) break
            sb.append(nextC)
            i++
        }
        val run = sb.toString()

        // ユニグラム: 1 文字検索（例: "猫" だけで検索できるように）
        for (j in 0 until run.length) {
            tokens.add(run.substring(j, j + 1))
        }

        // バイグラム: 隣接 2 文字（例: "猫が" "が好" "好き"）
        for (j in 0 until run.length - 1) {
            tokens.add(run.substring(j, j + 2))
        }
        return i
    }

    private fun normalize(c: Char): Char {
        return when {
            c.isWhitespace() -> ' '
            c.code < 128 && !c.isLetterOrDigit() -> ' '
            else -> c.lowercaseChar()
        }
    }

    /** FTS4 の MATCH 式を構築する。トークンが空なら null を返す。 */
    fun toFtsMatchQuery(text: String): String? {
        val tokens = toBigrams(text).split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" OR ") { "\"$it\"" }
    }
}
