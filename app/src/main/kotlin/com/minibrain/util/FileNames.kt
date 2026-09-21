package com.minibrain.util

/**
 * ドキュメントのファイル名 / パスから拡張子を除いた stem を取り出し、
 * 「ファイル名がクエリに含まれるか」（topicMatch）の判定規則を 1 箇所に集約する。
 * SearchPipeline のファイル名逆引き・AgentPipeline の plannerHint・
 * ToolExecutor の read_file パス解決が同じ規則を使う（ADR-026）。
 */
object FileNames {
    /**
     * stem がこの文字数以上なら topicMatch の判定対象にする。
     * 日本語の 1 文字ファイル名（`胃.md`）や略語（`AI.md`）を落とさないため 1。
     * 上げると短いファイル名の誤マッチは減るが、それらが検索から漏れる。
     */
    const val MIN_STEM_MATCH_CHARS = 1

    /** `notes/2021-03-15 サウナしきじ.md` → `2021-03-15 サウナしきじ` */
    fun stem(fileNameOrPath: String): String =
        fileNameOrPath.substringAfterLast('/')
            .removeSuffix(".md")
            .removeSuffix(".MD")

    /**
     * ファイル名 stem が queries のいずれかに部分文字列として含まれるか。
     * 形態素解析に依存せず日本語ファイル名を拾うための逆引き判定（ADR-026）。
     */
    fun stemMatchesAnyQuery(fileNameOrPath: String, queries: List<String>): Boolean {
        val stem = stem(fileNameOrPath)
        if (stem.length < MIN_STEM_MATCH_CHARS) return false
        return queries.any { q -> q.contains(stem, ignoreCase = true) }
    }
}
