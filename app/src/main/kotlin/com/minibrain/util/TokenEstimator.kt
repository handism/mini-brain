package com.minibrain.util

/**
 * 日本語(非 ASCII)を約 3 文字/トークン、英語(ASCII)を約 4 文字/トークンで推定する簡易見積。
 * 厳密な値が必要な場面ではなく、context 上限を超えないかのざっくり判定に使う。
 */
object TokenEstimator {
    const val MAX_CONTEXT_TOKENS = 1200

    fun estimate(text: String): Int {
        var jpChars = 0
        for (c in text) if (c.code > 127) jpChars++
        val enChars = text.length - jpChars
        return jpChars / 3 + enChars / 4 + 5
    }

    /**
     * 複数の文字列の合計トークン数を、文字列結合によるオブジェクト生成を行わずに推定します。
     */
    fun estimate(vararg texts: String): Int {
        var jpChars = 0
        var totalLength = 0
        for (text in texts) {
            totalLength += text.length
            for (c in text) {
                if (c.code > 127) jpChars++
            }
        }
        val enChars = totalLength - jpChars
        return jpChars / 3 + enChars / 4 + 5
    }
}
