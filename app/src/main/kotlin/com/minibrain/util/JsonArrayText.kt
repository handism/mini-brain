package com.minibrain.util

/**
 * LLM 出力から JSON 配列リテラルを切り出す。前置き・コードフェンス・末尾の解説が
 * 混ざる前提で、最初の `[` から最後の `]` までを素朴に抜き取る。
 * QueryExpander（文字列配列）と LlmReranker（インデックス配列）で共用する。
 */
object JsonArrayText {
    /** 配列リテラルらしき部分文字列。見つからなければ null。 */
    fun extract(raw: String): String? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
    }
}
