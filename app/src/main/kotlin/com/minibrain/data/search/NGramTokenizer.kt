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
        // ステップ1: 空白化 & 小文字正規化
        // ASCII 制御文字・記号はスペースに置換し、英字は小文字に統一する
        val cleaned = buildString(text.length) {
            for (c in text) {
                when {
                    c.isWhitespace() -> append(' ')
                    c.code < 128 && !c.isLetterOrDigit() -> append(' ')
                    else -> append(c.lowercaseChar())
                }
            }
        }

        // ステップ2: トークン化
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < cleaned.length) {
            val c = cleaned[i]
            when {
                c == ' ' -> i++
                c.code < 128 -> {
                    // ASCII 英数字の連続 → 単一トークン（例: "hello", "2024"）
                    val start = i
                    while (i < cleaned.length && cleaned[i] != ' ' && cleaned[i].code < 128) i++
                    tokens.add(cleaned.substring(start, i))
                }
                else -> {
                    // 非 ASCII の連続（日本語・中国語等）→ ユニグラム + バイグラム
                    val start = i
                    while (i < cleaned.length && cleaned[i] != ' ' && cleaned[i].code >= 128) i++
                    val run = cleaned.substring(start, i)

                    // ユニグラム: 1 文字検索（例: "猫" だけで検索できるように）
                    for (j in 0 until run.length) {
                        tokens.add(run.substring(j, j + 1))
                    }

                    // バイグラム: 隣接 2 文字（例: "猫が" "が好" "好き"）
                    for (j in 0 until run.length - 1) {
                        tokens.add(run.substring(j, j + 2))
                    }
                }
            }
        }
        return tokens.joinToString(" ")
    }

    /** FTS4 の MATCH 式を構築する。トークンが空なら null を返す。 */
    fun toFtsMatchQuery(text: String): String? {
        val tokens = toBigrams(text).split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" OR ") { "\"$it\"" }
    }
}
